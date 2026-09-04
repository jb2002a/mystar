package com.mystar.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import com.mystar.agent.agent.ReactAgent
import com.mystar.agent.agent.AskUserAnswer
import com.mystar.agent.agent.AskUserPrompt
import com.mystar.agent.tool.ToolRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class StabilizeOutcome {
    QUIET,
    HARD_TIMEOUT,
    ABORTED,
}

/**
 * M1: 접근성 트리 관찰(getScreenTree) + 덤프 오버레이.
 * M2: tapNode / inputText 행동 API.
 * M4: 이벤트 quiet window 안정화 + 오버레이 강제 종료.
 * M7: performBack. M8: scrollNode + scrollable 노드 참조 맵.
 */
class AgentAccessibilityService : AccessibilityService() {

    private val nodeCoords = ConcurrentHashMap<String, Point>()
    /** M8: 마지막 getScreenTree()의 scrollable node id → 스크롤 액션용 노드 복사본. */
    private val nodeScrollRefs = ConcurrentHashMap<String, AccessibilityNodeInfo>()
    private val nodeCounter = AtomicInteger(0)

    /** WINDOW_STATE/CONTENT_CHANGED 마지막 수신 시각 (elapsedRealtime). */
    private val lastWindowEventAt = AtomicLong(0L)

    private var overlayDumpButton: Button? = null
    private var overlayLlmButton: Button? = null
    private var dumpOverlayHiddenForHitl = false

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val hitlOverlayHost = HitlOverlayHost(this)

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        ServiceStatus.setConnected(true)
        ServiceStatus.appendLog("onServiceConnected: instance bound")
        Log.i(TAG, "onServiceConnected: instance bound")
        setOverlayVisible(ServiceStatus.overlayEnabled.value)
    }

    fun setOverlayVisible(visible: Boolean) {
        if (visible) {
            showDumpOverlay()
        } else {
            removeDumpOverlay()
            ServiceStatus.appendLog("오버레이 숨김")
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            -> lastWindowEventAt.set(SystemClock.elapsedRealtime())
        }
    }

    /**
     * 행동 직후 UI 안정화를 기다린다.
     * 대기 시작 이후 창 이벤트가 없으면 quietMs 뒤 QUIET,
     * 이벤트가 이어지면 마지막 이벤트 후 quietMs 뒤 QUIET,
     * 어떤 경우든 hardTimeoutMs 에서 HARD_TIMEOUT.
     */
    suspend fun waitForUiSettle(
        quietMs: Long = QUIET_WINDOW_MS,
        hardTimeoutMs: Long = HARD_TIMEOUT_MS,
        aborted: () -> Boolean = { false },
    ): StabilizeOutcome {
        val start = SystemClock.elapsedRealtime()
        while (true) {
            if (aborted()) {
                return StabilizeOutcome.ABORTED
            }
            val now = SystemClock.elapsedRealtime()
            val elapsed = now - start
            if (elapsed >= hardTimeoutMs) {
                return StabilizeOutcome.HARD_TIMEOUT
            }
            val lastEvent = lastWindowEventAt.get()
            val effectiveLast = if (lastEvent > start) lastEvent else start
            if (now - effectiveLast >= quietMs) {
                return StabilizeOutcome.QUIET
            }
            delay(STABILIZE_POLL_MS)
        }
    }

    override fun onInterrupt() {
        ServiceStatus.appendLog("onInterrupt")
        Log.w(TAG, "onInterrupt")
    }

    override fun onDestroy() {
        removeDumpOverlay()
        hitlOverlayHost.dismissIfShowing()
        clearScrollRefs()
        if (instance === this) {
            instance = null
            ServiceStatus.setConnected(false)
            ServiceStatus.appendLog("onDestroy: instance cleared")
            Log.i(TAG, "onDestroy: instance cleared")
        }
        super.onDestroy()
    }

    /** HITL 음성 인식 등 서비스에서 시작하는 코루틴. */
    fun launchScopeForHitl(block: suspend () -> Unit) {
        serviceScope.launch { block() }
    }

    suspend fun askUser(
        prompt: AskUserPrompt,
        speakQuestion: (String) -> Unit,
        aborted: () -> Boolean,
    ): AskUserAnswer = hitlOverlayHost.askUser(prompt, speakQuestion, aborted)

    fun setDumpOverlayHidden(hidden: Boolean) {
        dumpOverlayHiddenForHitl = hidden
        mainHandler.post { applyDumpOverlayVisibility() }
    }

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private fun applyDumpOverlayVisibility() {
        val visibility = if (dumpOverlayHiddenForHitl) android.view.View.GONE else android.view.View.VISIBLE
        overlayDumpButton?.visibility = visibility
        overlayLlmButton?.visibility = visibility
    }

    /** M2용: 마지막 getScreenTree() 스냅샷의 node id → 중심 좌표. */
    fun getNodeCoordinates(nodeId: String): Point? = nodeCoords[nodeId]

    /**
     * 마지막 getScreenTree() 스냅샷의 node id로 탭한다.
     * 좌표는 외부에 노출하지 않고 nodeCoords에서 조회한다.
     */
    fun tapNode(nodeId: String): Boolean {
        val id = nodeId.replace("[", "").replace("]", "").trim()
        if (id.isEmpty()) {
            ServiceStatus.appendLog("tapNode: node id가 비어 있음")
            Log.w(TAG, "tapNode: empty node id")
            return false
        }
        val point = getNodeCoordinates(id)
        if (point == null) {
            ServiceStatus.appendLog("Node $id 없음. 먼저 덤프하세요")
            Log.w(TAG, "tapNode: node $id not found")
            return false
        }
        val ok = performTap(point.x, point.y)
        if (ok) {
            ServiceStatus.appendLog("tapNode: $id at (${point.x},${point.y}) OK")
            Log.i(TAG, "tapNode: $id at (${point.x},${point.y}) OK")
        } else {
            ServiceStatus.appendLog("tapNode: $id at (${point.x},${point.y}) 실패")
            Log.w(TAG, "tapNode: $id at (${point.x},${point.y}) failed")
        }
        return ok
    }

    /**
     * node id로 입력 필드를 탭(포커스)한 뒤 ACTION_SET_TEXT로 텍스트를 넣는다.
     * findFocus가 비-editable이면 탭 좌표 근처 editable을 다시 찾는다.
     */
    fun inputText(text: String, nodeId: String): Boolean {
        val id = nodeId.replace("[", "").replace("]", "").trim()
        if (id.isEmpty()) {
            ServiceStatus.appendLog("inputText: node id가 비어 있음")
            Log.w(TAG, "inputText: empty node id")
            return false
        }
        val point = getNodeCoordinates(id)
        if (point == null) {
            ServiceStatus.appendLog("Node $id 없음. 먼저 덤프하세요")
            Log.w(TAG, "inputText: node $id not found")
            return false
        }

        val tapped = performTap(point.x, point.y)
        if (!tapped) {
            ServiceStatus.appendLog("inputText: $id 탭 실패")
            Log.w(TAG, "inputText: tap $id failed")
            return false
        }
        ServiceStatus.appendLog("inputText: $id 탭 후 포커스 대기")
        try {
            Thread.sleep(FOCUS_WAIT_MS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return false
        }

        val target = waitForTargetEditable(point.x, point.y)
        if (target == null) {
            ServiceStatus.appendLog("inputText: $id 탭 후에도 입력 필드 없음")
            Log.w(TAG, "inputText: no editable field after tapping $id")
            return false
        }
        return try {
            target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            val ok = trySetText(target, text)
            if (ok) {
                ServiceStatus.appendLog("inputText: $id OK (\"${sanitizeLabel(text)}\")")
                Log.i(TAG, "inputText: $id OK")
            } else {
                ServiceStatus.appendLog("inputText: $id ACTION_SET_TEXT 실패")
                Log.w(TAG, "inputText: ACTION_SET_TEXT failed for $id")
            }
            ok
        } finally {
            target.recycle()
        }
    }

    /** M7: 시스템 뒤로가기. */
    fun performBack(): Boolean {
        val ok = performGlobalAction(GLOBAL_ACTION_BACK)
        if (ok) {
            ServiceStatus.appendLog("back: OK")
            Log.i(TAG, "performBack: OK")
        } else {
            ServiceStatus.appendLog("back: 실패")
            Log.w(TAG, "performBack: failed")
        }
        return ok
    }

    /**
     * M8: scroll 마크가 있는 node id로 목록을 한 칸 스크롤한다.
     * @param direction "down" = 아래로 더 보기, "up" = 위로 되돌리기
     */
    fun scrollNode(nodeId: String, direction: String): Boolean {
        val id = nodeId.replace("[", "").replace("]", "").trim()
        if (id.isEmpty()) {
            ServiceStatus.appendLog("scrollNode: node id가 비어 있음")
            Log.w(TAG, "scrollNode: empty node id")
            return false
        }
        val dir = direction.trim().lowercase()
        if (dir != "up" && dir != "down") {
            ServiceStatus.appendLog("scrollNode: direction은 up/down만 가능 ($direction)")
            Log.w(TAG, "scrollNode: invalid direction $direction")
            return false
        }
        val scrollNode = nodeScrollRefs[id]
        if (scrollNode == null) {
            ServiceStatus.appendLog("scrollNode: $id 없음 또는 scroll 불가. 먼저 덤프하세요")
            Log.w(TAG, "scrollNode: node $id not scrollable")
            return false
        }
        val point = getNodeCoordinates(id)
        if (point == null) {
            ServiceStatus.appendLog("scrollNode: $id 좌표 없음")
            Log.w(TAG, "scrollNode: no coords for $id")
            return false
        }

        val scrollAction = if (dir == "down") {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        } else {
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        }
        var ok = scrollNode.performAction(scrollAction)
        var method = "action"
        if (!ok) {
            method = "gesture"
            ok = performScrollSwipe(point.x, point.y, dir)
        }
        if (ok) {
            ServiceStatus.appendLog("scrollNode: $id $dir ($method) OK")
            Log.i(TAG, "scrollNode: $id $dir ($method) OK")
        } else {
            ServiceStatus.appendLog("scrollNode: $id $dir 실패")
            Log.w(TAG, "scrollNode: $id $dir failed")
        }
        return ok
    }

    /**
     * findFocus(FOCUS_INPUT)가 editable이면 그걸 쓰고,
     * 아니면 탭 좌표를 포함하는 editable/EditText를 트리에서 찾는다.
     */
    private fun waitForTargetEditable(x: Int, y: Int): AccessibilityNodeInfo? {
        repeat(EDITABLE_RETRY_COUNT) { attempt ->
            val root = rootInActiveWindow
            if (root != null) {
                try {
                    val focused = findFocusedEditText(root)
                    if (focused != null) return focused
                    val near = findEditableNearPoint(root, x, y)
                    if (near != null) return near
                } finally {
                    root.recycle()
                }
            }
            if (attempt < EDITABLE_RETRY_COUNT - 1 && !sleepShort()) return null
        }
        return null
    }

    private fun findFocusedEditText(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return null
        if (focused.isEditable) return focused
        if (focused !== root) focused.recycle()
        return null
    }

    private fun findEditableNearPoint(node: AccessibilityNodeInfo, x: Int, y: Int): AccessibilityNodeInfo? {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        val className = node.className?.toString().orEmpty()
        val looksEditable = node.isEditable || className.contains("EditText")
        if (looksEditable && bounds.contains(x, y)) {
            return AccessibilityNodeInfo.obtain(node)
        }

        var best: AccessibilityNodeInfo? = null
        var bestDistance = Int.MAX_VALUE
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val candidate = try {
                findEditableNearPoint(child, x, y)
            } finally {
                child.recycle()
            } ?: continue
            val cb = Rect()
            candidate.getBoundsInScreen(cb)
            val dx = cb.centerX() - x
            val dy = cb.centerY() - y
            val distance = dx * dx + dy * dy
            if (distance < bestDistance) {
                best?.recycle()
                best = candidate
                bestDistance = distance
            } else {
                candidate.recycle()
            }
        }
        return best
    }

    private fun trySetText(node: AccessibilityNodeInfo, text: String): Boolean {
        repeat(SET_TEXT_RETRY_COUNT) { attempt ->
            val args = Bundle()
            args.putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                text,
            )
            if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return true
            if (attempt < SET_TEXT_RETRY_COUNT - 1) {
                if (!sleepShort()) return false
                node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
        }
        return false
    }

    private fun sleepShort(): Boolean {
        return try {
            Thread.sleep(RETRY_SLEEP_MS)
            true
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    private fun performTap(x: Int, y: Int, durationMs: Long = 100L): Boolean {
        val path = Path()
        path.moveTo(x.toFloat(), y.toFloat())
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGestureSync(gesture)
    }

    /** M8 폴백: 노드 중심에서 짧은 스와이프로 스크롤. */
    private fun performScrollSwipe(cx: Int, cy: Int, direction: String): Boolean {
        val delta = SCROLL_SWIPE_DELTA_PX
        val (startY, endY) = if (direction == "down") {
            cy + delta to cy - delta
        } else {
            cy - delta to cy + delta
        }
        val path = Path()
        path.moveTo(cx.toFloat(), startY.toFloat())
        path.lineTo(cx.toFloat(), endY.toFloat())
        val stroke = GestureDescription.StrokeDescription(path, 0, SCROLL_SWIPE_DURATION_MS)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGestureSync(gesture)
    }

    private fun clearScrollRefs() {
        for ((_, node) in nodeScrollRefs) {
            node.recycle()
        }
        nodeScrollRefs.clear()
    }

    private fun dispatchGestureSync(gesture: GestureDescription): Boolean {
        val latch = CountDownLatch(1)
        val result = AtomicBoolean(false)
        val dispatched = dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    result.set(true)
                    latch.countDown()
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    result.set(false)
                    latch.countDown()
                }
            },
            null,
        )
        if (!dispatched) {
            ServiceStatus.appendLog("dispatchGesture: 거부됨 (권한/서비스 상태 확인)")
            Log.w(TAG, "dispatchGesture returned false")
            return false
        }
        return try {
            latch.await(GESTURE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            result.get()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            Log.w(TAG, "dispatchGestureSync interrupted", e)
            false
        }
    }

    /**
     * 현재 활성 창의 의미 있는 노드만 압축 텍스트로 직렬화한다.
     * 호출마다 node id를 재발급하고 nodeCoords를 갱신한다.
     * root가 없으면 null.
     */
    fun getScreenTree(): String? {
        val root = rootInActiveWindow ?: return null
        clearScrollRefs()
        nodeCoords.clear()
        nodeCounter.set(0)
        val sb = StringBuilder()
        try {
            buildNodeTree(root, sb, 0)
        } finally {
            root.recycle()
        }
        return sb.toString()
    }

    fun dumpScreenTreeToLog() {
        val result = ToolRegistry.getScreenInfo()
        if (!result.success) {
            ServiceStatus.appendLog("get_screen_info 실패: ${result.message}")
            Log.w(TAG, "get_screen_info failed: ${result.message}")
            return
        }
        ServiceStatus.appendLog("── get_screen_info ──")
        for (line in result.message.lineSequence().filter { it.isNotBlank() }) {
            ServiceStatus.appendLog(line)
        }
        ServiceStatus.appendLog("── end get_screen_info ──")
        Log.i(TAG, "dumped get_screen_info")
    }

    private fun buildNodeTree(node: AccessibilityNodeInfo, sb: StringBuilder, depth: Int) {
        if (!node.isVisibleToUser) {
            traverseChildren(node, sb, depth)
            return
        }

        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.isEmpty || isFullyOffScreen(bounds)) {
            traverseChildren(node, sb, depth)
            return
        }

        val text = node.text?.toString()?.trim().orEmpty()
        val desc = node.contentDescription?.toString()?.trim().orEmpty()
        val hasText = text.isNotEmpty()
        val hasDesc = desc.isNotEmpty()
        val isInteractive = node.isClickable || node.isScrollable || node.isEditable ||
            node.isCheckable || node.isLongClickable
        val isMeaningful = hasText || hasDesc || isInteractive

        if (isMeaningful) {
            val cx = (bounds.left + bounds.right) / 2
            val cy = (bounds.top + bounds.bottom) / 2
            val nodeId = "n${nodeCounter.incrementAndGet()}"
            nodeCoords[nodeId] = Point(cx, cy)

            val line = StringBuilder()
            repeat(minOf(depth, 4)) { line.append("  ") }
            line.append("[").append(nodeId).append("] ")

            val label = when {
                hasText -> sanitizeLabel(text)
                hasDesc -> sanitizeLabel(desc)
                else -> ""
            }
            if (label.isNotEmpty()) {
                line.append("\"").append(label).append("\"")
            }

            if (node.isClickable) line.append(" tap")
            if (node.isEditable) line.append(" edit")
            if (node.isScrollable) {
                line.append(" scroll")
                nodeScrollRefs[nodeId] = AccessibilityNodeInfo.obtain(node)
            }
            if (node.isCheckable) line.append(if (node.isChecked) " on" else " off")

            sb.append(line).append('\n')
        }

        val childDepth = if (isMeaningful) depth + 1 else depth
        traverseChildren(node, sb, childDepth)
    }

    private fun traverseChildren(node: AccessibilityNodeInfo, sb: StringBuilder, depth: Int) {
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                buildNodeTree(child, sb, depth)
            } finally {
                child.recycle()
            }
        }
    }

    private fun isFullyOffScreen(bounds: Rect): Boolean {
        val dm = resources.displayMetrics
        return bounds.right <= 0 ||
            bounds.bottom <= 0 ||
            bounds.left >= dm.widthPixels ||
            bounds.top >= dm.heightPixels
    }

    private fun sanitizeLabel(raw: String): String {
        val cleaned = raw
            .replace('\n', ' ')
            .replace('\r', ' ')
            .replace('"', '\'')
            .replace(Regex("\\s+"), " ")
            .trim()
        return if (cleaned.length > MAX_LABEL_LEN) {
            cleaned.take(MAX_LABEL_LEN) + ".."
        } else {
            cleaned
        }
    }

    private fun showDumpOverlay() {
        if (overlayDumpButton != null) return

        val dumpButton = Button(this).apply {
            text = "덤프"
            textSize = 12f
            setPadding(24, 12, 24, 12)
            setOnClickListener { dumpScreenTreeToLog() }
        }
        val llmButton = Button(this).apply {
            text = "Finish"
            textSize = 12f
            setPadding(24, 12, 24, 12)
            setOnClickListener {
                if (ReactAgent.shared.requestStop()) {
                    ServiceStatus.appendLog("ReAct: 강제 종료 요청")
                } else {
                    ServiceStatus.appendLog("ReAct: 실행 중이 아님")
                }
            }
        }

        val dumpParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 16
            y = 120
        }
        val llmParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 16
            y = 200
        }

        try {
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            wm.addView(dumpButton, dumpParams)
            wm.addView(llmButton, llmParams)
            overlayDumpButton = dumpButton
            overlayLlmButton = llmButton
            ServiceStatus.appendLog("오버레이 표시됨 (덤프 / Finish)")
            Log.i(TAG, "overlay shown")
        } catch (e: Exception) {
            ServiceStatus.appendLog("오버레이 실패: ${e.message}")
            Log.e(TAG, "failed to show overlay", e)
        }
    }

    private fun removeDumpOverlay() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        overlayDumpButton?.let { button ->
            try {
                wm.removeView(button)
            } catch (e: Exception) {
                Log.w(TAG, "failed to remove dump overlay", e)
            }
        }
        overlayLlmButton?.let { button ->
            try {
                wm.removeView(button)
            } catch (e: Exception) {
                Log.w(TAG, "failed to remove llm overlay", e)
            }
        }
        overlayDumpButton = null
        overlayLlmButton = null
    }

    companion object {
        private const val TAG = "AgentA11y"
        private const val MAX_LABEL_LEN = 40
        private const val GESTURE_TIMEOUT_MS = 2000L
        private const val FOCUS_WAIT_MS = 300L
        private const val EDITABLE_RETRY_COUNT = 5
        private const val SET_TEXT_RETRY_COUNT = 3
        private const val RETRY_SLEEP_MS = 200L
        private const val SCROLL_SWIPE_DELTA_PX = 150
        private const val SCROLL_SWIPE_DURATION_MS = 250L
        const val QUIET_WINDOW_MS = 400L
        const val HARD_TIMEOUT_MS = 2500L
        private const val STABILIZE_POLL_MS = 50L

        @Volatile
        var instance: AgentAccessibilityService? = null
            private set

        fun isBound(): Boolean = instance != null
    }
}

object ServiceStatus {
    private const val PREFS_NAME = "mystar_agent_prefs"
    private const val KEY_OVERLAY_ENABLED = "overlay_enabled"

    private val _connected = MutableStateFlow(AgentAccessibilityService.isBound())
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _overlayEnabled = MutableStateFlow(true)
    val overlayEnabled: StateFlow<Boolean> = _overlayEnabled.asStateFlow()

    private val _pendingGoal = MutableStateFlow(
        "설정 열어서 배터리 항목까지 들어가줘",
    )
    val pendingGoal: StateFlow<String> = _pendingGoal.asStateFlow()

    private val logLines = CopyOnWriteArrayList<String>()
    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _overlayEnabled.value = prefs.getBoolean(KEY_OVERLAY_ENABLED, true)
    }

    fun setConnected(value: Boolean) {
        _connected.value = value
    }

    fun setOverlayEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_OVERLAY_ENABLED, enabled)
            .apply()
        _overlayEnabled.value = enabled
        AgentAccessibilityService.instance?.setOverlayVisible(enabled)
    }

    fun setPendingGoal(goal: String) {
        _pendingGoal.value = goal
    }

    fun appendLog(message: String) {
        val line = "${timeFormat.format(Date())}  $message"
        logLines.add(line)
        while (logLines.size > MAX_LOG_LINES) {
            logLines.removeAt(0)
        }
        _logs.value = logLines.toList()
    }

    fun refreshFromInstance() {
        _connected.value = AgentAccessibilityService.isBound()
    }

    private const val MAX_LOG_LINES = 500
}
