package com.mystar.agent.llm

import com.mystar.agent.tool.ToolCall
import kotlinx.serialization.json.JsonObject

sealed class LlmResult {
    data class Success(
        val toolCall: ToolCall,
        /** 히스토리에 그대로 넣을 assistant/model message. */
        val assistantMessage: JsonObject,
        val inputTokens: Int? = null,
        val outputTokens: Int? = null,
        /** 응답 usage.total_tokens. Gemini는 thinking 토큰이 여기에만 포함될 수 있다. */
        val totalTokens: Int? = null,
    ) : LlmResult()

    data class Failure(
        val message: String,
        val inputTokens: Int? = null,
        val outputTokens: Int? = null,
        val totalTokens: Int? = null,
    ) : LlmResult()
}
