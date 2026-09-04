package com.mystar.agent

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/** finish summary를 한국어 TTS로 낭독한다. Activity 수명에 맞춰 init/shutdown 한다. */
class TtsHelper(context: Context) : TextToSpeech.OnInitListener {
    private val appContext = context.applicationContext
    private var tts: TextToSpeech? = null
    private var ready = false
    private val pending = mutableListOf<String>()
    private val awaitContinuation = AtomicReference<Continuation<Unit>?>(null)
    private val awaitUtteranceId = AtomicReference<String?>(null)

    fun init() {
        if (tts != null) return
        tts = TextToSpeech(appContext, this)
    }

    fun shutdown() {
        clearAwaitContinuation()
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready = false
        pending.clear()
    }

    fun stop() {
        tts?.stop()
        clearAwaitContinuation()
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            Log.w(TAG, "TTS init failed: status=$status")
            ServiceStatus.appendLog("TTS: 초기화 실패 (status=$status)")
            return
        }
        val engine = tts ?: return
        val langResult = engine.setLanguage(Locale.KOREA)
        ready = langResult != TextToSpeech.LANG_MISSING_DATA &&
            langResult != TextToSpeech.LANG_NOT_SUPPORTED
        if (!ready) {
            Log.w(TAG, "TTS language not supported: $langResult")
            ServiceStatus.appendLog("TTS: 한국어 미지원")
            return
        }
        ensureUtteranceListener(engine)
        val queued = pending.toList()
        pending.clear()
        for (text in queued) {
            speakInternal(text)
        }
    }

    fun speak(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        if (!ready) {
            pending.add(trimmed)
            return
        }
        speakInternal(trimmed)
    }

    suspend fun speakAwaitingDone(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        if (!ready) {
            ServiceStatus.appendLog("TTS: 미초기화 — HITL 질문 낭독 생략")
            return
        }
        suspendCancellableCoroutine { cont ->
            val utteranceId = "hitl_${System.nanoTime()}"
            awaitUtteranceId.set(utteranceId)
            awaitContinuation.set(cont)
            cont.invokeOnCancellation {
                if (awaitUtteranceId.compareAndSet(utteranceId, null)) {
                    awaitContinuation.compareAndSet(cont, null)
                    tts?.stop()
                }
            }
            val engine = tts
            if (engine == null) {
                clearAwaitContinuation()
                cont.resume(Unit)
                return@suspendCancellableCoroutine
            }
            ensureUtteranceListener(engine)
            val result = engine.speak(trimmed, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
            if (result == TextToSpeech.ERROR) {
                Log.w(TAG, "TTS speak failed")
                ServiceStatus.appendLog("TTS: 낭독 실패")
                finishAwait(utteranceId)
            } else {
                ServiceStatus.appendLog("TTS: $trimmed")
            }
        }
    }

    private fun speakInternal(text: String) {
        val engine = tts
        if (engine == null || !ready) {
            pending.add(text)
            return
        }
        val result = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, FINISH_UTTERANCE_ID)
        if (result == TextToSpeech.ERROR) {
            Log.w(TAG, "TTS speak failed")
            ServiceStatus.appendLog("TTS: 낭독 실패")
        } else {
            ServiceStatus.appendLog("TTS: $text")
        }
    }

    private fun ensureUtteranceListener(engine: TextToSpeech) {
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit

            override fun onDone(utteranceId: String?) {
                finishAwait(utteranceId)
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                finishAwait(utteranceId)
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                finishAwait(utteranceId)
            }

            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                finishAwait(utteranceId)
            }
        })
    }

    private fun finishAwait(utteranceId: String?) {
        if (utteranceId == null || utteranceId != awaitUtteranceId.get()) return
        clearAwaitContinuation()
    }

    private fun clearAwaitContinuation() {
        awaitUtteranceId.set(null)
        awaitContinuation.getAndSet(null)?.resume(Unit)
    }

    companion object {
        private const val TAG = "AgentTts"
        private const val FINISH_UTTERANCE_ID = "finish_summary"
    }
}
