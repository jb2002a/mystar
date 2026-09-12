package com.mystar.agent.llm

import android.util.Log
import com.mystar.agent.BuildConfig
import com.mystar.agent.tool.ToolCall
import com.mystar.agent.tool.ToolDefinition
import com.mystar.agent.tool.ToolRegistry
import com.mystar.agent.tracing.LangSmithClient
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class OpenAiChatClient(
    private val apiKey: String = BuildConfig.LLM_API_KEY,
    private val baseUrl: String = BuildConfig.LLM_BASE_URL,
    private val model: String = BuildConfig.LLM_MODEL,
    private val reasoningEffort: String = BuildConfig.LLM_REASONING_EFFORT,
    private val tools: List<ToolDefinition> = ToolRegistry.definitions,
    private val tracer: LangSmithClient = LangSmithClient.shared,
) : LlmClient {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(75, TimeUnit.SECONDS)
        .build()

    override fun configErrorOrNull(): String? {
        return when {
            apiKey.isBlank() -> "LLM_API_KEY 미설정 (local.properties)"
            baseUrl.isBlank() -> "LLM_BASE_URL 미설정 (local.properties)"
            model.isBlank() -> "LLM_MODEL 미설정 (local.properties)"
            else -> null
        }
    }

    override suspend fun chooseNextTool(
        messages: List<JsonObject>,
        parentRunId: String?,
    ): LlmResult =
        withContext(Dispatchers.IO) {
            val configError = configErrorOrNull()
            if (configError != null) {
                return@withContext LlmResult.Failure(configError)
            }

            val endpoint = buildChatCompletionsUrl(baseUrl)
            val body = buildRequestBody(messages)
            val bodyText = body.toString()
            Log.i(LlmHttpSupport.TAG, "LLM req → $endpoint model=$model messages=${messages.size}")
            LlmHttpSupport.logChunked(LlmHttpSupport.TAG, "LLM req body", bodyText)

            val llmRunId = tracer.startRun(
                name = "choose_next_tool",
                runType = "llm",
                inputs = buildJsonObject {
                    put("model", model)
                    put("messages", LangSmithClient.sanitizeMessages(messages))
                    put("message_count", messages.size)
                    put("tools", toolsToJson(tools))
                    put("tool_choice", "required")
                    put("llm_provider", "openai_compat")
                },
                parentRunId = parentRunId,
                extra = buildJsonObject {
                    put(
                        "metadata",
                        buildJsonObject {
                            put("ls_provider", langSmithProvider())
                            put("ls_model_name", model)
                            put("ls_model_type", "chat")
                        },
                    )
                },
            )
            val startedAt = System.currentTimeMillis()

            val request = Request.Builder()
                .url(endpoint)
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .post(bodyText.toRequestBody(LlmHttpSupport.JSON_MEDIA_TYPE))
                .build()

            try {
                httpClient.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string().orEmpty()
                    val latencyMs = System.currentTimeMillis() - startedAt
                    Log.i(LlmHttpSupport.TAG, "LLM res ← HTTP ${response.code} (${responseBody.length} chars)")
                    LlmHttpSupport.logChunked(LlmHttpSupport.TAG, "LLM res body", responseBody)
                    val usageMetadata = parseUsage(responseBody)
                    val (inputTokens, outputTokens) = LlmHttpSupport.tokensFromUsage(usageMetadata)
                    val totalTokens = usageMetadata?.get("total_tokens")?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                    if (!response.isSuccessful) {
                        val snippet = responseBody.take(200).replace('\n', ' ')
                        val err = "HTTP ${response.code}: $snippet"
                        tracer.endRun(
                            llmRunId,
                            outputs = LlmHttpSupport.buildLlmRunOutputs(
                                model = model,
                                latencyMs = latencyMs,
                                error = err,
                                usageMetadata = usageMetadata,
                            ),
                            error = err,
                        )
                        return@withContext LlmResult.Failure(
                            message = err,
                            inputTokens = inputTokens,
                            outputTokens = outputTokens,
                            totalTokens = totalTokens,
                        )
                    }
                    val parsed = LlmHttpSupport.attachUsage(
                        parseToolCall(responseBody),
                        inputTokens,
                        outputTokens,
                        totalTokens,
                    )
                    when (parsed) {
                        is LlmResult.Success -> {
                            tracer.endRun(
                                llmRunId,
                                outputs = LlmHttpSupport.buildLlmRunOutputs(
                                    model = model,
                                    latencyMs = latencyMs,
                                    toolCall = parsed.toolCall,
                                    usageMetadata = usageMetadata,
                                ),
                            )
                        }
                        is LlmResult.Failure -> {
                            tracer.endRun(
                                llmRunId,
                                outputs = LlmHttpSupport.buildLlmRunOutputs(
                                    model = model,
                                    latencyMs = latencyMs,
                                    error = parsed.message,
                                    usageMetadata = usageMetadata,
                                ),
                                error = parsed.message,
                            )
                        }
                    }
                    parsed
                }
            } catch (e: Exception) {
                val latencyMs = System.currentTimeMillis() - startedAt
                Log.e(LlmHttpSupport.TAG, "LLM call failed: ${e.message}", e)
                val err = "네트워크/호출 실패: ${e.message}"
                tracer.endRun(
                    llmRunId,
                    outputs = buildJsonObject {
                        put("error", err)
                        put("latency_ms", latencyMs)
                    },
                    error = err,
                )
                LlmResult.Failure(err)
            }
        }

    private fun buildRequestBody(messages: List<JsonObject>): JsonObject {
        return buildJsonObject {
            put("model", model)
            put(
                "messages",
                buildJsonArray {
                    for (msg in messages) {
                        add(msg)
                    }
                },
            )
            put("tools", toolsToJson(tools))
            put("tool_choice", "required")
            put("temperature", LlmPrompt.TEMPERATURE)
            if (reasoningEffort.isNotBlank()) {
                put("reasoning_effort", reasoningEffort)
            }
        }
    }

    private fun toolsToJson(defs: List<ToolDefinition>): JsonArray = buildJsonArray {
        for (def in defs) {
            add(
                buildJsonObject {
                    put("type", "function")
                    put(
                        "function",
                        buildJsonObject {
                            put("name", def.name)
                            put("description", def.description)
                            put("parameters", def.parameters)
                        },
                    )
                },
            )
        }
    }

    private fun langSmithProvider(): String {
        val host = try {
            URI(baseUrl.trim()).host?.lowercase().orEmpty()
        } catch (_: Exception) {
            baseUrl.lowercase()
        }
        return when {
            host.contains("anthropic") -> "anthropic"
            host.contains("openai") -> "openai"
            else -> "openai"
        }
    }

    private fun parseUsage(responseBody: String): JsonObject? {
        val root = try {
            json.parseToJsonElement(responseBody).jsonObject
        } catch (_: Exception) {
            return null
        }
        val usage = root["usage"]?.jsonObject ?: return null
        val inputTokens = usage["prompt_tokens"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
            ?: usage["input_tokens"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
        val outputTokens = usage["completion_tokens"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
            ?: usage["output_tokens"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
        if (inputTokens == null && outputTokens == null) return null
        val totalTokens = usage["total_tokens"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
            ?: listOfNotNull(inputTokens, outputTokens).takeIf { it.isNotEmpty() }?.sum()
        return buildJsonObject {
            inputTokens?.let { put("input_tokens", it) }
            outputTokens?.let { put("output_tokens", it) }
            totalTokens?.let { put("total_tokens", it) }
        }
    }

    private fun parseToolCall(responseBody: String): LlmResult {
        val root = try {
            json.parseToJsonElement(responseBody).jsonObject
        } catch (e: Exception) {
            return LlmResult.Failure("응답 JSON 파싱 실패: ${e.message}")
        }

        val choices = root["choices"]?.jsonArray
            ?: return LlmResult.Failure("응답에 choices 없음")
        if (choices.isEmpty()) {
            return LlmResult.Failure("choices가 비어 있음")
        }

        val message = choices[0].jsonObject["message"]?.jsonObject
            ?: return LlmResult.Failure("message 없음")
        val toolCalls = message["tool_calls"]?.jsonArray
        if (toolCalls == null || toolCalls.isEmpty()) {
            val text = message["content"]?.jsonPrimitive?.contentOrNull?.take(120)
            return LlmResult.Failure(
                if (text.isNullOrBlank()) {
                    "tool_calls 없음"
                } else {
                    "tool_calls 없음 (텍스트: $text)"
                },
            )
        }

        val first = toolCalls[0].jsonObject
        val id = first["id"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (id.isEmpty()) {
            return LlmResult.Failure("tool_calls[0].id 없음")
        }

        val function = first["function"]?.jsonObject
            ?: return LlmResult.Failure("tool_calls[0].function 없음")
        val name = function["name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (name.isEmpty()) {
            return LlmResult.Failure("도구 이름이 비어 있음")
        }

        val argsRaw = function["arguments"]
        val args = when (argsRaw) {
            null -> JsonObject(emptyMap())
            is JsonObject -> argsRaw
            is JsonPrimitive -> {
                val text = argsRaw.content
                if (text.isBlank()) {
                    JsonObject(emptyMap())
                } else {
                    try {
                        json.parseToJsonElement(text).jsonObject
                    } catch (e: Exception) {
                        return LlmResult.Failure("arguments JSON 파싱 실패: ${e.message}")
                    }
                }
            }
            else -> return LlmResult.Failure("arguments 형식 오류")
        }

        val assistantMessage = buildJsonObject {
            put("role", "assistant")
            val content = message["content"]
            if (content != null && content !is JsonNull) {
                put("content", content)
            } else {
                put("content", JsonNull)
            }
            put(
                "tool_calls",
                buildJsonArray {
                    add(first)
                },
            )
        }

        return LlmResult.Success(
            toolCall = ToolCall(name = name, args = args, id = id),
            assistantMessage = assistantMessage,
        )
    }

    companion object {
        fun buildChatCompletionsUrl(baseUrl: String): String {
            val trimmed = baseUrl.trimEnd('/')
            return if (trimmed.endsWith("/chat/completions")) {
                trimmed
            } else {
                "$trimmed/chat/completions"
            }
        }
    }
}
