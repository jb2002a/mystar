package com.mystar.agent

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import com.mystar.agent.agent.AskUserAnswer
import com.mystar.agent.agent.AskUserKind
import com.mystar.agent.agent.AskUserResultFormatter
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

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
            onStatus("듣는 중…")

            val spoken = withTimeoutOrNull(SPEECH_TIMEOUT_MS) {
                listenOnce(appContext, prompt)
            }

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
                putExtra(
                    RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS,
                    SPEECH_MIN_LISTEN_MS,
                )
                putExtra(
                    RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                    SPEECH_SILENCE_MS,
                )
                putExtra(
                    RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                    SPEECH_SILENCE_MS,
                )
            }

            fun finish(text: String?) {
                if (!cont.isActive) return
                activeRecognizer.compareAndSet(recognizer, null)
                try {
                    recognizer.destroy()
                } catch (_: Exception) {
                }
                cont.resume(text)
            }

            cont.invokeOnCancellation {
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
                override fun onReadyForSpeech(params: Bundle?) = Unit

                override fun onBeginningOfSpeech() = Unit

                override fun onRmsChanged(rmsdB: Float) = Unit

                override fun onBufferReceived(buffer: ByteArray?) = Unit

                override fun onEndOfSpeech() = Unit

                override fun onError(error: Int) {
                    val message = errorMessage(error)
                    ServiceStatus.appendLog("HITL 음성: 오류 — $message ($error)")
                    finish(null)
                }

                override fun onResults(results: Bundle?) {
                    val spoken = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        ?.trim()
                    finish(spoken?.takeIf { it.isNotEmpty() })
                }

                override fun onPartialResults(partialResults: Bundle?) = Unit

                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })

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

    private const val SPEECH_MIN_LISTEN_MS = 8_000
    private const val SPEECH_SILENCE_MS = 3_000
    private const val SPEECH_TIMEOUT_MS = 45_000L
}
