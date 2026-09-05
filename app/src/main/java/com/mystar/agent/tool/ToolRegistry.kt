package com.mystar.agent.tool

import android.content.Intent
import com.mystar.agent.AgentAccessibilityService
import com.mystar.agent.search.WebSearchClient
import kotlinx.serialization.json.JsonArray
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
            description = "앱을 실행한다. 다른 앱으로 전환할 때 back 대신 이 도구를 쓴다. package는 시작 시 주입된 앱 목록의 패키지명만 사용한다.",
            parameters = objectSchema(
                "package" to stringProp("실행할 앱의 패키지명 (앱 목록에서 선택)"),
                "reason" to reasonProp(),
                required = listOf("package", "reason"),
            ),
        ),
        ToolDefinition(
            name = "tap_node",
            description = "최신 화면 트리에 있는 node id를 탭한다. 예: n3",
            parameters = objectSchema(
                "node_id" to stringProp("탭할 node id (예: n3)"),
                "reason" to reasonProp(),
                required = listOf("node_id", "reason"),
            ),
        ),
        ToolDefinition(
            name = "input_text",
            description = "node id로 입력 필드를 포커스한 뒤 텍스트를 넣는다. text는 사용자가 말한 문구 그대로. 의역·이모지·마침표 추가 금지.",
            parameters = objectSchema(
                "text" to stringProp("입력할 텍스트"),
                "node_id" to stringProp("입력 필드의 node id"),
                "reason" to reasonProp(),
                required = listOf("text", "node_id", "reason"),
            ),
        ),
        ToolDefinition(
            name = "back",
            description = "시스템 뒤로가기로 현재 앱 화면을 한 단계 되돌린다. 잘못된 하위 화면·다이얼로그에서만. 메인·탭·홈처럼 최상위면 back하지 않는다.",
            parameters = objectSchema(
                "reason" to reasonProp(),
                required = listOf("reason"),
            ),
        ),
        ToolDefinition(
            name = "scroll",
            description = "최신 화면 트리에서 scroll 마크가 있는 node id로 목록을 한 칸 스크롤한다. scroll 후 node id는 전부 새로 발급된다. 같은 scroll 연속 반복 금지.",
            parameters = objectSchema(
                "node_id" to stringProp("scroll 마크가 있는 node id"),
                "direction" to enumProp("스크롤 방향", listOf("up", "down")),
                "reason" to reasonProp(),
                required = listOf("node_id", "direction", "reason"),
            ),
        ),
        ToolDefinition(
            name = "web_search",
            description = "웹 검색 API로 날씨·시세·사실 등 웹 정보를 가져온다. 구글/크롬을 open_app으로 열지 않는다. 실패 시 브라우저를 열지 말고 finish로 알린다. 카톡 친구·설정 항목 등 앱 안 검색에는 쓰지 않는다.",
            parameters = objectSchema(
                "query" to stringProp("검색어"),
                "reason" to reasonProp(),
                required = listOf("query", "reason"),
            ),
        ),
        ToolDefinition(
            name = "ask_user",
            description = "루프를 멈추고 사용자에게 질문한다. 한 호출에 하나만. " +
                "kind=missing_info: 목표에 없는 필수 정보(수신자, 보낼 메시지 내용, 날짜, 도착역 등)를 묻는다(자유 텍스트 답). " +
                "kind=confirm: 전송·결제·구매·가입완료·동의 등 되돌릴 수 없는 탭 직전에 승인/거절을 받는다. " +
                "목록 탭·스크롤·open_app·back에는 쓰지 않는다. 거절 시 tap_node하지 말고 finish로 종료. " +
                "확인 후 실제 탭은 tap_node가 한다. ask_user는 finish가 아니다.",
            parameters = objectSchema(
                "question" to stringProp("사용자에게 읽을 한두 문장 질문. 트리 원문을 넣지 않는다"),
                "kind" to enumProp("질문 종류", listOf("missing_info", "confirm")),
                "reason" to reasonProp(),
                required = listOf("question", "kind", "reason"),
            ),
        ),
        ToolDefinition(
            name = "finish",
            description = "목표가 완료되었음을 선언하고 작업을 종료한다. summary는 사용자에게 읽을 1~2문장 한국어. 화면·검색 결과에 실제로 있는 내용만. 비우지 않는다.",
            parameters = objectSchema(
                "summary" to stringProp("완료 요약 (1~2문장 한국어)"),
                "reason" to reasonProp(),
                required = listOf("reason"),
            ),
        ),
    )

    private val byName: Map<String, ToolDefinition> = definitions.associateBy { it.name }

    fun execute(call: ToolCall): ToolResult {
        if (call.name == "get_screen_info") return getScreenInfo()
        if (byName[call.name] == null) {
            return ToolResult(false, "알 수 없는 도구: ${call.name}")
        }
        return when (call.name) {
            "open_app" -> executeOpenApp(call.args)
            "tap_node" -> executeTapNode(call.args)
            "input_text" -> executeInputText(call.args)
            "back" -> executeBack(call.args)
            "scroll" -> executeScroll(call.args)
            "web_search" -> executeWebSearch(call.args)
            "ask_user" -> executeAskUser(call.args)
            "finish" -> executeFinish(call.args)
            else -> ToolResult(false, "알 수 없는 도구: ${call.name}")
        }
    }

    /** LLM 스키마에는 없음. 덤프·내부 관찰용. */
    fun getScreenInfo(): ToolResult {
        val service = AgentAccessibilityService.instance
            ?: return ToolResult(false, "접근성 서비스 미연결")
        val tree = service.getScreenTree()
            ?: return ToolResult(false, "화면 트리를 읽을 수 없음 (root null)")
        val lines = tree.lines().count { it.isNotBlank() }
        return ToolResult(true, "screen tree ($lines nodes):\n$tree")
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
        val named = service.describeNode(nodeId)
        val ok = service.tapNode(nodeId)
        return if (ok) {
            ToolResult(true, "tap_node($named) OK")
        } else {
            ToolResult(false, "tap_node($named) 실패 (존재하지 않는 id 또는 제스처 실패)")
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

    private fun executeBack(@Suppress("UNUSED_PARAMETER") args: JsonObject): ToolResult {
        val service = AgentAccessibilityService.instance
            ?: return ToolResult(false, "접근성 서비스 미연결")
        val ok = service.performBack()
        return if (ok) {
            ToolResult(true, "back OK")
        } else {
            ToolResult(false, "back 실패")
        }
    }

    private fun executeScroll(args: JsonObject): ToolResult {
        val nodeId = requireString(args, "node_id")
            ?: return ToolResult(false, "node_id 인자 필요")
        val direction = requireString(args, "direction")
            ?: return ToolResult(false, "direction 인자 필요")
        if (direction != "up" && direction != "down") {
            return ToolResult(false, "direction은 up 또는 down만 가능")
        }
        val service = AgentAccessibilityService.instance
            ?: return ToolResult(false, "접근성 서비스 미연결")
        val ok = service.scrollNode(nodeId, direction)
        return if (ok) {
            ToolResult(true, "scroll($nodeId, $direction) OK")
        } else {
            ToolResult(false, "scroll($nodeId, $direction) 실패 (scroll 마크 없음 또는 스크롤 불가)")
        }
    }

    private fun executeWebSearch(args: JsonObject): ToolResult {
        val query = requireString(args, "query")
            ?: return ToolResult(false, "query 인자 필요")
        return WebSearchClient.shared.search(query)
    }

    /** ReactAgent가 UI 대기 전에 인자 검증용으로 호출한다. 실제 대기는 onAskUser 콜백에서 수행. */
    private fun executeAskUser(args: JsonObject): ToolResult {
        return when (val parsed = parseAskUserPrompt(args)) {
            is AskUserParseResult.Ok -> ToolResult(true, "ask_user pending")
            is AskUserParseResult.Error -> ToolResult(false, parsed.message)
        }
    }

    fun parseAskUserPrompt(args: JsonObject): AskUserParseResult {
        val question = requireString(args, "question")
            ?: return AskUserParseResult.Error("question 인자 필요")
        val kindRaw = requireString(args, "kind")
            ?: return AskUserParseResult.Error("kind 인자 필요")
        val kind = com.mystar.agent.agent.AskUserKind.fromString(kindRaw)
            ?: return AskUserParseResult.Error("kind는 missing_info 또는 confirm만 가능")
        return AskUserParseResult.Ok(
            com.mystar.agent.agent.AskUserPrompt(question = question, kind = kind),
        )
    }

    sealed class AskUserParseResult {
        data class Ok(val prompt: com.mystar.agent.agent.AskUserPrompt) : AskUserParseResult()
        data class Error(val message: String) : AskUserParseResult()
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

    private fun enumProp(description: String, values: List<String>): JsonObject = buildJsonObject {
        put("type", "string")
        put("description", description)
        put("enum", JsonArray(values.map { JsonPrimitive(it) }))
    }

    private fun reasonProp(): JsonObject = stringProp("이 도구를 지금 고른 이유. 한 문장.")
}
