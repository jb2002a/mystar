package com.mystar.agent

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mystar.agent.agent.ReactAgent
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ServiceStatus.refreshFromInstance()
        ServiceStatus.appendLog("MainActivity.onCreate")
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AgentHomeScreen()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        ServiceStatus.refreshFromInstance()
    }
}

@Composable
private fun AgentHomeScreen() {
    val context = LocalContext.current
    val connected by ServiceStatus.connected.collectAsStateWithLifecycle()
    val logs by ServiceStatus.logs.collectAsStateWithLifecycle()
    val pendingGoal by ServiceStatus.pendingGoal.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val reactAgent = remember { ReactAgent.shared }
    var param1 by remember { mutableStateOf("n3") }
    var param2 by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var reactRunning by remember { mutableStateOf(false) }

    fun runGoal(goal: String) {
        reactRunning = true
        scope.launch {
            try {
                reactAgent.run(goal) { msg -> ServiceStatus.appendLog(msg) }
            } finally {
                reactRunning = false
            }
        }
    }

    val speechLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) {
            ServiceStatus.appendLog("음성: 취소 또는 실패 (resultCode=${result.resultCode})")
            return@rememberLauncherForActivityResult
        }
        val spoken = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
            ?.trim()
            .orEmpty()
        if (spoken.isEmpty()) {
            ServiceStatus.appendLog("음성: 인식 결과가 비어 있음")
            return@rememberLauncherForActivityResult
        }
        ServiceStatus.setPendingGoal(spoken)
        ServiceStatus.appendLog("음성 인식: $spoken")
        if (!connected) {
            ServiceStatus.appendLog("음성: 접근성 서비스 미연결 — 실행 생략")
            return@rememberLauncherForActivityResult
        }
        if (reactRunning) {
            ServiceStatus.appendLog("음성: 이미 실행 중 — 실행 생략")
            return@rememberLauncherForActivityResult
        }
        runGoal(spoken)
    }

    fun startSpeechRecognition() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            ServiceStatus.appendLog("음성: 인식 제공자 없음")
            return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.KOREA.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PROMPT, "목표를 말씀해 주세요")
        }
        try {
            speechLauncher.launch(intent)
        } catch (e: ActivityNotFoundException) {
            ServiceStatus.appendLog("음성: 인식 Activity 없음 — ${e.message}")
        }
    }

    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            listState.animateScrollToItem(logs.lastIndex)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars)
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "MyStar Agent",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "M5 — 음성 + 자동 전송",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (connected) {
                    Color(0xFF1B5E20)
                } else {
                    Color(0xFF4E342E)
                },
            ),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = if (connected) "접근성 서비스: 연결됨" else "접근성 서비스: 미연결",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (connected) {
                        "목표를 입력한 뒤 \"ReAct 루프 실행\" 또는 오버레이의 \"ReAct\"로 실행하세요.\n앱 전환이 필요한 목표는 open_app부터 자동으로 시작합니다."
                    } else {
                        "설정 > 접근성에서 \"MyStar Agent\"를 켜세요"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
            ),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "ReAct 루프 실행",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                OutlinedTextField(
                    value = pendingGoal,
                    onValueChange = { ServiceStatus.setPendingGoal(it) },
                    label = { Text("목표") },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !reactRunning,
                )
                Button(
                    onClick = { runGoal(pendingGoal) },
                    enabled = connected && !reactRunning && pendingGoal.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (reactRunning) "실행 중…" else "ReAct 루프 실행")
                }
                Text(
                    text = "데모: 마이크 또는 텍스트로 \"카카오톡에서 딸한테 사랑한다고 보내줘\"",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
            ),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "수동 테스트 (tool 호출)",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                OutlinedTextField(
                    value = param1,
                    onValueChange = { param1 = it },
                    label = { Text("param1 · node id") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = connected,
                )
                OutlinedTextField(
                    value = param2,
                    onValueChange = { param2 = it },
                    label = { Text("param2 · text (input_text용)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = connected,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = {
                            val id = param1
                            scope.launch(Dispatchers.Default) {
                                AgentAccessibilityService.instance?.tapNode(id)
                                    ?: ServiceStatus.appendLog("탭: 서비스 미연결")
                            }
                        },
                        enabled = connected,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("탭 (tap_node)")
                    }
                    Button(
                        onClick = {
                            val id = param1
                            val text = param2
                            scope.launch(Dispatchers.Default) {
                                AgentAccessibilityService.instance?.inputText(text, id)
                                    ?: ServiceStatus.appendLog("입력: 서비스 미연결")
                            }
                        },
                        enabled = connected,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("입력 (input_text)")
                    }
                }
                Button(
                    onClick = {
                        scope.launch(Dispatchers.Default) {
                            AgentAccessibilityService.instance?.dumpScreenTreeToLog()
                                ?: ServiceStatus.appendLog("확인: 서비스 미연결")
                        }
                    },
                    enabled = connected,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("확인 (화면 덤프)")
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
            ),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "테스트용 입력 필드",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "이 화면을 확인으로 덤프해 node id를 얻고, 그 id로 탭/입력해 실제 포커스·타이핑이 되는지 검증하세요.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = message,
                    onValueChange = { message = it },
                    label = { Text("메시지") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Button(
                onClick = { startSpeechRecognition() },
                enabled = connected && !reactRunning,
                shape = CircleShape,
                modifier = Modifier.size(96.dp),
            ) {
                Text(text = "🎤", style = MaterialTheme.typography.headlineMedium)
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = if (reactRunning) {
                    "실행 중…"
                } else if (!connected) {
                    "마이크 (접근성 연결 필요)"
                } else {
                    "마이크 — 목표를 말하면 ReAct 실행"
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Text(
            text = "이벤트 로그",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 240.dp, max = 360.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
            ),
        ) {
            if (logs.isEmpty()) {
                Text(
                    text = "아직 로그 없음",
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(logs) { line ->
                        Text(
                            text = line,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
                    shape = MaterialTheme.shapes.small,
                )
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ConfigRow(
                label = "API 키",
                value = if (BuildConfig.LLM_API_KEY.isBlank()) {
                    "미설정"
                } else {
                    "설정됨 (${BuildConfig.LLM_API_KEY.take(4)}…)"
                },
            )
            ConfigRow(
                label = "BASE_URL",
                value = BuildConfig.LLM_BASE_URL.ifBlank { "미설정" },
            )
            ConfigRow(
                label = "MODEL",
                value = BuildConfig.LLM_MODEL.ifBlank { "미설정" },
            )
        }
    }
}

@Composable
private fun ConfigRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
