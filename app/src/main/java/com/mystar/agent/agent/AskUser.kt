package com.mystar.agent.agent

import com.mystar.agent.tool.ToolResult

enum class AskUserKind {
    MISSING_INFO,
    CONFIRM,
    ;

    companion object {
        fun fromString(value: String): AskUserKind? = when (value.trim().lowercase()) {
            "missing_info" -> MISSING_INFO
            "confirm" -> CONFIRM
            else -> null
        }

        fun toApiString(kind: AskUserKind): String = when (kind) {
            MISSING_INFO -> "missing_info"
            CONFIRM -> "confirm"
        }
    }
}

data class AskUserPrompt(
    val question: String,
    val kind: AskUserKind,
)

sealed class AskUserAnswer {
    data class Text(val value: String) : AskUserAnswer()
    data object Approved : AskUserAnswer()
    data object Rejected : AskUserAnswer()
    data object Timeout : AskUserAnswer()
    data object Cancelled : AskUserAnswer()
}

object AskUserResultFormatter {
    fun toToolResult(kind: AskUserKind, answer: AskUserAnswer): ToolResult {
        val kindLabel = AskUserKind.toApiString(kind)
        return when (answer) {
            is AskUserAnswer.Text -> ToolResult(
                success = true,
                message = "ask_user kind=$kindLabel answer=${answer.value}",
            )
            AskUserAnswer.Approved -> ToolResult(
                success = true,
                message = "ask_user kind=$kindLabel answer=approved",
            )
            AskUserAnswer.Rejected -> ToolResult(
                success = true,
                message = "ask_user kind=$kindLabel answer=rejected",
            )
            AskUserAnswer.Timeout -> ToolResult(
                success = false,
                message = "ask_user 실패: 60초 동안 응답 없음. finish로 알리거나 안전하게 중단하라.",
            )
            AskUserAnswer.Cancelled -> ToolResult(
                success = false,
                message = "ask_user 실패: 사용자가 작업을 중단했습니다.",
            )
        }
    }

    /** confirm 음성 인식 결과를 승인/거절로 해석한다. */
    fun parseConfirmSpeech(spoken: String): AskUserAnswer? {
        val normalized = spoken.trim().lowercase()
        if (normalized.isEmpty()) return null
        val approveKeywords = listOf(
            "보내", "보내줘", "예", "응", "네", "승인", "확인", "좋아", "ok", "okay", "yes",
        )
        val rejectKeywords = listOf(
            "취소", "아니", "아니요", "안돼", "안 보내", "거절", "싫어", "no", "cancel",
        )
        if (approveKeywords.any { normalized.contains(it) }) return AskUserAnswer.Approved
        if (rejectKeywords.any { normalized.contains(it) }) return AskUserAnswer.Rejected
        return null
    }
}
