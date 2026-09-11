package com.mystar.agent

import android.content.Context
import android.os.Build
import android.util.Log
import com.mystar.agent.agent.ReactAgent
import com.mystar.agent.llm.CloudLlmClient
import java.io.File
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class EvalToolEntry(
    val round: Int,
    val name: String,
    val reason: String?,
    /** LLM이 보낸 원본 인자 (마스킹 없음). */
    val args: JsonObject,
    val ok: Boolean,
    /** LLM에 돌려준 tool 메시지 원문 (자르지 않음). */
    val result: String,
    /** 이 도구를 고를 때 LLM이 본 화면 트리. 1라운드는 초기 트리 주입이 꺼져 있어 null. */
    val screen: String?,
    /** 행동 후 안정화 결과. 화면을 갱신하지 않은 도구(web_search, finish)는 null. */
    val settle: String?,
    val tokensIn: Int?,
    val tokensOut: Int?,
    val tokensTotal: Int?,
    val llmMs: Long,
    val toolMs: Long,
)

/** 평가 큐가 붙이는 실행 식별자. 단발 실행이면 null. */
data class EvalTag(
    val queueId: String,
    val taskIndex: Int,
    val attempt: Int,
    /** 런 직전 앱 정리 결과: closed_all / home_only / recents_failed */
    val precondition: String,
)

data class EvalRunRecord(
    val task: String,
    val elapsedS: Double,
    val rounds: Int,
    val tokensIn: Int,
    val tokensOut: Int,
    val tokensTotal: Int,
    val costUsd: Double?,
    val endReason: String,
    val endError: String?,
    val finished: Boolean,
    val finishSummary: String?,
    val hitlCount: Int,
    val tools: List<EvalToolEntry>,
    /** 종료 시점에 새로 읽은 화면 트리. */
    val finalScreen: String?,
    val finalPackage: String?,
    val tag: EvalTag? = null,
)

object EvalRunStore {
    private const val TAG = "AgentA11y"
    private const val EVAL_DIR = "eval"
    private const val TASK_SLUG_MAX = 40

    /** 모델별 단가 (USD per 1M tokens, 입력 to 출력). 출력 단가는 thinking 토큰 포함. */
    private val PRICES_PER_M = mapOf(
        "gemini-3-flash-preview" to (0.50 to 3.00),
        "gpt-4o" to (2.50 to 10.00),
    )

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
    }

    /**
     * 단가를 모르는 모델이면 null (추측하지 않는다).
     * total이 input+output보다 크면 그 차이(thinking 토큰)도 출력 단가로 계산한다.
     */
    fun estimateCostUsd(model: String, tokensIn: Int, tokensOut: Int, tokensTotal: Int): Double? {
        val (inputPrice, outputPrice) = PRICES_PER_M[model] ?: return null
        val billedOut = maxOf(tokensOut, tokensTotal - tokensIn)
        val cost = (tokensIn * inputPrice + billedOut * outputPrice) / 1_000_000.0
        return (cost * 10_000).toLong() / 10_000.0
    }

    suspend fun save(context: Context, record: EvalRunRecord): String? = withContext(Dispatchers.IO) {
        try {
            val dir = File(context.filesDir, EVAL_DIR)
            if (!dir.exists() && !dir.mkdirs()) {
                Log.w(TAG, "EvalRunStore: eval 디렉터리 생성 실패")
                return@withContext null
            }
            val now = Date()
            val tsForFile = fileTimestampFormat().format(now)
            val tsForJson = jsonTimestampFormat().format(now)
            val slug = sanitizeTaskSlug(record.task)
            val tagPart = record.tag
                ?.let { "_t${it.taskIndex.toString().padStart(2, '0')}a${it.attempt}" }
                .orEmpty()
            val fileName = "$tsForFile${tagPart}_$slug.json"
            val file = File(dir, fileName)
            val body = buildJsonObject {
                put("ts", tsForJson)
                put("queue_id", record.tag?.queueId?.let { JsonPrimitive(it) } ?: JsonNull)
                put("task_index", record.tag?.taskIndex?.let { JsonPrimitive(it) } ?: JsonNull)
                put("attempt", record.tag?.attempt?.let { JsonPrimitive(it) } ?: JsonNull)
                put("run_config", buildRunConfig(record.tag))
                put("task", record.task)
                put("elapsed_s", JsonPrimitive(record.elapsedS))
                put("rounds", record.rounds)
                put("tokens_in", record.tokensIn)
                put("tokens_out", record.tokensOut)
                put("tokens_total", record.tokensTotal)
                put("cost_usd", record.costUsd?.let { JsonPrimitive(it) } ?: JsonNull)
                put("end_reason", record.endReason)
                put("end_error", record.endError?.let { JsonPrimitive(it) } ?: JsonNull)
                put("finished", record.finished)
                put(
                    "finish_summary",
                    record.finishSummary?.let { JsonPrimitive(it) } ?: JsonNull,
                )
                put("hitl_count", record.hitlCount)
                put("final_package", record.finalPackage?.let { JsonPrimitive(it) } ?: JsonNull)
                put("final_screen", record.finalScreen?.let { JsonPrimitive(it) } ?: JsonNull)
                put(
                    "tools",
                    buildJsonArray {
                        for (tool in record.tools) {
                            add(
                                buildJsonObject {
                                    put("round", tool.round)
                                    put("name", tool.name)
                                    put(
                                        "reason",
                                        tool.reason?.let { JsonPrimitive(it) } ?: JsonNull,
                                    )
                                    put("args", tool.args)
                                    put("ok", tool.ok)
                                    put("result", tool.result)
                                    put("settle", tool.settle?.let { JsonPrimitive(it) } ?: JsonNull)
                                    put("tokens_in", tool.tokensIn?.let { JsonPrimitive(it) } ?: JsonNull)
                                    put("tokens_out", tool.tokensOut?.let { JsonPrimitive(it) } ?: JsonNull)
                                    put(
                                        "tokens_total",
                                        tool.tokensTotal?.let { JsonPrimitive(it) } ?: JsonNull,
                                    )
                                    put("llm_ms", tool.llmMs)
                                    put("tool_ms", tool.toolMs)
                                    put("screen", tool.screen?.let { JsonPrimitive(it) } ?: JsonNull)
                                },
                            )
                        }
                    },
                )
            }
            file.writeText(json.encodeToString(JsonElement.serializer(), body))
            fileName
        } catch (e: Exception) {
            Log.w(TAG, "EvalRunStore: 저장 실패 — ${e.message}")
            null
        }
    }

    /** 회차 간 비교용 실행 조건. */
    private fun buildRunConfig(tag: EvalTag?): JsonObject = buildJsonObject {
        put("model", BuildConfig.LLM_MODEL)
        put(
            "reasoning_effort",
            BuildConfig.LLM_REASONING_EFFORT.ifBlank { null }?.let { JsonPrimitive(it) } ?: JsonNull,
        )
        put("llm_host", llmHost()?.let { JsonPrimitive(it) } ?: JsonNull)
        put("temperature", CloudLlmClient.TEMPERATURE)
        put("max_rounds", ReactAgent.MAX_ROUNDS)
        put("system_prompt_sha", CloudLlmClient.systemPromptSha)
        put("app_version", BuildConfig.VERSION_NAME)
        put("app_version_code", BuildConfig.VERSION_CODE)
        put("device", Build.MODEL)
        put("sdk_int", Build.VERSION.SDK_INT)
        put("precondition", tag?.precondition?.let { JsonPrimitive(it) } ?: JsonNull)
    }

    private fun llmHost(): String? {
        return try {
            URI(BuildConfig.LLM_BASE_URL.trim()).host
        } catch (_: Exception) {
            null
        }
    }

    private fun sanitizeTaskSlug(task: String): String {
        val slug = task
            .trim()
            .replace(Regex("""[\\/:*?"<>|\s]+"""), "_")
            .trim('_')
        val trimmed = if (slug.length <= TASK_SLUG_MAX) {
            slug
        } else {
            slug.take(TASK_SLUG_MAX).trimEnd('_')
        }
        return trimmed.ifEmpty { "task" }
    }

    private fun fileTimestampFormat(): SimpleDateFormat {
        return SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.US).apply {
            timeZone = TimeZone.getDefault()
        }
    }

    private fun jsonTimestampFormat(): SimpleDateFormat {
        return SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).apply {
            timeZone = TimeZone.getDefault()
        }
    }
}
