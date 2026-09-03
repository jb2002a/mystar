package com.mystar.agent

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

/** finish summary를 한국어 TTS로 낭독한다. Activity 수명에 맞춰 init/shutdown 한다. */
class TtsHelper(context: Context) : TextToSpeech.OnInitListener {
    private val appContext = context.applicationContext
    private var tts: TextToSpeech? = null
    private var ready = false
    private val pending = mutableListOf<String>()

    fun init() {
        if (tts != null) return
        tts = TextToSpeech(appContext, this)
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready = false
        pending.clear()
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

    private fun speakInternal(text: String) {
        val engine = tts
        if (engine == null || !ready) {
            pending.add(text)
            return
        }
        val result = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
        if (result == TextToSpeech.ERROR) {
            Log.w(TAG, "TTS speak failed")
            ServiceStatus.appendLog("TTS: 낭독 실패")
        } else {
            ServiceStatus.appendLog("TTS: $text")
        }
    }

    companion object {
        private const val TAG = "AgentTts"
        private const val UTTERANCE_ID = "finish_summary"
    }
}
