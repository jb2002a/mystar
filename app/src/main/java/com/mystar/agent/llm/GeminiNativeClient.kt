package com.mystar.agent.llm

import android.util.Log
import com.mystar.agent.BuildConfig
import com.mystar.agent.tool.ToolCall
import com.mystar.agent.tool.ToolDefinition
import com.mystar.agent.tool.ToolRegistry
import com.mystar.agent.tracing.LangSmithClient
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
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

class GeminiNativeClient(
    private val apiKey: String = BuildConfig.LLM_API_KEY,
    private val baseUrl: String = BuildConfig.LLM_BASE_URL,
    private val model: String = BuildConfig.LLM_MODEL,
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

            val (systemInstruction, contents) = translateMessages(messages)
            val body = buildRequestBody(systemInstruction, contents)
            val bodyText = body.toString()
            val endpoint = buildGenerateContentUrl(baseUrl, model)
            Log.i(LlmHttpSupport.TAG, "Gemini req → $endpoint model=$model contents=${contents.size}")
            LlmHttpSupport.logChunked(LlmHttpSupport.TAG, "Gemini req body", bodyText)

            val llmRunId = tracer.startRun(
                name = "choose_next_tool",
                runType = "llm",
                inputs = buildJsonObject {
                    put("model", model)
                    put("messages", LangSmithClient.sanitizeMessages(messages))
                    put("message_count", messages.size)
                    put("contents", contents)
                    put("llm_provider", "gemini_native")
                },
                parentRunId = parentRunId,
                extra = buildJsonObject {
                    put(
                        "metadata",
                        buildJsonObject {
                            put("ls_provider", "google")
                            put("ls_model_name", model)
                            put("ls_model_type", "chat")
                        },
                    )
                },
            )
            val startedAt = System.currentTimeMillis()

            val request = Request.Builder()
                .url("$endpoint?key=$apiKey")
                .header("Content-Type", "application/json")
                .post(bodyText.toRequestBody(LlmHttpSupport.JSON_MEDIA_TYPE))
                .build()

            try {
                httpClient.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string().orEmpty()
                    val latencyMs = System.currentTimeMillis() - startedAt
                    Log.i(LlmHttpSupport.TAG, "Gemini res ← HTTP ${response.code} (${responseBody.length} chars)")
                    LlmHttpSupport.logChunked(LlmHttpSupport.TAG, "Gemini res body", responseBody)
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
                Log.e(LlmHttpSupport.TAG, "Gemini call failed: ${e.message}", e)
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

    /**
     * OpenAI 형태 messages → Gemini systemInstruction + contents.
     * 규칙만 systemInstruction, 카탈로그/화면은 user text part, model 턴은 원본 에코.
     */
    internal fun translateMessages(messages: List<JsonObject>): Pair<JsonObject?, JsonArray> {
        var systemInstruction: JsonObject? = null
        val contents = mutableListOf<JsonObject>()
        var pendingCatalog: String? = null
        var pendingScreen: String? = null
        var lastFunctionCallName: String? = null
        var lastFunctionCallId: String? = null

        for (msg in messages) {
            val role = msg["role"]?.jsonPrimitive?.contentOrNull.orEmpty()
            when (role) {
                "system" -> {
                    val content = msg["content"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    when {
                        content.contains("<installed_apps>") -> pendingCatalog = content
                        content.contains("<current_screen>") -> pendingScreen = content
                        systemInstruction == null -> {
                            systemInstruction = buildJsonObject {
                                put(
                                    "parts",
                                    buildJsonArray {
                                        add(buildJsonObject { put("text", content) })
                                    },
                                )
                            }
                        }
                    }
                }
                "user" -> {
                    val text = msg["content"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val parts = buildJsonArray {
                        pendingCatalog?.let {
                            add(buildJsonObject { put("text", it) })
                            pendingCatalog = null
                        }
                        add(buildJsonObject { put("text", text) })
                    }
                    contents.add(
                        buildJsonObject {
                            put("role", "user")
                            put("parts", parts)
                        },
                    )
                }
                "assistant", "model" -> {
                    if (msg.containsKey("parts")) {
                        contents.add(
                            buildJsonObject {
                                put("role", "model")
                                put("parts", msg["parts"]!!)
                            },
                        )
                        extractFunctionCallMeta(msg["parts"]!!.jsonArray)?.let { (name, id) ->
                            lastFunctionCallName = name
                            lastFunctionCallId = id
                        }
                    } else {
                        val toolCalls = msg["tool_calls"]?.jsonArray
                        if (toolCalls != null && toolCalls.isNotEmpty()) {
                            val first = toolCalls[0].jsonObject
                            val function = first["function"]?.jsonObject
                            val name = function?.get("name")?.jsonPrimitive?.contentOrNull.orEmpty()
                            val id = first["id"]?.jsonPrimitive?.contentOrNull.orEmpty()
                            val argsRaw = function?.get("arguments")
                            val args = parseOpenAiArguments(argsRaw)
                            lastFunctionCallName = name
                            lastFunctionCallId = id
                            val part = buildJsonObject {
                                put(
                                    "functionCall",
                                    buildJsonObject {
                                        put("name", name)
                                        put("args", args)
                                        if (id.isNotEmpty()) {
                                            put("id", id)
                                        }
                                    },
                                )
                            }
                            contents.add(
                                buildJsonObject {
                                    put("role", "model")
                                    put(
                                        "parts",
                                        buildJsonArray { add(part) },
                                    )
                                },
                            )
                        }
                    }
                }
                "tool" -> {
                    val id = msg["tool_call_id"]?.jsonPrimitive?.contentOrNull
                        ?: lastFunctionCallId.orEmpty()
                    val name = lastFunctionCallName.orEmpty()
                    val resultText = msg["content"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    contents.add(buildFunctionResponseContent(id, name, resultText))
                }
            }
        }

        pendingScreen?.let { screen ->
            contents.add(
                buildJsonObject {
                    put("role", "user")
                    put(
                        "parts",
                        buildJsonArray {
                            add(buildJsonObject { put("text", screen) })
                        },
                    )
                },
            )
        }

        return systemInstruction to JsonArray(contents)
    }

    private fun buildRequestBody(systemInstruction: JsonObject?, contents: JsonArray): JsonObject {
        return buildJsonObject {
            if (systemInstruction != null) {
                put("systemInstruction", systemInstruction)
            }
            put("contents", contents)
            put(
                "tools",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put(
                                "functionDeclarations",
                                functionDeclarationsToJson(tools),
                            )
                        },
                    )
                },
            )
            put(
                "toolConfig",
                buildJsonObject {
                    put(
                        "functionCallingConfig",
                        buildJsonObject {
                            put("mode", "ANY")
                        },
                    )
                },
            )
            // temperature는 보내지 않는다. Google 권장: Gemini 3는 기본값(1.0) 유지,
            // 1.0 미만으로 낮추면 looping·성능 저하가 생길 수 있음.
        }
    }

    private fun functionDeclarationsToJson(defs: List<ToolDefinition>): JsonArray = buildJsonArray {
        for (def in defs) {
            add(
                buildJsonObject {
                    put("name", def.name)
                    put("description", def.description)
                    put("parameters", stripAdditionalProperties(def.parameters))
                },
            )
        }
    }

    private fun stripAdditionalProperties(schema: JsonObject): JsonObject {
        val out = mutableMapOf<String, JsonElement>()
        for ((key, value) in schema) {
            if (key == "additionalProperties") continue
            out[key] = when (value) {
                is JsonObject -> stripAdditionalProperties(value)
                is JsonArray -> {
                    buildJsonArray {
                        for (item in value) {
                            add(
                                if (item is JsonObject) {
                                    stripAdditionalProperties(item)
                                } else {
                                    item
                                },
                            )
                        }
                    }
                }
                else -> value
            }
        }
        return JsonObject(out)
    }

    private fun buildFunctionResponseContent(id: String, name: String, content: String): JsonObject {
        return buildJsonObject {
            put("role", "user")
            put(
                "parts",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put(
                                "functionResponse",
                                buildJsonObject {
                                    if (id.isNotEmpty()) {
                                        put("id", id)
                                    }
                                    put("name", name)
                                    put(
                                        "response",
                                        buildJsonObject {
                                            put("content", content)
                                        },
                                    )
                                },
                            )
                        },
                    )
                },
            )
        }
    }

    private fun extractFunctionCallMeta(parts: JsonArray): Pair<String, String>? {
        for (part in parts) {
            val fc = part.jsonObject["functionCall"]?.jsonObject ?: continue
            val name = fc["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val id = fc["id"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (name.isNotEmpty()) {
                return name to id
            }
        }
        return null
    }

    private fun parseOpenAiArguments(argsRaw: JsonElement?): JsonObject {
        return when (argsRaw) {
            null -> JsonObject(emptyMap())
            is JsonObject -> argsRaw
            is JsonPrimitive -> {
                val text = argsRaw.content
                if (text.isBlank()) {
                    JsonObject(emptyMap())
                } else {
                    try {
                        json.parseToJsonElement(text).jsonObject
                    } catch (_: Exception) {
                        JsonObject(emptyMap())
                    }
                }
            }
            else -> JsonObject(emptyMap())
        }
    }

    private fun parseUsage(responseBody: String): JsonObject? {
        val root = try {
            json.parseToJsonElement(responseBody).jsonObject
        } catch (_: Exception) {
            return null
        }
        val usage = root["usageMetadata"]?.jsonObject ?: return null
        val inputTokens = usage["promptTokenCount"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
        val outputTokens = usage["candidatesTokenCount"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
        if (inputTokens == null && outputTokens == null) return null
        val totalTokens = usage["totalTokenCount"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
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

        val candidates = root["candidates"]?.jsonArray
            ?: return LlmResult.Failure("응답에 candidates 없음")
        if (candidates.isEmpty()) {
            return LlmResult.Failure("candidates가 비어 있음")
        }

        val content = candidates[0].jsonObject["content"]?.jsonObject
            ?: return LlmResult.Failure("content 없음")
        val parts = content["parts"]?.jsonArray
            ?: return LlmResult.Failure("parts 없음")

        for (part in parts) {
            val fc = part.jsonObject["functionCall"]?.jsonObject ?: continue
            val name = fc["name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (name.isEmpty()) {
                return LlmResult.Failure("functionCall.name 없음")
            }
            val args = fc["args"]?.jsonObject ?: JsonObject(emptyMap())
            val id = fc["id"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()

            // 원본 part(functionCall + thoughtSignature)를 그대로 히스토리에 보존.
            val assistantMessage = buildJsonObject {
                put("role", "model")
                put(
                    "parts",
                    buildJsonArray {
                        add(part)
                    },
                )
            }

            return LlmResult.Success(
                toolCall = ToolCall(name = name, args = args, id = id),
                assistantMessage = assistantMessage,
            )
        }

        return LlmResult.Failure("functionCall 없음")
    }

    companion object {
        fun buildGenerateContentUrl(baseUrl: String, model: String): String {
            val trimmed = baseUrl.trimEnd('/')
            val modelPath = "models/$model:generateContent"
            return if (trimmed.endsWith(":generateContent")) {
                trimmed
            } else if (trimmed.endsWith("/$modelPath")) {
                trimmed
            } else {
                "$trimmed/$modelPath"
            }
        }
    }
}
