package com.mystar.agent.llm

import com.mystar.agent.BuildConfig
import kotlinx.serialization.json.JsonObject

interface LlmClient {
    fun configErrorOrNull(): String?

    suspend fun chooseNextTool(
        messages: List<JsonObject>,
        parentRunId: String? = null,
    ): LlmResult

    fun buildSystemMessage(): JsonObject = LlmPrompt.buildSystemMessage()

    fun buildGoalUserMessage(goal: String): JsonObject = LlmPrompt.buildGoalUserMessage(goal)

    fun buildInitialAppCatalogMessage(catalog: String): JsonObject =
        LlmPrompt.buildInitialAppCatalogMessage(catalog)

    fun buildInitialScreenContextMessage(screenTree: String): JsonObject =
        LlmPrompt.buildInitialScreenContextMessage(screenTree)

    fun buildCurrentScreenMessage(screenTree: String): JsonObject =
        LlmPrompt.buildCurrentScreenMessage(screenTree)

    fun buildToolResultMessage(toolCallId: String, content: String): JsonObject =
        LlmPrompt.buildToolResultMessage(toolCallId, content)

    companion object {
        fun create(): LlmClient = when (BuildConfig.LLM_PROVIDER.trim().lowercase()) {
            "gemini_native" -> GeminiNativeClient()
            else -> OpenAiChatClient()
        }
    }
}
