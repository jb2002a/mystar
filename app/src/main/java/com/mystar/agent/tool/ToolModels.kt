package com.mystar.agent.tool

import kotlinx.serialization.json.JsonObject

data class ToolDefinition(
    val name: String,
    val description: String,
    val parameters: JsonObject,
)

data class ToolCall(
    val name: String,
    val args: JsonObject,
    /** OpenAI tool_calls[].id — 히스토리의 role:tool 과 연결할 때 사용. */
    val id: String = "",
)

data class ToolResult(
    val success: Boolean,
    val message: String,
)
