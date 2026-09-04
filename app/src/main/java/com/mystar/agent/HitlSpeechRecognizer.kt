package com.mystar.agent

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.content.ContextCompat
import com.mystar.agent.agent.AskUserAnswer
import com.mystar.agent.agent.AskUserKind
import com.mystar.agent.agent.AskUserResultFormatter
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/** HITL 오버레이용 인앱 SpeechRecognizer. Activity 전환 없이 듣는다. */
object HitlSpeechRecognizer {

    private val inProgress = AtomicBoolean(false)
    private val activeRecognizer = AtomicReference<SpeechRecognizer?>(null)

    suspend fun recognize(
        context: Context,
        prompt: String,
        kind: AskUserKind,
        onStatus: (String) -> Unit = {},
    ): AskUserAnswer? {
        if (!inProgress.compareAndSet(false, true)) {
            ServiceStatus.appendLog("HITL 음성: 이미 인식 중")
            return null
        }
        val appContext = context.applicationContext
        try {
            if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                ServiceStatus.appendLog("HITL 음성: RECORD_AUDIO 권한 없음")
                onStatus("마이크 권한 필요 — MyStar 앱에서 허용하세요")
                return null
            }
            if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
                ServiceStatus.appendLog("HITL 음성: 인식 제공자 없음")
                onStatus("인식 실패")
                return null
            }

            MicForegroundService.start(appContext)
            Log.i(TAG, "FGS start, listen timeout=${SPEECH_TIMEOUT_MS}ms kind=$kind")
            onStatus("듣는 중…")

            val spoken = listenOnce(appContext, prompt)
            Log.i(TAG, "listenOnce done spoken=${spoken?.take(40) ?: "null"}")

            if (spoken.isNullOrBlank()) {
                ServiceStatus.appendLog("HITL 음성: 결과 없음")
                onStatus("인식 실패 — 다시 말하거나 텍스트/버튼으로 답하세요")
                return null
            }

            ServiceStatus.appendLog("HITL 음성: $spoken")
            onStatus("")
            return when (kind) {
                AskUserKind.CONFIRM -> AskUserResultFormatter.parseConfirmSpeech(spoken)
                    ?: AskUserAnswer.Text(spoken)
                AskUserKind.MISSING_INFO -> AskUserAnswer.Text(spoken)
            }
        } finally {
            cancelActiveRecognizer()
            MicForegroundService.stop(appContext)
            inProgress.set(false)
        }
    }

    fun cancel() {
        cancelActiveRecognizer()
    }

    private fun cancelActiveRecognizer() {
        activeRecognizer.getAndSet(null)?.let { recognizer ->
            Log.i(TAG, "cancelActiveRecognizer")
            try {
                recognizer.cancel()
            } catch (_: Exception) {
            }
            try {
                recognizer.destroy()
            } catch (_: Exception) {
            }
        }
    }

    private suspend fun listenOnce(context: Context, prompt: String): String? =
        suspendCancellableCoroutine { cont ->
            val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
            activeRecognizer.set(recognizer)
            var lastPartial: String? = null
            val mainHandler = Handler(Looper.getMainLooper())

            fun firstRecognition(bundle: Bundle?): String? =
                bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                )
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.KOREA.toLanguageTag())
                putExtra(
                    RecognizerIntent.EXTRA_PROMPT,
                    prompt.ifEmpty { "답변을 말씀해 주세요" },
                )
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(
                    RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                    SPEECH_SILENCE_MS,
                )
                putExtra(
                    RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                    SPEECH_SILENCE_MS,
                )
            }

            lateinit var timeoutRunnable: Runnable

            fun finishInternal(text: String?) {
                if (!cont.isActive) return
                mainHandler.removeCallbacks(timeoutRunnable)
                activeRecognizer.compareAndSet(recognizer, null)
                try {
                    recognizer.destroy()
                } catch (_: Exception) {
                }
                cont.resume(text)
            }

            timeoutRunnable = Runnable {
                Log.i(TAG, "timeout ${SPEECH_TIMEOUT_MS}ms lastPartial=$lastPartial")
                finishInternal(lastPartial)
            }

            cont.invokeOnCancellation {
                mainHandler.removeCallbacks(timeoutRunnable)
                try {
                    recognizer.cancel()
                } catch (_: Exception) {
                }
                try {
                    recognizer.destroy()
                } catch (_: Exception) {
                }
                activeRecognizer.compareAndSet(recognizer, null)
            }

            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    Log.i(TAG, "onReadyForSpeech")
                }

                override fun onBeginningOfSpeech() {
                    Log.i(TAG, "onBeginningOfSpeech")
                }

                override fun onRmsChanged(rmsdB: Float) = Unit

                override fun onBufferReceived(buffer: ByteArray?) = Unit

                override fun onEndOfSpeech() {
                    Log.i(TAG, "onEndOfSpeech lastPartial=$lastPartial")
                    if (lastPartial != null) {
                        finishInternal(lastPartial)
                    }
                }

                override fun onError(error: Int) {
                    val message = errorMessage(error)
                    Log.e(TAG, "onError $message ($error) lastPartial=$lastPartial")
                    if (lastPartial == null) {
                        ServiceStatus.appendLog("HITL 음성: 오류 — $message ($error)")
                    }
                    finishInternal(lastPartial)
                }

                override fun onResults(results: Bundle?) {
                    val finalResult = firstRecognition(results)
                    val selected = finalResult ?: lastPartial
                    Log.i(TAG, "onResults final=$finalResult selected=$selected")
                    finishInternal(selected)
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val partial = firstRecognition(partialResults) ?: return
                    lastPartial = partial
                    Log.i(TAG, "onPartialResults $partial")
                }

                override fun onEvent(eventType: Int, params: Bundle?) {
                    Log.i(TAG, "onEvent type=$eventType")
                }
            })

            Log.i(TAG, "startListening lang=${Locale.KOREA.toLanguageTag()}")
            mainHandler.postDelayed(timeoutRunnable, SPEECH_TIMEOUT_MS)
            recognizer.startListening(intent)
        }

    private fun errorMessage(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "오디오"
        SpeechRecognizer.ERROR_CLIENT -> "클라이언트"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "권한 부족"
        SpeechRecognizer.ERROR_NETWORK -> "네트워크"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "네트워크 타임아웃"
        SpeechRecognizer.ERROR_NO_MATCH -> "인식 없음"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "인식기 사용 중"
        SpeechRecognizer.ERROR_SERVER -> "서버"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "무음"
        else -> "알 수 없음"
    }

    private const val TAG = "HitlStt"
    private const val SPEECH_SILENCE_MS = 3_000
    private const val SPEECH_TIMEOUT_MS = 8_000L
}
