package com.mystar.agent

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.mystar.agent.agent.AskUserAnswer
import com.mystar.agent.agent.AskUserKind
import com.mystar.agent.agent.AskUserPrompt
import com.mystar.agent.agent.AskUserResultFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * M10: 접근성 오버레이 HITL 패널.
 * 카톡 등 다른 앱 위에 질문을 띄우고 사용자 답을 기다린다.
 */
class HitlOverlayHost(private val service: AgentAccessibilityService) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var panelRoot: LinearLayout? = null
    private var onAnswer: ((AskUserAnswer) -> Unit)? = null
    private var answered = false

    suspend fun askUser(
        prompt: AskUserPrompt,
        speakQuestion: (String) -> Unit,
        aborted: () -> Boolean,
    ): AskUserAnswer = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            answered = false
            fun finish(answer: AskUserAnswer) {
                if (answered) return
                answered = true
                dismissPanel()
                if (cont.isActive) cont.resume(answer)
            }

            showPanel(prompt) { answer ->
                finish(answer)
            }

            speakQuestion(prompt.question)
            ServiceStatus.appendLog("HITL: 질문 (${AskUserKind.toApiString(prompt.kind)}) — ${prompt.question}")

            cont.invokeOnCancellation {
                mainHandler.post {
                    if (!answered) {
                        answered = true
                        dismissPanel()
                    }
                }
            }

            // 강제 종료 폴링
            val pollRunnable = object : Runnable {
                override fun run() {
                    if (answered) return
                    if (aborted()) {
                        finish(AskUserAnswer.Cancelled)
                        return
                    }
                    mainHandler.postDelayed(this, ABORT_POLL_MS)
                }
            }
            mainHandler.postDelayed(pollRunnable, ABORT_POLL_MS)
            cont.invokeOnCancellation {
                mainHandler.removeCallbacks(pollRunnable)
            }
        }
    }

    fun dismissIfShowing() {
        mainHandler.post { dismissPanel() }
    }

    private fun showPanel(prompt: AskUserPrompt, onResult: (AskUserAnswer) -> Unit) {
        dismissPanel()
        onAnswer = onResult

        val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val dm = service.resources.displayMetrics
        val pad = (16 * dm.density).toInt()

        val root = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#EE1E1E2E"))
            setPadding(pad, pad, pad, pad)
            elevation = 12f
        }

        val title = TextView(service).apply {
            text = if (prompt.kind == AskUserKind.CONFIRM) "확인" else "질문"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTypeface(typeface, Typeface.BOLD)
        }
        root.addView(title)

        val questionView = TextView(service).apply {
            text = prompt.question
            setTextColor(Color.parseColor("#E0E0E0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(0, pad / 2, 0, pad)
        }
        root.addView(questionView)

        val buttonRow = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }

        fun completeOnce(answer: AskUserAnswer) {
            val callback = onAnswer ?: return
            onAnswer = null
            callback(answer)
        }

        when (prompt.kind) {
            AskUserKind.MISSING_INFO -> {
                val input = EditText(service).apply {
                    hint = "답변 입력"
                    setTextColor(Color.WHITE)
                    setHintTextColor(Color.GRAY)
                    setSingleLine(true)
                }
                root.addView(input, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = pad / 2 })

                val micBtn = makeButton("🎤") {
                    service.launchScopeForHitl {
                        val answer = AskUserSpeechCoordinator.recognize(
                            service,
                            prompt.question,
                            prompt.kind,
                        )
                        mainHandler.post {
                            if (answer is AskUserAnswer.Text) {
                                input.setText(answer.value)
                            } else if (answer != null) {
                                completeOnce(answer)
                            }
                        }
                    }
                }
                val okBtn = makeButton("확인") {
                    val text = input.text?.toString()?.trim().orEmpty()
                    if (text.isNotEmpty()) completeOnce(AskUserAnswer.Text(text))
                }
                buttonRow.addView(micBtn)
                buttonRow.addView(okBtn)
            }
            AskUserKind.CONFIRM -> {
                val rejectBtn = makeButton("거절") {
                    completeOnce(AskUserAnswer.Rejected)
                }
                val approveBtn = makeButton("승인") {
                    completeOnce(AskUserAnswer.Approved)
                }
                val micBtn = makeButton("🎤") {
                    service.launchScopeForHitl {
                        val answer = AskUserSpeechCoordinator.recognize(
                            service,
                            prompt.question,
                            prompt.kind,
                        )
                        mainHandler.post {
                            when (answer) {
                                is AskUserAnswer.Approved, AskUserAnswer.Rejected -> completeOnce(answer)
                                is AskUserAnswer.Text -> {
                                    val parsed = AskUserResultFormatter.parseConfirmSpeech(answer.value)
                                    if (parsed != null) {
                                        completeOnce(parsed)
                                    } else {
                                        ServiceStatus.appendLog(
                                            "HITL: confirm 음성 불명확 — ${answer.value}",
                                        )
                                    }
                                }
                                else -> Unit
                            }
                        }
                    }
                }
                buttonRow.addView(micBtn)
                buttonRow.addView(rejectBtn)
                buttonRow.addView(approveBtn)
            }
        }
        root.addView(buttonRow)

        val params = WindowManager.LayoutParams(
            (dm.widthPixels * 0.88f).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.CENTER
        }

        wm.addView(root, params)
        panelRoot = root
        service.setDumpOverlayHidden(true)
    }

    private fun makeButton(label: String, onClick: () -> Unit): Button {
        val pad = (12 * service.resources.displayMetrics.density).toInt()
        return Button(service).apply {
            text = label
            textSize = 14f
            setPadding(pad, pad / 2, pad, pad / 2)
            setOnClickListener { onClick() }
        }
    }

    private fun dismissPanel() {
        onAnswer = null
        val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        panelRoot?.let { view ->
            try {
                wm.removeView(view)
            } catch (_: Exception) {
            }
        }
        panelRoot = null
        service.setDumpOverlayHidden(false)
    }

    companion object {
        private const val ABORT_POLL_MS = 200L
    }
}
