package com.mystar.agent.tracing

import android.util.Log
import com.mystar.agent.BuildConfig
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * 테스트용 LangSmith Run API 클라이언트.
 * 설정이 비어 있으면 no-op. HTTP 실패는 경고만 남기고 호출자에게 예외를 전파하지 않는다.
 */
class LangSmithClient(
    private val apiKey: String = BuildConfig.LANGSMITH_API_KEY,
    private val projectName: String = BuildConfig.LANGSMITH_PROJECT,
    private val endpoint: String = BuildConfig.LANGSMITH_ENDPOINT
        .ifBlank { DEFAULT_ENDPOINT },
) {
    val enabled: Boolean
        get() = apiKey.isNotBlank() && projectName.isNotBlank()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    suspend fun startRun(
        name: String,
        runType: String,
        inputs: JsonObject,
        parentRunId: String? = null,
    ): String? {
        if (!enabled) return null
        val runId = UUID.randomUUID().toString()
        val body = buildJsonObject {
            put("id", runId)
            put("name", name)
            put("run_type", runType)
            put("inputs", inputs)
            put("start_time", utcNow())
            put("session_name", projectName)
            if (parentRunId != null) {
                put("parent_run_id", parentRunId)
            }
        }
        postRun(body)
        return runId
    }

    suspend fun endRun(
        runId: String?,
        outputs: JsonObject,
        error: String? = null,
    ) {
        if (!enabled || runId.isNullOrBlank()) return
        val body = buildJsonObject {
            put("outputs", outputs)
            put("end_time", utcNow())
            if (error != null) {
                put("error", error)
            }
        }
        patchRun(runId, body)
    }

    private suspend fun postRun(body: JsonObject) {
        withContext(Dispatchers.IO) {
            try {
                val url = "${endpoint.trimEnd('/')}/runs"
                val request = Request.Builder()
                    .url(url)
                    .header("x-api-key", apiKey)
                    .header("Content-Type", "application/json")
                    .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
                    .build()
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        val snippet = response.body?.string().orEmpty().take(160).replace('\n', ' ')
                        Log.w(TAG, "LangSmith POST /runs HTTP ${response.code}: $snippet")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "LangSmith POST /runs failed: ${e.message}")
            }
        }
    }

    private suspend fun patchRun(runId: String, body: JsonObject) {
        withContext(Dispatchers.IO) {
            try {
                val url = "${endpoint.trimEnd('/')}/runs/$runId"
                val request = Request.Builder()
                    .url(url)
                    .header("x-api-key", apiKey)
                    .header("Content-Type", "application/json")
                    .patch(body.toString().toRequestBody(JSON_MEDIA_TYPE))
                    .build()
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        val snippet = response.body?.string().orEmpty().take(160).replace('\n', ' ')
                        Log.w(TAG, "LangSmith PATCH /runs HTTP ${response.code}: $snippet")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "LangSmith PATCH /runs failed: ${e.message}")
            }
        }
    }

    companion object {
        private const val TAG = "AgentA11y"
        private const val DEFAULT_ENDPOINT = "https://api.smith.langchain.com"
        private const val MAX_TREE_CHARS = 2000
        private const val MAX_CONTENT_CHARS = 400
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        val shared = LangSmithClient()

        private fun utcNow(): String {
            val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            fmt.timeZone = TimeZone.getTimeZone("UTC")
            return fmt.format(Date())
        }

        fun redactGoal(goal: String): String {
            if (goal.length <= MAX_CONTENT_CHARS) return goal
            return goal.take(MAX_CONTENT_CHARS) + "…[truncated]"
        }

        fun truncateTree(tree: String): String {
            if (tree.length <= MAX_TREE_CHARS) return tree
            return tree.take(MAX_TREE_CHARS) + "\n…[truncated ${tree.length - MAX_TREE_CHARS} chars]"
        }

        /** tool args: input_text.text 마스킹. */
        fun sanitizeToolArgs(toolName: String, args: JsonObject): JsonObject {
            if (toolName != "input_text") return args
            return buildJsonObject {
                for ((key, value) in args) {
                    if (key == "text") {
                        put(key, JsonPrimitive("[REDACTED]"))
                    } else {
                        put(key, value)
                    }
                }
            }
        }

        /** tool result message: 긴 트리 truncate. */
        fun sanitizeToolResultMessage(message: String): String {
            val marker = "screen tree"
            val idx = message.indexOf(marker)
            if (idx < 0) {
                return if (message.length <= MAX_TREE_CHARS + 200) {
                    message
                } else {
                    message.take(MAX_TREE_CHARS + 200) + "\n…[truncated]"
                }
            }
            val before = message.substring(0, idx)
            val after = message.substring(idx)
            return before + truncateTree(after)
        }

        /**
         * LLM messages 정제: content 내 긴 텍스트/트리를 줄이고,
         * input_text 관련 arguments를 마스킹.
         */
        fun sanitizeMessages(messages: List<JsonObject>): JsonArray = buildJsonArray {
            for (msg in messages) {
                add(sanitizeMessage(msg))
            }
        }

        private fun sanitizeMessage(msg: JsonObject): JsonObject = buildJsonObject {
            for ((key, value) in msg) {
                when (key) {
                    "content" -> put(key, sanitizeContent(value))
                    "tool_calls" -> put(key, sanitizeToolCalls(value))
                    else -> put(key, value)
                }
            }
        }

        private fun sanitizeContent(value: JsonElement): JsonElement {
            if (value is JsonNull) return value
            val text = value.jsonPrimitive.contentOrNull ?: return value
            val redacted = if (text.contains("screen tree") || text.contains("현재 화면 트리")) {
                truncateTree(text)
            } else if (text.length > MAX_CONTENT_CHARS * 2) {
                text.take(MAX_CONTENT_CHARS * 2) + "…[truncated]"
            } else {
                text
            }
            return JsonPrimitive(redacted)
        }

        private fun sanitizeToolCalls(value: JsonElement): JsonElement {
            val arr = value as? JsonArray ?: return value
            return buildJsonArray {
                for (item in arr) {
                    val obj = item as? JsonObject
                    if (obj == null) {
                        add(item)
                    } else {
                        add(
                            buildJsonObject {
                                for ((k, v) in obj) {
                                    if (k == "function") {
                                        put(k, sanitizeFunction(v))
                                    } else {
                                        put(k, v)
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }

        private fun sanitizeFunction(value: JsonElement): JsonElement {
            val fn = value as? JsonObject ?: return value
            val name = fn["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
            return buildJsonObject {
                for ((k, v) in fn) {
                    if (k == "arguments" && name == "input_text") {
                        put(k, JsonPrimitive("""{"node_id":"?","text":"[REDACTED]"}"""))
                    } else {
                        put(k, v)
                    }
                }
            }
        }
    }
}
