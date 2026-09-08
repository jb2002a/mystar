package com.mystar.agent

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.Manifest
import android.os.Build
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mystar.agent.agent.ReactAgent
import java.util.Locale
import kotlinx.coroutines.launch

/** 말하기 시작 후 최소 청취 시간(ms). 생각하며 말할 때 조기 종료 방지. */
private const val SPEECH_MIN_LISTEN_MS = 15_000

/** 말 끝으로 간주하는 침묵(ms). 기본 1~2초보다 여유 있게. */
private const val SPEECH_SILENCE_MS = 4_000

class MainActivity : ComponentActivity() {
    /** 수명은 AgentTts가 갖는다 — 평가 큐가 이 Activity를 닫아도 낭독은 이어진다. */
    private lateinit var ttsHelper: TtsHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ttsHelper = AgentTts.ensure(this)
        ServiceStatus.init(this)
        ServiceStatus.refreshFromInstance()
        ServiceStatus.appendLog("MainActivity.onCreate")
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AgentHomeScreen(
                        onSpeakFinish = { ttsHelper.speak(it) },
                        onSpeakQuestion = { ttsHelper.speakAwaitingDone(it) },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        ServiceStatus.refreshFromInstance()
        ServiceStatus.refreshHitlMicPermission(this)
    }
}

@Composable
private fun AgentHomeScreen(
    onSpeakFinish: (String) -> Unit,
    onSpeakQuestion: suspend (String) -> Unit,
) {
    val context = LocalContext.current
    val connected by ServiceStatus.connected.collectAsStateWithLifecycle()
    val overlayEnabled by ServiceStatus.overlayEnabled.collectAsStateWithLifecycle()
    val hitlMicGranted by ServiceStatus.hitlMicGranted.collectAsStateWithLifecycle()
    val pendingGoal by ServiceStatus.pendingGoal.collectAsStateWithLifecycle()
    val queueState by EvalQueueRunner.state.collectAsStateWithLifecycle()
    val queueTasks by EvalQueueRunner.tasksText.collectAsStateWithLifecycle()
    val queueRepeat by EvalQueueRunner.repeatText.collectAsStateWithLifecycle()
    val queueCooldown by EvalQueueRunner.cooldownText.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val reactAgent = remember { ReactAgent.shared }
    var reactRunning by remember { mutableStateOf(false) }
    var queueError by remember { mutableStateOf("") }
    val busy = reactRunning || queueState.running

    val hitlPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        ServiceStatus.refreshHitlMicPermission(context)
        val audioGranted = results[Manifest.permission.RECORD_AUDIO] == true
        if (audioGranted) {
            ServiceStatus.appendLog("HITL: 마이크 권한 허용됨")
        } else {
            ServiceStatus.appendLog("HITL: 마이크 권한 거부 — 오버레이에서 텍스트/버튼으로만 답할 수 있음")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val notifGranted = results[Manifest.permission.POST_NOTIFICATIONS] == true
            if (!notifGranted) {
                ServiceStatus.appendLog("HITL: 알림 권한 거부 — 마이크 FGS 알림이 안 보일 수 있음")
            }
        }
    }

    fun requestHitlMicPermission() {
        val permissions = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        hitlPermissionLauncher.launch(permissions.toTypedArray())
    }

    fun runGoal(goal: String) {
        reactRunning = true
        scope.launch {
            try {
                reactAgent.run(
                    goal = goal,
                    onEvent = { msg -> ServiceStatus.appendLog(msg) },
                    onFinishSummary = onSpeakFinish,
                    onSpeakQuestion = onSpeakQuestion,
                )
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
        if (busy) {
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
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, SPEECH_MIN_LISTEN_MS)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, SPEECH_SILENCE_MS)
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                SPEECH_SILENCE_MS,
            )
        }
        try {
            speechLauncher.launch(intent)
        } catch (e: ActivityNotFoundException) {
            ServiceStatus.appendLog("음성: 인식 Activity 없음 — ${e.message}")
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
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "MyStar Agent",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = BuildConfig.VERSION_NAME,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Button(
                onClick = { startSpeechRecognition() },
                enabled = connected && !busy,
                shape = CircleShape,
                modifier = Modifier.size(96.dp),
            ) {
                Text(text = "🎤", style = MaterialTheme.typography.headlineMedium)
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = if (busy) {
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
                    text = if (hitlMicGranted) {
                        "HITL 마이크: 허용됨"
                    } else {
                        "HITL 마이크: 미허용"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (!hitlMicGranted) {
                    Button(
                        onClick = { requestHitlMicPermission() },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("HITL 마이크 권한 허용")
                    }
                }
            }
        }

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
            Text(
                text = if (connected) "접근성 서비스: 연결됨" else "접근성 서비스: 미연결",
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
            ),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "오버레이",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    if (connected) {
                        Text(
                            text = "화면에 노드 박스·덤프·Finish 버튼을 표시합니다",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Switch(
                    checked = overlayEnabled,
                    onCheckedChange = { ServiceStatus.setOverlayEnabled(context, it) },
                    enabled = connected,
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
                    enabled = !busy,
                )
                Button(
                    onClick = { runGoal(pendingGoal) },
                    enabled = connected && !busy && pendingGoal.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (busy) "실행 중…" else "ReAct 루프 실행")
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
                    text = "평가 큐 (자동 연속 실행)",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                if (queueState.running) {
                    Text(
                        text = "진행 ${queueState.done}/${queueState.total} · " +
                            "태스크 ${queueState.taskNo} (${queueState.attempt}/${queueState.repeat}회)" +
                            " — ${queueState.phase}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = queueState.goal,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(
                        onClick = { EvalQueueRunner.stop() },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("큐 중단")
                    }
                } else {
                    Text(
                        text = "한 줄에 목표 하나. 태스크마다 연속으로 반복하고, 런 사이에 최근 앱을 닫고 홈으로 돌아갑니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = queueTasks,
                        onValueChange = { EvalQueueRunner.setTasksText(it) },
                        label = { Text("태스크 목록") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 4,
                        maxLines = 10,
                        enabled = !busy,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = queueRepeat,
                            onValueChange = { EvalQueueRunner.setRepeatText(it) },
                            label = { Text("반복") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            enabled = !busy,
                        )
                        OutlinedTextField(
                            value = queueCooldown,
                            onValueChange = { EvalQueueRunner.setCooldownText(it) },
                            label = { Text("쿨다운(초)") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            enabled = !busy,
                        )
                    }
                    if (queueError.isNotEmpty()) {
                        Text(
                            text = queueError,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Button(
                        onClick = { queueError = EvalQueueRunner.start().orEmpty() },
                        enabled = connected && !busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("큐 시작 (${EvalQueueRunner.plannedTotal()}런)")
                    }
                }
            }
        }
    }
}
