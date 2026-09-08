package com.mystar.agent

import com.mystar.agent.agent.ReactAgent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 골든셋 자동 연속 실행.
 * 태스크별로 repeat회 연속 실행하고, 런 사이에 최근 앱을 닫고 홈으로 되돌린다.
 * 실행이 실패해도 기록만 남기고 다음 런으로 넘어간다.
 *
 * MainActivity가 큐에 의해 함께 닫히므로 상태는 프로세스 싱글턴이 들고 있는다.
 */
object EvalQueueRunner {

    data class QueueState(
        val running: Boolean = false,
        /** 끝난 런 수. */
        val done: Int = 0,
        val total: Int = 0,
        /** 1-based 태스크 번호. */
        val taskNo: Int = 0,
        val attempt: Int = 0,
        val repeat: Int = 0,
        val goal: String = "",
        val phase: String = "",
    )

    /** 홈/최근 앱 전환 후 화면이 자리 잡을 때까지. */
    private const val NAV_SETTLE_MS = 1_200L
    private const val RECENTS_SETTLE_MS = 1_500L

    /** 최근 앱 화면의 전체 닫기 버튼 라벨 (기기·언어별 표기). */
    private val CLOSE_ALL_LABELS = listOf(
        "모두 닫기",
        "모두 지우기",
        "전체 닫기",
        "닫기 전체",
        "Close all",
        "Clear all",
    )

    /** docs/evaluation/set_D0.md 골든셋. */
    private val DEFAULT_TASKS = listOf(
        "카카오톡에서 기흥에게 오늘 병원 다녀왔어라고 보내줘",
        "카카오톡에서 기흥이 마지막으로 보낸 말 읽어줘",
        "내일 서울 날씨알려줘",
        "익산에서 지금 여는 약국 찾아줘",
        "서울에서 대전까지 얼마나걸려",
        "설정에서 글자 크기 조절하는 곳 열어줘",
        "오전 8시로 알람설정해줘.",
        "네이버에서 뉴스 열어줘",
        "막내에게 전화 걸어줘",
        "막내에게 문자로 집에 잘 도착했어라고 보내줘",
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Volatile
    private var stopRequested = false

    private val _state = MutableStateFlow(QueueState())
    val state: StateFlow<QueueState> = _state.asStateFlow()

    private val _tasksText = MutableStateFlow(DEFAULT_TASKS.joinToString("\n"))
    val tasksText: StateFlow<String> = _tasksText.asStateFlow()

    private val _repeatText = MutableStateFlow("3")
    val repeatText: StateFlow<String> = _repeatText.asStateFlow()

    private val _cooldownText = MutableStateFlow("5")
    val cooldownText: StateFlow<String> = _cooldownText.asStateFlow()

    fun setTasksText(value: String) {
        _tasksText.value = value
    }

    fun setRepeatText(value: String) {
        _repeatText.value = value
    }

    fun setCooldownText(value: String) {
        _cooldownText.value = value
    }

    /** 현재 입력으로 만들어질 총 런 수. 입력이 잘못되면 0. */
    fun plannedTotal(): Int {
        val tasks = parseTasks()
        val repeat = _repeatText.value.trim().toIntOrNull() ?: return 0
        if (repeat < 1) return 0
        return tasks.size * repeat
    }

    /** @return 시작하지 못한 이유. 정상 시작이면 null. */
    fun start(): String? {
        if (_state.value.running) return "큐가 이미 실행 중입니다"
        val tasks = parseTasks()
        if (tasks.isEmpty()) return "태스크 목록이 비어 있습니다"
        val repeat = _repeatText.value.trim().toIntOrNull()
        if (repeat == null || repeat < 1) return "반복 횟수는 1 이상이어야 합니다"
        val cooldownS = _cooldownText.value.trim().toIntOrNull()
        if (cooldownS == null || cooldownS < 0) return "쿨다운(초)은 0 이상이어야 합니다"
        if (AgentAccessibilityService.instance == null) return "접근성 서비스가 연결되지 않았습니다"

        stopRequested = false
        _state.value = QueueState(
            running = true,
            total = tasks.size * repeat,
            repeat = repeat,
            phase = "시작",
        )
        scope.launch { runQueue(tasks, repeat, cooldownS * 1000L) }
        return null
    }

    /** 현재 런을 끊고 큐를 멈춘다. 진행 중이던 런의 평가 JSON은 그대로 저장된다. */
    fun stop() {
        if (!_state.value.running) return
        stopRequested = true
        ReactAgent.shared.requestStop()
        ServiceStatus.appendLog("큐: 중단 요청 — 현재 런을 정리한 뒤 멈춥니다")
    }

    private fun parseTasks(): List<String> =
        _tasksText.value.lines().map { it.trim() }.filter { it.isNotEmpty() }

    private suspend fun runQueue(tasks: List<String>, repeat: Int, cooldownMs: Long) {
        val queueId = queueIdFormat().format(Date())
        val total = tasks.size * repeat
        var done = 0
        var succeeded = 0
        ServiceStatus.appendLog(
            "큐: 시작 — 태스크 ${tasks.size}개 × ${repeat}회 = ${total}런 (id=$queueId)",
        )
        try {
            for ((index, goal) in tasks.withIndex()) {
                for (attempt in 1..repeat) {
                    if (stopRequested) {
                        ServiceStatus.appendLog("큐: 중단됨 — $done/$total 실행")
                        return
                    }
                    val taskNo = index + 1
                    val service = AgentAccessibilityService.instance
                    if (service == null) {
                        ServiceStatus.appendLog("큐: 접근성 서비스 끊김 — $done/$total 에서 중단")
                        return
                    }

                    _state.value = QueueState(
                        running = true,
                        done = done,
                        total = total,
                        taskNo = taskNo,
                        attempt = attempt,
                        repeat = repeat,
                        goal = goal,
                        phase = "앱 정리",
                    )
                    resetApps(service, cooldownMs)
                    if (stopRequested) {
                        ServiceStatus.appendLog("큐: 중단됨 — $done/$total 실행")
                        return
                    }

                    _state.value = _state.value.copy(phase = "실행")
                    ServiceStatus.appendLog("큐: [${done + 1}/$total] t$taskNo a$attempt — \"$goal\"")
                    val ok = ReactAgent.shared.run(
                        goal = goal,
                        onEvent = { ServiceStatus.appendLog(it) },
                        onFinishSummary = { AgentTts.instance?.speak(it) },
                        onSpeakQuestion = { AgentTts.instance?.speakAwaitingDone(it) },
                        evalTag = EvalTag(queueId, taskNo, attempt),
                    )
                    done++
                    if (ok) succeeded++
                    ServiceStatus.appendLog(
                        "큐: [$done/$total] t$taskNo a$attempt 결과 ${if (ok) "성공" else "실패"}",
                    )
                    _state.value = _state.value.copy(done = done, phase = "런 종료")
                }
            }
            ServiceStatus.appendLog("큐: 완료 — $total 런 중 $succeeded 성공")
            AgentTts.instance?.speak("평가 큐를 마쳤습니다. $total 건 중 $succeeded 건 성공했습니다.")
        } finally {
            _state.value = QueueState()
            stopRequested = false
        }
    }

    /**
     * 다음 런의 전제(앱이 꺼져 있거나 홈 화면)를 맞춘다.
     * 홈 → 최근 앱 → '모두 닫기' → 홈 → 쿨다운.
     * '모두 닫기' 버튼을 못 찾으면 홈 복귀까지만 하고 로그를 남긴다.
     */
    private suspend fun resetApps(service: AgentAccessibilityService, cooldownMs: Long) {
        ServiceStatus.appendLog("큐: 앱 정리 — 홈 → 최근 앱 → 모두 닫기")
        service.performHome()
        delay(NAV_SETTLE_MS)

        if (service.performRecents()) {
            delay(RECENTS_SETTLE_MS)
            val nodeId = withContext(Dispatchers.Default) {
                if (service.getScreenTree() == null) {
                    null
                } else {
                    service.findNodeIdByLabel(CLOSE_ALL_LABELS)
                }
            }
            if (nodeId == null) {
                ServiceStatus.appendLog("큐: 최근 앱에 '모두 닫기' 없음 — 홈 복귀만 수행")
            } else {
                withContext(Dispatchers.Default) { service.tapNode(nodeId) }
                delay(NAV_SETTLE_MS)
            }
        }

        service.performHome()
        delay(NAV_SETTLE_MS)
        if (cooldownMs > 0) {
            _state.value = _state.value.copy(phase = "쿨다운 ${cooldownMs / 1000}초")
            delay(cooldownMs)
        }
    }

    private fun queueIdFormat(): SimpleDateFormat =
        SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.US).apply {
            timeZone = TimeZone.getDefault()
        }
}
