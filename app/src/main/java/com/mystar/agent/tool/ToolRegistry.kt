package com.mystar.agent.tool

import android.content.Intent
import com.mystar.agent.AgentAccessibilityService
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

object ToolRegistry {

    /** LLM에 노출하는 공개 도구 (M4: get_screen_info 제외). */
    val definitions: List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "open_app",
            description = "패키지명으로 앱을 실행한다. 예: com.android.settings",
            parameters = objectSchema(
                "package" to stringProp("실행할 앱의 패키지명"),
                required = listOf("package"),
            ),
        ),
        ToolDefinition(
            name = "tap_node",
            description = "최신 화면 트리에 있는 node id를 탭한다. 예: n3",
            parameters = objectSchema(
                "node_id" to stringProp("탭할 node id (예: n3)"),
                required = listOf("node_id"),
            ),
        ),
        ToolDefinition(
            name = "input_text",
            description = "node id로 입력 필드를 포커스한 뒤 텍스트를 넣는다.",
            parameters = objectSchema(
                "text" to stringProp("입력할 텍스트"),
                "node_id" to stringProp("입력 필드의 node id"),
                required = listOf("text", "node_id"),
            ),
        ),
        ToolDefinition(
            name = "finish",
            description = "목표가 완료되었음을 선언하고 작업을 종료한다.",
            parameters = objectSchema(
                "summary" to stringProp("완료 요약 (선택)"),
                required = emptyList(),
            ),
        ),
    )

    private val byName: Map<String, ToolDefinition> = definitions.associateBy { it.name }

    fun execute(call: ToolCall): ToolResult {
        if (byName[call.name] == null) {
            return ToolResult(false, "알 수 없는 도구: ${call.name}")
        }
        return when (call.name) {
            "open_app" -> executeOpenApp(call.args)
            "tap_node" -> executeTapNode(call.args)
            "input_text" -> executeInputText(call.args)
            "finish" -> executeFinish(call.args)
            else -> ToolResult(false, "알 수 없는 도구: ${call.name}")
        }
    }

    private fun executeOpenApp(args: JsonObject): ToolResult {
        val packageName = requireString(args, "package")
            ?: return ToolResult(false, "package 인자 필요")
        val service = AgentAccessibilityService.instance
            ?: return ToolResult(false, "접근성 서비스 미연결")
        return try {
            val intent = service.packageManager.getLaunchIntentForPackage(packageName)
                ?: return ToolResult(false, "앱을 찾을 수 없음: $packageName")
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            service.startActivity(intent)
            ToolResult(true, "앱 실행: $packageName")
        } catch (e: Exception) {
            ToolResult(false, "앱 실행 실패: ${e.message}")
        }
    }

    private fun executeTapNode(args: JsonObject): ToolResult {
        val nodeId = requireString(args, "node_id")
            ?: return ToolResult(false, "node_id 인자 필요")
        val service = AgentAccessibilityService.instance
            ?: return ToolResult(false, "접근성 서비스 미연결")
        val ok = service.tapNode(nodeId)
        return if (ok) {
            ToolResult(true, "tap_node($nodeId) OK")
        } else {
            ToolResult(false, "tap_node($nodeId) 실패 (존재하지 않는 id 또는 제스처 실패)")
        }
    }

    private fun executeInputText(args: JsonObject): ToolResult {
        val text = requireString(args, "text")
            ?: return ToolResult(false, "text 인자 필요")
        val nodeId = requireString(args, "node_id")
            ?: return ToolResult(false, "node_id 인자 필요")
        val service = AgentAccessibilityService.instance
            ?: return ToolResult(false, "접근성 서비스 미연결")
        val ok = service.inputText(text, nodeId)
        return if (ok) {
            ToolResult(true, "input_text($nodeId) OK")
        } else {
            ToolResult(false, "input_text($nodeId) 실패")
        }
    }

    private fun executeFinish(args: JsonObject): ToolResult {
        val summary = args["summary"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val message = if (summary.isEmpty()) "finish" else "finish: $summary"
        return ToolResult(true, message)
    }

    private fun requireString(args: JsonObject, key: String): String? {
        val value = args[key]?.jsonPrimitive?.contentOrNull?.trim()
        return value?.takeIf { it.isNotEmpty() }
    }

    private fun objectSchema(
        vararg properties: Pair<String, JsonObject>,
        required: List<String>,
    ): JsonObject = buildJsonObject {
        put("type", "object")
        put(
            "properties",
            buildJsonObject {
                for ((name, schema) in properties) {
                    put(name, schema)
                }
            },
        )
        if (required.isNotEmpty()) {
            put(
                "required",
                kotlinx.serialization.json.JsonArray(required.map { JsonPrimitive(it) }),
            )
        }
        put("additionalProperties", false)
    }

    private fun stringProp(description: String): JsonObject = buildJsonObject {
        put("type", "string")
        put("description", description)
    }
}
