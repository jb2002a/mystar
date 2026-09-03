package com.mystar.agent.agent

import com.mystar.agent.AgentAccessibilityService
import com.mystar.agent.ServiceStatus
import com.mystar.agent.llm.CloudLlmClient
import com.mystar.agent.llm.LlmResult
import com.mystar.agent.tool.ToolRegistry
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * M3: 관찰 1회 → LLM 판단 1회 → 도구 실행 1회 → 종료.
 */
class SingleStepAgent(
    private val llmClient: CloudLlmClient = CloudLlmClient(),
) {
    private val running = AtomicBoolean(false)
    private val mutex = Mutex()

    suspend fun runOnce(goal: String): Boolean = mutex.withLock {
        if (!running.compareAndSet(false, true)) {
            ServiceStatus.appendLog("LLM 1회: 이미 실행 중")
            return false
        }
        try {
            execute(goal.trim())
        } finally {
            running.set(false)
        }
    }

    private suspend fun execute(goal: String): Boolean {
        if (goal.isEmpty()) {
            ServiceStatus.appendLog("LLM 1회: 목표가 비어 있음")
            return false
        }

        val configError = llmClient.configErrorOrNull()
        if (configError != null) {
            ServiceStatus.appendLog("LLM 1회: $configError")
            return false
        }

        val service = AgentAccessibilityService.instance
        if (service == null) {
            ServiceStatus.appendLog("LLM 1회: 접근성 서비스 미연결")
            return false
        }

        ServiceStatus.appendLog("LLM 1회: 목표=\"$goal\"")

        val tree = withContext(Dispatchers.Default) {
            service.getScreenTree()
        }
        if (tree == null) {
            ServiceStatus.appendLog("LLM 1회: 화면 트리 null (활성 창 없음?)")
            return false
        }
        val nodeCount = tree.lines().count { it.isNotBlank() }
        ServiceStatus.appendLog("LLM 1회: 화면 캡처 OK ($nodeCount nodes)")

        val llmResult = llmClient.chooseTool(goal, tree)
        val toolCall = when (llmResult) {
            is LlmResult.Success -> llmResult.toolCall
            is LlmResult.Failure -> {
                ServiceStatus.appendLog("LLM 1회: 실패 — ${llmResult.message}")
                return false
            }
        }

        val argsText = toolCall.args.toString()
        ServiceStatus.appendLog("LLM 1회: 선택 ${toolCall.name}$argsText")

        val result = withContext(Dispatchers.Default) {
            ToolRegistry.execute(toolCall)
        }
        if (result.success) {
            ServiceStatus.appendLog("LLM 1회: 실행 OK — ${result.message.lineSequence().first()}")
        } else {
            ServiceStatus.appendLog("LLM 1회: 실행 실패 — ${result.message}")
        }
        return result.success
    }
}
