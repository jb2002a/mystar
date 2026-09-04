package com.mystar.agent

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.mystar.agent.agent.AskUserAnswer
import com.mystar.agent.agent.AskUserKind
import com.mystar.agent.agent.AskUserResultFormatter
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/** HITL 오버레이에서 RecognizerIntent를 띄우기 위한 얇은 Activity. */
class AskUserSpeechActivity : ComponentActivity() {

  private val speechLauncher = registerForActivityResult(
      ActivityResultContracts.StartActivityForResult(),
  ) { result ->
      val spoken = if (result.resultCode == Activity.RESULT_OK) {
          result.data
              ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
              ?.firstOrNull()
              ?.trim()
              .orEmpty()
      } else {
          ""
      }
      AskUserSpeechCoordinator.complete(spoken.ifEmpty { null })
      finish()
  }

  override fun onCreate(savedInstanceState: Bundle?) {
      super.onCreate(savedInstanceState)
      val prompt = intent.getStringExtra(EXTRA_PROMPT).orEmpty()
      if (!SpeechRecognizer.isRecognitionAvailable(this)) {
          ServiceStatus.appendLog("HITL 음성: 인식 제공자 없음")
          AskUserSpeechCoordinator.complete(null)
          finish()
          return
      }
      val speechIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
          putExtra(
              RecognizerIntent.EXTRA_LANGUAGE_MODEL,
              RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
          )
          putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.KOREA.toLanguageTag())
          putExtra(RecognizerIntent.EXTRA_PROMPT, prompt.ifEmpty { "답변을 말씀해 주세요" })
          putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, SPEECH_MIN_LISTEN_MS)
          putExtra(
              RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
              SPEECH_SILENCE_MS,
          )
          putExtra(
              RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
              SPEECH_SILENCE_MS,
          )
      }
      try {
          speechLauncher.launch(speechIntent)
      } catch (e: ActivityNotFoundException) {
          ServiceStatus.appendLog("HITL 음성: Activity 없음 — ${e.message}")
          AskUserSpeechCoordinator.complete(null)
          finish()
      }
  }

  companion object {
      const val EXTRA_PROMPT = "prompt"
      private const val SPEECH_MIN_LISTEN_MS = 8_000
      private const val SPEECH_SILENCE_MS = 3_000
  }
}

object AskUserSpeechCoordinator {
    private val pending = AtomicReference<CompletableDeferred<String?>?>(null)

    suspend fun recognize(context: Context, prompt: String, kind: AskUserKind): AskUserAnswer? {
        val deferred = CompletableDeferred<String?>()
        if (!pending.compareAndSet(null, deferred)) {
            ServiceStatus.appendLog("HITL 음성: 이미 인식 중")
            return null
        }
        val intent = Intent(context, AskUserSpeechActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(AskUserSpeechActivity.EXTRA_PROMPT, prompt)
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            pending.set(null)
            ServiceStatus.appendLog("HITL 음성: 시작 실패 — ${e.message}")
            return null
        }
        val spoken = try {
            withTimeoutOrNull(SPEECH_TIMEOUT_MS) { deferred.await() }
        } finally {
            pending.set(null)
        }
        if (spoken.isNullOrBlank()) return null
        return when (kind) {
            AskUserKind.CONFIRM -> AskUserResultFormatter.parseConfirmSpeech(spoken)
                ?: AskUserAnswer.Text(spoken)
            AskUserKind.MISSING_INFO -> AskUserAnswer.Text(spoken)
        }
    }

    fun complete(text: String?) {
        pending.getAndSet(null)?.complete(text)
    }

    private const val SPEECH_TIMEOUT_MS = 45_000L
}
