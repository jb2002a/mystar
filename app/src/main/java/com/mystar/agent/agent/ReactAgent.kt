package com.mystar.agent.agent

import com.mystar.agent.AgentAccessibilityService
import com.mystar.agent.StabilizeOutcome
import com.mystar.agent.llm.CloudLlmClient
import com.mystar.agent.llm.LlmResult
import com.mystar.agent.tool.ToolRegistry
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/**
 * M4: reason → act → (안정화 + 최신 트리 tool_result) 반복.
 */
class ReactAgent(
    private val llmClient: CloudLlmClient = CloudLlmClient(),
) {
    private val running = AtomicBoolean(false)
    private val mutex = Mutex()

    /**
     * @param onEvent 라운드/도구/안정화/종료 로그를 UI에 스트리밍
     * @return true if finish로 정상 종료, false if 실패/최대 라운드/중단
     */
    suspend fun run(
        goal: String,
        onEvent: (String) -> Unit = {},
    ): Boolean = mutex.withLock {
        if (!running.compareAndSet(false, true)) {
            onEvent("ReAct: 이미 실행 중")
            return false
        }
        try {
            execute(goal.trim(), onEvent)
        } finally {
            running.set(false)
        }
    }

    private suspend fun execute(goal: String, onEvent: (String) -> Unit): Boolean {
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

        val initialTree = withContext(Dispatchers.Default) {
            service.getScreenTree()
        }
        if (initialTree == null) {
            onEvent("ReAct: 초기 화면 트리 null (활성 창 없음?)")
            return false
        }
        val initialNodes = initialTree.lines().count { it.isNotBlank() }
        onEvent("ReAct: 초기 트리 주입 ($initialNodes nodes)")

        val messages = mutableListOf<JsonObject>(
            llmClient.buildSystemMessage(),
            llmClient.buildInitialUserMessage(goal, initialTree),
        )

        for (round in 1..MAX_ROUNDS) {
            onEvent("ReAct: 라운드 $round/$MAX_ROUNDS")

            val llmResult = llmClient.chooseNextTool(messages)
            val (toolCall, assistantMessage) = when (llmResult) {
                is LlmResult.Success -> llmResult.toolCall to llmResult.assistantMessage
                is LlmResult.Failure -> {
                    onEvent("ReAct: LLM 실패 — ${llmResult.message}")
                    return false
                }
            }

            messages.add(assistantMessage)
            onEvent("ReAct: 선택 ${toolCall.name}${toolCall.args}")

            val actionResult = withContext(Dispatchers.Default) {
                ToolRegistry.execute(toolCall)
            }

            if (toolCall.name == "finish") {
                onEvent("ReAct: 완료 — ${actionResult.message}")
                return actionResult.success
            }

            onEvent("ReAct: 안정화 대기 (quiet=${AgentAccessibilityService.QUIET_WINDOW_MS}ms / hard=${AgentAccessibilityService.HARD_TIMEOUT_MS}ms)")
            val outcome = service.waitForUiSettle()
            val settleLabel = when (outcome) {
                StabilizeOutcome.QUIET -> "quiet"
                StabilizeOutcome.HARD_TIMEOUT -> "hard timeout"
            }
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
        return false
    }

    companion object {
        const val MAX_ROUNDS = 10

        /** UI·오버레이가 공유하는 단일 인스턴스 (동시 실행 방지). */
        val shared = ReactAgent()
    }
}
