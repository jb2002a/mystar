package com.mystar.agent.agent

import com.mystar.agent.AgentAccessibilityService
import com.mystar.agent.BuildConfig
import com.mystar.agent.StabilizeOutcome
import com.mystar.agent.llm.CloudLlmClient
import com.mystar.agent.llm.LlmResult
import com.mystar.agent.tool.AppCatalog
import com.mystar.agent.tool.ToolCall
import com.mystar.agent.tool.ToolRegistry
import com.mystar.agent.tool.ToolResult
import com.mystar.agent.tracing.LangSmithClient
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * M4: reason → act → (안정화 + 최신 트리 tool_result) 반복.
 * 행동 도구: open_app / tap_node / input_text / back / scroll.
 * 비화면 도구: web_search / ask_user (finish 제외).
 */
class ReactAgent(
    private val llmClient: CloudLlmClient = CloudLlmClient(),
    private val tracer: LangSmithClient = LangSmithClient.shared,
) {
    private val running = AtomicBoolean(false)
    private val stopRequested = AtomicBoolean(false)
    private val mutex = Mutex()

    /** 실행 중이면 다음 체크 지점에서 루프를 강제 종료한다. */
    fun requestStop(): Boolean {
        if (!running.get()) return false
        stopRequested.set(true)
        return true
    }

    /**
     * @param onEvent 라운드/도구/안정화/종료 로그를 UI에 스트리밍
     * @param onFinishSummary finish 호출 시 사용자에게 읽어줄 summary (비어 있으면 기본 문구)
     * @param onSpeakQuestion ask_user 질문 TTS
     * @param onAskUser ask_user 대기 — 사람 응답을 반환한다
     * @return true if finish로 정상 종료, false if 실패/최대 라운드/중단
     */
    suspend fun run(
        goal: String,
        onEvent: (String) -> Unit = {},
        onFinishSummary: (String) -> Unit = {},
        onSpeakQuestion: suspend (String) -> Unit = {},
        onAskUser: suspend (AskUserPrompt) -> AskUserAnswer = { prompt ->
            askUserDefault(prompt, onSpeakQuestion)
        },
    ): Boolean = mutex.withLock {
        if (!running.compareAndSet(false, true)) {
            onEvent("ReAct: 이미 실행 중")
            return false
        }
        stopRequested.set(false)
        try {
            execute(goal.trim(), onEvent, onFinishSummary, onSpeakQuestion, onAskUser)
        } finally {
            running.set(false)
            stopRequested.set(false)
        }
    }

    private suspend fun execute(
        goal: String,
        onEvent: (String) -> Unit,
        onFinishSummary: (String) -> Unit,
        onSpeakQuestion: suspend (String) -> Unit,
        onAskUser: suspend (AskUserPrompt) -> AskUserAnswer,
    ): Boolean {
        if (goal.isEmpty()) {
            onEvent("ReAct: 목표가 비어 있음")
            return false
        }

        val configError = llmClient.configErrorOrNull()
        if (configError != null) {
            onEvent("ReAct: $configError")
            return false
        }

        val service = AgentAccessibilityService.instance
        if (service == null) {
            onEvent("ReAct: 접근성 서비스 미연결")
            return false
        }

        onEvent("ReAct: 시작 — 목표=\"$goal\"")
        if (tracer.enabled) {
            onEvent("ReAct: LangSmith tracing ON (project=${BuildConfig.LANGSMITH_PROJECT})")
        }

        val rootRunId = tracer.startRun(
            name = "react_agent_run",
            runType = "chain",
            inputs = buildJsonObject {
                put("goal", LangSmithClient.redactGoal(goal))
            },
        )

        var completedRounds = 0
        var endError: String? = null
        var success = false
        var endReason = "unknown"

        fun cancelled(): Boolean {
            if (!stopRequested.get()) return false
            onEvent("ReAct: 강제 종료됨")
            endReason = "cancelled"
            endError = "forced stop"
            return true
        }

        try {
            // 초기 트리 주입 임시 비활성. 첫 화면은 행동 후 tool_result로만 본다.
            // val initialTree = withContext(Dispatchers.Default) {
            //     service.getScreenTree()
            // }
            // if (initialTree == null) {
            //     onEvent("ReAct: 초기 화면 트리 null (활성 창 없음?)")
            //     endReason = "initial_tree_null"
            //     endError = "initial screen tree null"
            //     return false
            // }
            // val initialNodes = initialTree.lines().count { it.isNotBlank() }
            // onEvent("ReAct: 초기 트리 1회 주입 준비 ($initialNodes nodes)")

            val appCatalog = withContext(Dispatchers.Default) {
                AppCatalog.buildCatalog(service)
            }
            val catalogLines = appCatalog.lines().count { it.isNotBlank() }
            onEvent("ReAct: 앱 카탈로그 1회 주입 준비 ($catalogLines apps)")

            val messages = mutableListOf<JsonObject>(
                llmClient.buildSystemMessage(),
                llmClient.buildGoalUserMessage(goal),
            )
            val initialAppCatalog = llmClient.buildInitialAppCatalogMessage(appCatalog)
            // val initialScreenContext = llmClient.buildInitialScreenContextMessage(initialTree)

            for (round in 1..MAX_ROUNDS) {
                if (cancelled()) return false
                completedRounds = round
                onEvent("ReAct: 라운드 $round/$MAX_ROUNDS")

                val requestMessages = if (round == 1) {
                    onEvent("ReAct: 앱 카탈로그 1회 전달 (히스토리 비누적)")
                    listOf(messages[0], initialAppCatalog) + messages.drop(1)
                    // onEvent("ReAct: 앱 카탈로그·초기 트리 1회 전달 (히스토리 비누적)")
                    // listOf(messages[0], initialAppCatalog, initialScreenContext) + messages.drop(1)
                } else {
                    messages
                }
                val llmResult = llmClient.chooseNextTool(requestMessages, parentRunId = rootRunId)
                val (toolCall, assistantMessage) = when (llmResult) {
                    is LlmResult.Success -> llmResult.toolCall to llmResult.assistantMessage
                    is LlmResult.Failure -> {
                        onEvent("ReAct: LLM 실패 — ${llmResult.message}")
                        endReason = "llm_failure"
                        endError = llmResult.message
                        return false
                    }
                }

                if (cancelled()) return false

                messages.add(assistantMessage)
                onEvent(formatToolChoice(toolCall))

                val toolRunId = tracer.startRun(
                    name = toolCall.name,
                    runType = "tool",
                    inputs = buildJsonObject {
                        put("name", toolCall.name)
                        put("args", LangSmithClient.sanitizeToolArgs(toolCall.name, toolCall.args))
                        put("round", round)
                    },
                    parentRunId = rootRunId,
                )

                val actionResult = when (toolCall.name) {
                    "ask_user" -> executeAskUser(
                        toolCall = toolCall,
                        onEvent = onEvent,
                        onSpeakQuestion = onSpeakQuestion,
                        onAskUser = onAskUser,
                        aborted = { stopRequested.get() },
                    )
                    else -> withContext(
                        if (toolCall.name == "web_search") Dispatchers.IO else Dispatchers.Default,
                    ) {
                        ToolRegistry.execute(toolCall)
                    }
                }

                tracer.endRun(
                    toolRunId,
                    outputs = buildJsonObject {
                        put("success", actionResult.success)
                        put(
                            "message",
                            LangSmithClient.sanitizeToolResultMessage(actionResult.message),
                        )
                    },
                    error = if (actionResult.success) null else actionResult.message.lineSequence().first(),
                )

                if (toolCall.name == "finish") {
                    onEvent("ReAct: 완료 — ${actionResult.message}")
                    val summary = toolCall.args["summary"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                    val spokenSummary = summary.ifEmpty { DEFAULT_FINISH_SUMMARY }
                    onFinishSummary(spokenSummary)
                    success = actionResult.success
                    endReason = if (success) "finish" else "finish_failed"
                    if (!success) {
                        endError = actionResult.message
                    }
                    return actionResult.success
                }

                if (cancelled()) return false

                if (toolCall.name == "ask_user" &&
                    actionResult.message.contains("사용자가 작업을 중단")
                ) {
                    return false
                }

                if (!shouldAttachScreenTree(toolCall.name)) {
                    messages.add(llmClient.buildToolResultMessage(toolCall.id, actionResult.message))
                    val status = if (actionResult.success) "OK" else "실패"
                    onEvent("ReAct: 결과 $status — ${actionResult.message.lineSequence().first()}")
                    continue
                }

                val expectedPackage = if (toolCall.name == "open_app") {
                    toolCall.args["package"]?.jsonPrimitive?.contentOrNull?.trim()?.ifEmpty { null }
                } else {
                    null
                }
                onEvent(
                    "ReAct: 안정화 대기 (quiet=${AgentAccessibilityService.QUIET_WINDOW_MS}ms" +
                        " / hard=${AgentAccessibilityService.HARD_TIMEOUT_MS}ms" +
                        expectedPackage?.let { " / pkg=$it" }.orEmpty() +
                        ")",
                )
                val outcome = service.waitForUiSettle(
                    expectedPackage = expectedPackage,
                    aborted = { stopRequested.get() },
                )
                if (cancelled()) return false
                val settleLabel = if (outcome == StabilizeOutcome.QUIET) "quiet" else "hard timeout"
                onEvent("ReAct: 안정화 종료 ($settleLabel)")

                val latestTree = withContext(Dispatchers.Default) {
                    service.getScreenTree()
                }
                val treeBlock = if (latestTree != null) {
                    val nodes = latestTree.lines().count { it.isNotBlank() }
                    onEvent("ReAct: 트리 갱신 ($nodes nodes)")
                    "screen tree ($nodes nodes):\n$latestTree"
                } else {
                    onEvent("ReAct: 트리 null (root 없음)")
                    "screen tree: null (root 없음)"
                }

                val resultContent = buildString {
                    append(actionResult.message)
                    appendLine()
                    appendLine()
                    append(treeBlock)
                }
                messages.add(llmClient.buildToolResultMessage(toolCall.id, resultContent))

                val status = if (actionResult.success) "OK" else "실패"
                onEvent("ReAct: 결과 $status — ${actionResult.message.lineSequence().first()}")
            }

            onEvent("ReAct: 최대 라운드($MAX_ROUNDS) 도달 — 중단 (완료 여부 불명확)")
            endReason = "max_rounds"
            endError = "max rounds reached"
            return false
        } finally {
            tracer.endRun(
                rootRunId,
                outputs = buildJsonObject {
                    put("success", success)
                    put("reason", endReason)
                    put("rounds", completedRounds)
                },
                error = endError,
            )
        }
    }

    private suspend fun executeAskUser(
        toolCall: ToolCall,
        onEvent: (String) -> Unit,
        onSpeakQuestion: suspend (String) -> Unit,
        onAskUser: suspend (AskUserPrompt) -> AskUserAnswer,
        aborted: () -> Boolean,
    ): ToolResult {
        return when (val parsed = ToolRegistry.parseAskUserPrompt(toolCall.args)) {
            is ToolRegistry.AskUserParseResult.Error -> ToolResult(false, parsed.message)
            is ToolRegistry.AskUserParseResult.Ok -> {
                onEvent(
                    "ReAct: 사용자에게 질문 (${AskUserKind.toApiString(parsed.prompt.kind)})",
                )
                val answer = withTimeoutOrNull(ASK_USER_TIMEOUT_MS) {
                    onAskUser(parsed.prompt)
                } ?: AskUserAnswer.Timeout
                AskUserResultFormatter.toToolResult(parsed.prompt.kind, answer)
            }
        }
    }

    private suspend fun askUserDefault(
        prompt: AskUserPrompt,
        speakQuestion: suspend (String) -> Unit,
    ): AskUserAnswer {
        val service = AgentAccessibilityService.instance
            ?: return AskUserAnswer.Timeout
        return service.askUser(
            prompt = prompt,
            speakQuestion = speakQuestion,
            aborted = { stopRequested.get() },
        )
    }

    private fun formatToolChoice(toolCall: ToolCall): String {
        val reason = toolCall.args["reason"]?.jsonPrimitive?.contentOrNull?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: "(reason 없음)"
        val otherArgs = buildJsonObject {
            for ((key, value) in toolCall.args) {
                if (key != "reason") {
                    put(key, value)
                }
            }
        }
        val argsSuffix = if (otherArgs.isEmpty()) "" else " $otherArgs"
        return "ReAct: 선택 ${toolCall.name} — $reason$argsSuffix"
    }

    private fun shouldAttachScreenTree(toolName: String): Boolean {
        return toolName !in NON_SCREEN_TOOLS
    }

    companion object {
        const val MAX_ROUNDS = 20
        const val DEFAULT_FINISH_SUMMARY = "작업을 마쳤습니다."
        const val ASK_USER_TIMEOUT_MS = 60_000L

        private val NON_SCREEN_TOOLS = setOf("web_search", "ask_user")

        val shared = ReactAgent()
    }
}
