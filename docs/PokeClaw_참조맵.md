# PokeClaw 코드 참조 맵 (코딩 에이전트용)

> 목적: 음성 기반 ReAct 폰 에이전트 MVP를 Kotlin으로 구현할 때, **PokeClaw의 해당 소스를 참조 자료로** 쓰기 위한 위치·역할·주의사항 정리. 클론해서 지우는 방식이 아니라, 막힐 때 대조하는 "참고서"로 사용한다.
>
> **실제 소스 코드:** 이 문서가 가리키는 파일들의 원문 코드를 오프라인으로 대조하려면 [PokeClaw_소스_추출.md](./PokeClaw_소스_추출.md) 참조.
>
> **주의:** PokeClaw는 프로덕션 앱이라 안전장치·최적화가 많이 얹혀 있다. MVP는 각 파일에서 **핵심 로직만** 참조하고, 부가 장치(예산·압축·다중 Guard 등)는 나중 로드맵으로 미룬다. 통째로 베끼지 말 것.

---

## 0. 저장소 정보

- **Repo:** `agents-io/PokeClaw` (GitHub, 언어: Kotlin/Java 혼용)
- **소스 루트:** `app/src/main/java/io/agents/pokeclaw/`
- **GitHub 웹 뷰:** `https://github.com/agents-io/PokeClaw/blob/main/<파일경로>`
- **Raw 원본 (에이전트가 fetch할 때):** `https://raw.githubusercontent.com/agents-io/PokeClaw/main/<파일경로>`

아래 표의 경로는 모두 `app/src/main/java/io/agents/pokeclaw/` 를 기준으로 한 상대 경로다. Raw로 받으려면 앞에 위 prefix를 붙인다.

---

## 1. MVP 5개 툴 — 소스 위치

| 툴 이름 | 파일 경로 | MVP 골격의 대응 |
|---|---|---|
| `get_screen_info` | `tool/impl/GetScreenInfoTool.java` | `GetScreenInfoTool` |
| `open_app` | `tool/impl/OpenAppTool.java` | `OpenAppTool` |
| `input_text` | `tool/impl/InputTextTool.java` | `InputTextTool` |
| `tap_node` | `tool/impl/mobile/TapNodeTool.java` | `TapNodeTool` |
| `finish` | `tool/impl/FinishTool.java` | `FinishTool` |

**경로 주의:** `tap_node`만 `impl/mobile/` 하위에 있다. 나머지 4개는 `impl/` 바로 아래. `mobile/` 폴더는 탭·스와이프·롱프레스 등 제스처 계열이 모인 곳(`TapTool`, `SwipeTool`, `LongPressTool`, `FindAndTapTool`, `ScrollToFindTool`).

---

## 2. 툴을 이해하려면 먼저 읽어야 하는 기반 파일

개별 툴만 열면 등록·호출 흐름이 안 보인다. 아래 공통 기반부터 참조한다. 모두 `tool/` 바로 아래.

| 파일 | 역할 | MVP 골격의 대응 |
|---|---|---|
| `tool/BaseTool.kt` | 모든 툴이 상속하는 추상 클래스. 이름·설명·파라미터·`execute()` 형태 정의 | `Tool` 인터페이스 |
| `tool/ToolResult.kt` | 툴 반환 객체(성공/실패 + 데이터) | `ToolResult` |
| `tool/ToolRegistry.kt` | 툴을 이름으로 등록/조회. 루프가 `getTool("tap_node")` 할 때 사용 | `ToolRegistry` |
| `tool/ToolParameter.kt` | 파라미터 스키마 정의(선택 참조) | `ParamSpec` |

---

## 3. 핵심 주의: `get_screen_info`의 실제 로직 위치

**가장 중요한 참조 포인트.** `GetScreenInfoTool.java` 파일 자체는 얇다 — 실제 트리 직렬화 로직은 이 툴이 **위임하는 접근성 서비스**에 있다.

- **진짜 알맹이:** `service/ClawAccessibilityService.java` 의 `getScreenTree()` / `buildNodeTree()`
- 여기서 하는 일: 접근성 트리 재귀 순회 → 안 보이는 노드 스킵 → 의미 있는 노드만 필터 → 중심 좌표 계산 → `n1`,`n2`… id 부여 후 좌표 맵에 저장 → 압축 문자열(`[n3] "텍스트" tap (cx,cy)`) 생성
- **이 서비스가 관찰(read)과 행동(act, 제스처/입력)을 모두 담당**한다. `tap_node`·`input_text`도 결국 이 서비스의 메서드를 호출한다.

즉 5개 툴을 참조하기 전에 `ClawAccessibilityService.java` 를 먼저 읽어야 툴들이 무엇에 위임하는지 이해된다.

| 파일 | 역할 | MVP 골격의 대응 |
|---|---|---|
| `service/ClawAccessibilityService.java` | 트리 직렬화 + 제스처 탭 + 텍스트 입력. 관찰·행동의 물리적 접점 | `AgentAccessibilityService` |

---

## 4. ReAct 루프 — 소스 위치

MVP 루프(`ReactAgent.run()`)를 구현할 때 참조할 원본.

| 파일 | 역할 | MVP 골격의 대응 |
|---|---|---|
| `agent/DefaultAgentService.kt` | ReAct 메인 루프(`while (iterations < maxIterations)`): observe→reason→act→되먹임, 도구 없는 텍스트면 종료 | `ReactAgent` |

**루프 코어는 MVP 골격과 동일**하다(while + LLM 호출 + 툴 실행 + 히스토리 축적 + 종료 판정). PokeClaw가 긴 이유는 아래 부가 장치들이 while 안에 얹혀서다. **MVP엔 지금 넣지 말고, 코어가 돈 뒤 필요 순서대로 참조해 추가**한다.

같은 `agent/` 폴더의 참조용 부가 장치:

| 파일 | 역할 | MVP 도입 우선순위 |
|---|---|---|
| `agent/DefaultAgentService.kt` 내 `chatWithRetry` | LLM API 재시도 | 1순위 (네트워크 끊김 대비) |
| `agent/StuckDetector.kt` + 루프 내 `RoundFingerprint`(화면해시+도구호출) | 반복 함정 감지 | 2순위 (실기기 테스트 시 필요) |
| `agent/DirectDeviceDataGuard.kt` / `agent/InAppSearchGuard.kt` / `agent/EmailComposeGuard.kt` | 성급한 `finish` 차단(환각성 조기 종료 방지) | 3순위 (되돌릴 수 없는 행동 전) |
| `agent/TaskBudget.kt` / `agent/TokenMonitor.kt` | 토큰·비용 상한 | 4순위 (비용이 아파질 때) |
| `DefaultAgentService.kt` 내 `compressHistoryForSend` | 오래된 화면 트리 압축(토큰 절약, 최근 N라운드는 보호) | 4순위 |
| `agent/PlaybookManager.kt` / `agent/skill/*` | 앱별 플레이북(스킬)을 프롬프트에 주입 | 확장 (planning 단계) |
| `agent/PipelineRouter.kt` / `agent/TaskClassifier.kt` | 단순 명령은 LLM 없이 처리하는 3단계 라우터 | 확장 |
| `agent/llm/OpenAiLlmClient.kt` / `agent/llm/AnthropicLlmClient.kt` | 클라우드 LLM tool-calling 클라이언트 구현 | 참조 (MVP `CloudLlmClient` 작성 시 대조) |

---

## 5. 코딩 에이전트를 위한 참조 순서

에이전트가 이 저장소를 참조해 MVP를 구현할 때 권장 순서:

1. `tool/BaseTool.kt` → 툴 공통 틀 파악
2. `tool/impl/FinishTool.java` → 가장 단순한 툴로 틀이 채워지는 방식 확인
3. `service/ClawAccessibilityService.java` → 트리 직렬화·제스처·입력의 실제 로직 (**최우선 이해 대상**)
4. `tool/impl/GetScreenInfoTool.java` → 위 서비스에 어떻게 위임하는지
5. `tool/impl/mobile/TapNodeTool.java`, `tool/impl/InputTextTool.java`, `tool/impl/OpenAppTool.java` → 나머지 툴(훑고 이식)
6. `agent/DefaultAgentService.kt` → ReAct 루프 코어 (부가 장치는 §4 우선순위대로 나중에)
7. `agent/llm/OpenAiLlmClient.kt` 또는 `AnthropicLlmClient.kt` → `CloudLlmClient` 구현 대조

---

## 6. 이식 시 반드시 지킬 불변 규칙 (참조하되 놓치기 쉬운 것)

- **node id는 매 `get_screen_info` 호출마다 재발급**된다. 캐싱 금지. 좌표는 그때그때 새로 만든 맵에서 조회.
- **좌표는 `get_screen_info`가 미리 계산해 맵에 저장**해두고, `tap_node`는 id로 그 좌표를 꺼내 제스처만 실행한다. 모델은 좌표를 직접 다루지 않는다.
- **`input_text` 전에 입력 필드를 `tap_node`로 포커스**해야 텍스트가 들어간다. (시스템 프롬프트 규칙으로 강제)
- **접근성 서비스는 시스템이 생성**한다(`new` 불가). 사용자가 설정 > 접근성에서 켜야 하며, `onServiceConnected`에서 인스턴스를 static으로 잡아 다른 코드가 접근한다.
- **되돌릴 수 없는 행동(메시지 전송 등) 앞에는 사용자 확인 스텝**을 둔다. PokeClaw는 finish Guard로, MVP는 최소한 전송 직전 확인 다이얼로그로.
- **PokeClaw 라이선스·저작권을 확인**하고, 참조와 직접 이식의 경계를 지킨다. 로직 아이디어를 참고해 자기 코드로 재작성하는 것과 파일을 그대로 복사하는 것은 다르다.

---

## 7. 빠른 경로 인덱스 (복붙용)

```
# 5개 툴
tool/impl/GetScreenInfoTool.java
tool/impl/OpenAppTool.java
tool/impl/InputTextTool.java
tool/impl/mobile/TapNodeTool.java
tool/impl/FinishTool.java

# 기반
tool/BaseTool.kt
tool/ToolResult.kt
tool/ToolRegistry.kt
tool/ToolParameter.kt

# 접근성 서비스 (get_screen_info/tap/input의 실제 로직)
service/ClawAccessibilityService.java

# ReAct 루프 + 부가 장치
agent/DefaultAgentService.kt
agent/StuckDetector.kt
agent/TaskBudget.kt
agent/TokenMonitor.kt
agent/PlaybookManager.kt
agent/PipelineRouter.kt
agent/llm/OpenAiLlmClient.kt
agent/llm/AnthropicLlmClient.kt

# raw prefix
https://raw.githubusercontent.com/agents-io/PokeClaw/main/app/src/main/java/io/agents/pokeclaw/
```
