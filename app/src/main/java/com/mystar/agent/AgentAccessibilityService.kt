package com.mystar.agent

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
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
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * M1: 접근성 트리 관찰(getScreenTree) + 덤프 오버레이.
 * 탭/입력은 M2에서 추가한다.
 */
class AgentAccessibilityService : AccessibilityService() {

    private val nodeCoords = ConcurrentHashMap<String, Point>()
    private val nodeCounter = AtomicInteger(0)

    private var overlayButton: Button? = null
    private var overlayParams: WindowManager.LayoutParams? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        ServiceStatus.setConnected(true)
        ServiceStatus.appendLog("onServiceConnected: instance bound")
        Log.i(TAG, "onServiceConnected: instance bound")
        showDumpOverlay()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Window-change handling comes later (M4 timing).
    }

    override fun onInterrupt() {
        ServiceStatus.appendLog("onInterrupt")
        Log.w(TAG, "onInterrupt")
    }

    override fun onDestroy() {
        removeDumpOverlay()
        if (instance === this) {
            instance = null
            ServiceStatus.setConnected(false)
            ServiceStatus.appendLog("onDestroy: instance cleared")
            Log.i(TAG, "onDestroy: instance cleared")
        }
        super.onDestroy()
    }

    /** M2용: 마지막 getScreenTree() 스냅샷의 node id → 중심 좌표. */
    fun getNodeCoordinates(nodeId: String): Point? = nodeCoords[nodeId]

    /**
     * 현재 활성 창의 의미 있는 노드만 압축 텍스트로 직렬화한다.
     * 호출마다 node id를 재발급하고 nodeCoords를 갱신한다.
     * root가 없으면 null.
     */
    fun getScreenTree(): String? {
        val root = rootInActiveWindow ?: return null
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
        val tree = getScreenTree()
        if (tree == null) {
            ServiceStatus.appendLog("getScreenTree: root null (활성 창 없음 / 시스템 다이얼로그?)")
            Log.w(TAG, "getScreenTree: root null")
            return
        }
        val lines = tree.lines().filter { it.isNotBlank() }
        ServiceStatus.appendLog("── screen tree (${lines.size} nodes, ${nodeCoords.size} coords) ──")
        for (line in lines) {
            ServiceStatus.appendLog(line)
        }
        ServiceStatus.appendLog("── end screen tree ──")
        Log.i(TAG, "dumped screen tree: ${lines.size} lines")
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
            if (node.isScrollable) line.append(" scroll")
            if (node.isCheckable) line.append(if (node.isChecked) " on" else " off")
            line.append(" (").append(cx).append(",").append(cy).append(")")

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
        if (overlayButton != null) return

        val button = Button(this).apply {
            text = "덤프"
            textSize = 12f
            setPadding(24, 12, 24, 12)
            setOnClickListener { dumpScreenTreeToLog() }
        }

        val params = WindowManager.LayoutParams(
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

        try {
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            wm.addView(button, params)
            overlayButton = button
            overlayParams = params
            ServiceStatus.appendLog("덤프 오버레이 표시됨 (설정 화면에서 '덤프' 탭)")
            Log.i(TAG, "dump overlay shown")
        } catch (e: Exception) {
            ServiceStatus.appendLog("덤프 오버레이 실패: ${e.message}")
            Log.e(TAG, "failed to show dump overlay", e)
        }
    }

    private fun removeDumpOverlay() {
        val button = overlayButton ?: return
        try {
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            wm.removeView(button)
        } catch (e: Exception) {
            Log.w(TAG, "failed to remove dump overlay", e)
        }
        overlayButton = null
        overlayParams = null
    }

    companion object {
        private const val TAG = "AgentA11y"
        private const val MAX_LABEL_LEN = 40

        @Volatile
        var instance: AgentAccessibilityService? = null
            private set

        fun isBound(): Boolean = instance != null
    }
}

object ServiceStatus {
    private val _connected = MutableStateFlow(AgentAccessibilityService.isBound())
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val logLines = CopyOnWriteArrayList<String>()
    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    fun setConnected(value: Boolean) {
        _connected.value = value
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
