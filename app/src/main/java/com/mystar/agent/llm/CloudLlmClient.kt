package com.mystar.agent.llm

import android.util.Log
import com.mystar.agent.BuildConfig
import com.mystar.agent.tool.ToolCall
import com.mystar.agent.tool.ToolDefinition
import com.mystar.agent.tool.ToolRegistry
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

sealed class LlmResult {
    data class Success(val toolCall: ToolCall) : LlmResult()
    data class Failure(val message: String) : LlmResult()
}

class CloudLlmClient(
    private val apiKey: String = BuildConfig.LLM_API_KEY,
    private val baseUrl: String = BuildConfig.LLM_BASE_URL,
    private val model: String = BuildConfig.LLM_MODEL,
    private val tools: List<ToolDefinition> = ToolRegistry.definitions,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(75, TimeUnit.SECONDS)
        .build()

    fun configErrorOrNull(): String? {
        return when {
            apiKey.isBlank() -> "LLM_API_KEY 미설정 (local.properties)"
            baseUrl.isBlank() -> "LLM_BASE_URL 미설정 (local.properties)"
            model.isBlank() -> "LLM_MODEL 미설정 (local.properties)"
            else -> null
        }
    }

    suspend fun chooseTool(goal: String, screenTree: String): LlmResult =
        withContext(Dispatchers.IO) {
            val configError = configErrorOrNull()
            if (configError != null) {
                return@withContext LlmResult.Failure(configError)
            }

            val endpoint = buildChatCompletionsUrl(baseUrl)
            val body = buildRequestBody(goal, screenTree)
            val bodyText = body.toString()
            Log.i(TAG, "LLM req → $endpoint model=$model")
            logChunked(TAG, "LLM req body", bodyText)

            val request = Request.Builder()
                .url(endpoint)
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .post(bodyText.toRequestBody(JSON_MEDIA_TYPE))
                .build()

            try {
                httpClient.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string().orEmpty()
                    Log.i(TAG, "LLM res ← HTTP ${response.code} (${responseBody.length} chars)")
                    logChunked(TAG, "LLM res body", responseBody)
                    if (!response.isSuccessful) {
                        val snippet = responseBody.take(200).replace('\n', ' ')
                        return@withContext LlmResult.Failure(
                            "HTTP ${response.code}: $snippet",
                        )
                    }
                    parseToolCall(responseBody)
                }
            } catch (e: Exception) {
                Log.e(TAG, "LLM call failed: ${e.message}", e)
                LlmResult.Failure("네트워크/호출 실패: ${e.message}")
            }
        }

    private fun buildRequestBody(goal: String, screenTree: String): JsonObject {
        return buildJsonObject {
            put("model", model)
            put(
                "messages",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("role", "system")
                            put("content", SYSTEM_PROMPT)
                        },
                    )
                    add(
                        buildJsonObject {
                            put("role", "user")
                            put(
                                "content",
                                buildString {
                                    appendLine("목표: $goal")
                                    appendLine()
                                    appendLine("현재 화면 트리 (최신 스냅샷):")
                                    append(screenTree)
                                },
                            )
                        },
                    )
                },
            )
            put("tools", toolsToJson(tools))
            put("tool_choice", "required")
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

        return LlmResult.Success(ToolCall(name = name, args = args))
    }

    companion object {
        private const val TAG = "AgentA11y"
        private const val LOG_CHUNK = 3500
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        private val SYSTEM_PROMPT = """
당신은 Android 접근성 트리 기반 에이전트다.
제공된 화면 트리는 방금 캡처한 최신 스냅샷이다.
반드시 도구를 정확히 한 번만 호출한다.
node id는 트리에 있는 값만 사용하고, 새로 만들지 않는다.
이 단발 테스트에서는 목표를 달성하기 위한 다음 행동 하나만 고른다.
이미 트리가 주어졌으므로 get_screen_info를 다시 부르지 않는다.
""".trimIndent()

        fun buildChatCompletionsUrl(baseUrl: String): String {
            val trimmed = baseUrl.trimEnd('/')
            return if (trimmed.endsWith("/chat/completions")) {
                trimmed
            } else {
                "$trimmed/chat/completions"
            }
        }

        /** Logcat 한도(~4KB)를 넘지 않도록 본문을 나눠 출력. Authorization/API 키는 절대 포함하지 말 것. */
        private fun logChunked(tag: String, label: String, text: String) {
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
    }
}
