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
)

data class ToolResult(
    val success: Boolean,
    val message: String,
)
