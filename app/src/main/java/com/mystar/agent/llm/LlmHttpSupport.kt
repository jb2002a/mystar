package com.mystar.agent.llm

import android.util.Log
import com.mystar.agent.tool.ToolCall
import com.mystar.agent.tracing.LangSmithClient
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType

internal object LlmHttpSupport {
    const val TAG = "AgentA11y"
    const val LOG_CHUNK = 3500
    val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    fun logChunked(tag: String, label: String, text: String) {
        if (text.isEmpty()) {
            Log.i(tag, "$label: <empty>")
            return
        }
        if (text.length <= LOG_CHUNK) {
            Log.i(tag, "$label:\n$text")
            return
        }
        var offset = 0
        var part = 1
        val total = (text.length + LOG_CHUNK - 1) / LOG_CHUNK
        while (offset < text.length) {
            val end = minOf(offset + LOG_CHUNK, text.length)
            Log.i(tag, "$label ($part/$total):\n${text.substring(offset, end)}")
            offset = end
            part++
        }
    }

    fun attachUsage(
        result: LlmResult,
        inputTokens: Int?,
        outputTokens: Int?,
        totalTokens: Int?,
    ): LlmResult = when (result) {
        is LlmResult.Success -> result.copy(
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            totalTokens = totalTokens,
        )
        is LlmResult.Failure -> result.copy(
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            totalTokens = totalTokens,
        )
    }

    fun buildLlmRunOutputs(
        model: String,
        latencyMs: Long,
        toolCall: ToolCall? = null,
        error: String? = null,
        usageMetadata: JsonObject? = null,
    ): JsonObject = buildJsonObject {
        put("model", model)
        put("latency_ms", latencyMs)
        if (toolCall != null) {
            put("tool_name", toolCall.name)
            put(
                "tool_args",
                LangSmithClient.sanitizeToolArgs(toolCall.name, toolCall.args),
            )
            put("tool_call_id", toolCall.id)
        }
        if (error != null) {
            put("error", error)
        }
        if (usageMetadata != null) {
            put("usage_metadata", usageMetadata)
        }
    }

    fun tokensFromUsage(usageMetadata: JsonObject?): Pair<Int?, Int?> {
        if (usageMetadata == null) return null to null
        val inputTokens = usageMetadata["input_tokens"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
        val outputTokens = usageMetadata["output_tokens"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
        return inputTokens to outputTokens
    }
}
