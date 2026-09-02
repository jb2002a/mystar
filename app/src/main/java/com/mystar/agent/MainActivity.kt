package com.mystar.agent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    val connected by ServiceStatus.connected.collectAsStateWithLifecycle()
    val logs by ServiceStatus.logs.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var nodeId by remember { mutableStateOf("n3") }
    var inputText by remember { mutableStateOf("") }

    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            listState.animateScrollToItem(logs.lastIndex)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "MyStar Agent",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "M2 — 행동: 탭 / 입력",
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
                        "설정 앱에서 '덤프'로 id를 확인한 뒤,\n탭은 id만, 입력은 id+텍스트로 선택·입력을 한 번에 테스트하세요."
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
                    text = "수동 테스트",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = nodeId,
                        onValueChange = { nodeId = it },
                        label = { Text("node id") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        enabled = connected,
                    )
                    Button(
                        onClick = {
                            val id = nodeId
                            scope.launch(Dispatchers.Default) {
                                AgentAccessibilityService.instance?.tapNode(id)
                                    ?: ServiceStatus.appendLog("탭: 서비스 미연결")
                            }
                        },
                        enabled = connected,
                    ) {
                        Text("탭")
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        label = { Text("입력 텍스트") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        enabled = connected,
                    )
                    Button(
                        onClick = {
                            val id = nodeId
                            val text = inputText
                            scope.launch(Dispatchers.Default) {
                                AgentAccessibilityService.instance?.inputText(text, id)
                                    ?: ServiceStatus.appendLog("입력: 서비스 미연결")
                            }
                        },
                        enabled = connected,
                    ) {
                        Text("입력")
                    }
                }
                Button(
                    onClick = {
                        val service = AgentAccessibilityService.instance
                        if (service == null) {
                            ServiceStatus.appendLog("탭 후 덤프: 서비스 미연결")
                            return@Button
                        }
                        val id = nodeId
                        scope.launch {
                            withContext(Dispatchers.Default) {
                                service.tapNode(id)
                            }
                            delay(600)
                            withContext(Dispatchers.Default) {
                                service.dumpScreenTreeToLog()
                            }
                        }
                    },
                    enabled = connected,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("탭 후 덤프")
                }
            }
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Button(
                onClick = { /* M5: RecognizerIntent 연결 */ },
                enabled = false,
                shape = CircleShape,
                modifier = Modifier.size(96.dp),
                colors = ButtonDefaults.buttonColors(
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            ) {
                Text(text = "🎤", style = MaterialTheme.typography.headlineMedium)
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "마이크 (M5에서 활성화)",
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
                .weight(1f),
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

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
                    shape = MaterialTheme.shapes.small,
                )
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "API 키",
                style = MaterialTheme.typography.labelMedium,
            )
            Text(
                text = if (BuildConfig.LLM_API_KEY.isBlank()) {
                    "미설정 (local.properties)"
                } else {
                    "설정됨 (${BuildConfig.LLM_API_KEY.take(4)}…)"
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
