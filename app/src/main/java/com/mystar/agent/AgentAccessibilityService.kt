package com.mystar.agent

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * M0: 접근성 서비스 등록·바인딩만 담당.
 * 트리 읽기/탭/입력은 M1·M2에서 추가한다.
 */
class AgentAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        ServiceStatus.setConnected(true)
        ServiceStatus.appendLog("onServiceConnected: instance bound")
        Log.i(TAG, "onServiceConnected: instance bound")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // M0: ignore events. Window-change handling comes later.
    }

    override fun onInterrupt() {
        ServiceStatus.appendLog("onInterrupt")
        Log.w(TAG, "onInterrupt")
    }

    override fun onDestroy() {
        if (instance === this) {
            instance = null
            ServiceStatus.setConnected(false)
            ServiceStatus.appendLog("onDestroy: instance cleared")
            Log.i(TAG, "onDestroy: instance cleared")
        }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "AgentA11y"

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
        while (logLines.size > 100) {
            logLines.removeAt(0)
        }
        _logs.value = logLines.toList()
    }

    fun refreshFromInstance() {
        _connected.value = AgentAccessibilityService.isBound()
    }
}
