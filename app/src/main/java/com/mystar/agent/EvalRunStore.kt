package com.mystar.agent

import android.content.Context
import android.util.Log
import java.io.File
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
    val args: JsonObject,
    val ok: Boolean,
    val result: String,
)

/** 평가 큐가 붙이는 실행 식별자. 단발 실행이면 null. */
data class EvalTag(
    val queueId: String,
    val taskIndex: Int,
    val attempt: Int,
)

data class EvalRunRecord(
    val task: String,
    val elapsedS: Double,
    val rounds: Int,
    val tokensIn: Int,
    val tokensOut: Int,
    val costUsd: Double,
    val endReason: String,
    val finished: Boolean,
    val finishSummary: String?,
    val hitlCount: Int,
    val tools: List<EvalToolEntry>,
    val tag: EvalTag? = null,
)

object EvalRunStore {
    private const val TAG = "AgentA11y"
    private const val EVAL_DIR = "eval"
    private const val TASK_SLUG_MAX = 40
    private const val RESULT_MAX = 200

    /** gpt-4o per 1M tokens (USD). */
    private const val INPUT_COST_PER_M = 2.50
    private const val OUTPUT_COST_PER_M = 10.00

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
    }

    fun estimateCostUsd(tokensIn: Int, tokensOut: Int): Double {
        val cost = (tokensIn * INPUT_COST_PER_M + tokensOut * OUTPUT_COST_PER_M) / 1_000_000.0
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
                put("task", record.task)
                put("elapsed_s", JsonPrimitive(record.elapsedS))
                put("rounds", record.rounds)
                put("tokens_in", record.tokensIn)
                put("tokens_out", record.tokensOut)
                put("cost_usd", JsonPrimitive(record.costUsd))
                put("end_reason", record.endReason)
                put("finished", record.finished)
                put(
                    "finish_summary",
                    record.finishSummary?.let { JsonPrimitive(it) } ?: JsonNull,
                )
                put("hitl_count", record.hitlCount)
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

    fun compactResult(message: String): String {
        val first = message.lineSequence().firstOrNull().orEmpty()
        return if (first.length <= RESULT_MAX) {
            first
        } else {
            first.take(RESULT_MAX) + "…"
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
