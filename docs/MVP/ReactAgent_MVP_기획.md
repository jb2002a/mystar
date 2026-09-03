# 음성 기반 ReAct 폰 에이전트 — MVP 기획

> 음성으로 목적을 말하면(예: "카카오톡에서 딸한테 사랑한다고 보내줘), ReAct 루프가 접근성 트리를 읽어가며 앱을 조작해 목표를 달성하는 안드로이드 앱. PokeClaw 아키텍처를 클라우드 LLM 기준으로 최소화한 버전.

**상태:** ✅ MVP 완료 (2026-09-03, M5) — 음성 명령 → ReAct 루프 → 제네릭 도구 4개로 앱 자동 조작. 다음 구현은 [포스트 MVP](./ReactAgent_포스트MVP.md) (speak · back · scroll · ask_user · 다단계 시나리오).

---

## 1. 목표와 스코프

**한 문장 목표:** 음성 명령 → 클라우드 LLM의 ReAct 루프 → 접근성 서비스로 앱 자동 조작.

MVP는 **하나의 ReAct 루프 + 최소 도구 5개 + 음성 입력**만 구현한다. PokeClaw의 무거운 부분(로컬 모델·스킬 시스템·3단계 라우터·다국어·외부 자동화 API)은 전부 제외한다.

| 구분 | MVP 포함 | 제외 (이후 확장) |
|---|---|---|
| 추론 | 클라우드 LLM native tool calling | 온디바이스 Gemma / LiteRT-LM |
| 실행 | 제네릭 도구 5개 | 스킬(재사용 워크플로우) 시스템 |
| 라우팅 | 무조건 풀 ReAct 루프 | LLM 0회 결정론 경로 / 스킬 매칭 |
| 입력 | 시스템 음성인식 다이얼로그 | 인앱 실시간 인식 / 웨이크워드 |
| 트리거 | 앱 내 마이크 버튼 | Tasker·MacroDroid 인텐트 API |

**클라우드부터 시작하는 이유:** 이 프로젝트의 진짜 난관은 "접근성 트리 읽기 ↔ 도구 실행 ↔ 루프 안정화"이지 모델이 아니다. 온디바이스 모델 브링업(GPU 폴백·세션·다운로드)까지 동시에 싸우면 MVP가 끝나지 않는다. 도구 레이어를 generic하게 짜두면, 이후 로컬 모델은 LLM 클라이언트 구현체 하나만 교체하면 된다.

---

## 2. 동작 구조 (ReAct 루프)

핵심은 관찰–추론–행동의 반복이다. 화면을 **스크린샷이 아니라 접근성 트리 텍스트**로 관찰한다.

```
음성 입력 → 목표 텍스트
        │
        ▼
  ┌─────────────────────────────────────┐
  │  ReAct 루프 (최대 N라운드)           │
  │                                     │
  │  1) observe : get_screen_info로     │
  │               현재 UI 트리를 텍스트로 │
  │  2) reason  : LLM이 다음 도구 선택   │
  │               (native tool calling) │
  │  3) act     : 도구 실행 → 결과를     │
  │               관찰로 되먹임          │
  │                                     │
  │  finish 호출 시 종료                 │
  └─────────────────────────────────────┘
        │
        ▼
  접근성 서비스가 실제 탭·입력 수행
```

- **관찰(observation)** = 접근성 트리를 `[n3] "텍스트" tap edit (cx,cy)` 형식으로 압축한 문자열. node id는 매 호출마다 재발급되므로 캐싱 금지.
- **추론(reason)** = 매 라운드 현재 화면을 컨텍스트에 넣고 LLM이 도구 하나를 선택.
- **행동(act)** = 선택된 도구 실행 후 결과 문자열을 다시 대화에 추가 → 다음 라운드 관찰이 됨.

---

## 3. 기술 스택

### 앱 기반
- **언어:** Kotlin (신규 코드 단일 권장)
- **최소 SDK:** API 24 (Android 7.0) — 제스처 탭 `dispatchGesture`가 API 24부터
- **빌드:** Gradle (Kotlin DSL, `.kts`)
- **UI:** 최소한만 — 마이크 버튼 1개 + 진행 로그. 화면이 하나뿐이라 Compose/XML 무엇이든 무방

### 핵심 3요소 (프레임워크 내장, 라이브러리 불필요)
- **AccessibilityService** — 트리 읽기(`getRootInActiveWindow`)와 행동(`dispatchGesture`, `performAction`)의 유일한 통로. 관찰과 행동을 한 서비스가 담당
- **음성 입력** — `RecognizerIntent.ACTION_RECOGNIZE_SPEECH`. 별도 SDK·API 키 불필요, `RECORD_AUDIO` 권한도 시스템이 처리. 한국어는 `EXTRA_LANGUAGE = Locale.KOREA`
- **Kotlin Coroutines** — ReAct 루프가 `suspend`. 도구 실행 대기와 네트워크 호출을 순차로 엮는 데 필수

### 클라우드 LLM 연동 (직접 구현할 부분)
- **HTTP:** OkHttp (Retrofit은 MVP엔 과함)
- **JSON:** kotlinx.serialization 또는 Moshi — 도구 스키마 직렬화 + `tool_calls` 파싱
- **모델 API:** tool calling 지원 모델 아무거나 (OpenAI `/chat/completions`, Anthropic `/v1/messages`, Gemini 등). `LlmClient` 매핑만 맞추면 루프는 동일
- **API 키:** `local.properties` → `BuildConfig` (하드코딩·git 커밋 금지. 배포 시 암호화 저장소로)

### 의존성 요약
```
Kotlin + Coroutines
AccessibilityService     (프레임워크 내장)
RecognizerIntent         (프레임워크 내장, 음성)
OkHttp + kotlinx.serialization  (LLM 연동)
클라우드 LLM API 키 1개    (tool calling 지원)
```
외부 라이브러리는 **OkHttp · kotlinx.serialization · coroutines** 세 개면 충분. 나머지는 전부 안드로이드 기본 제공.

---

## 4. 도구 세트 (제네릭 도구 5개)

이 5개만으로 "카톡에서 딸한테 사랑한다 보내기"를 루프가 스스로 풀 수 있다. 앱별 하드코딩 없음.

| 도구 | 역할 | 파라미터 |
|---|---|---|
| `get_screen_info` | 접근성 트리 → 텍스트 (observation) | 없음 |
| `open_app` | 패키지명으로 앱 실행 (`com.kakao.talk`) | `package` |
| `tap_node` | node id로 탭 (좌표는 서비스가 저장) | `node_id` |
| `input_text` | node id로 입력창을 탭(포커스)한 뒤 타이핑 | `text`, `node_id` |
| `finish` | 완료 선언 → 루프 종료 | `summary` (선택) |

**예상 흐름:** `open_app`(카톡) → `get_screen_info` → 딸 채팅 탭(`tap_node`) → `input_text`(사랑한다, 입력창 node) → 전송 버튼 탭 → `finish`. 화면 전환이 많아 라운드 5~14회 예상.

---

## 5. 구현 순서

1. ✅ **접근성 서비스 골격** — `getScreenTree()` 직렬화 + `tapNode()` 제스처 + `inputText()`. 설정 > 접근성에서 수동으로 켜야 동작.
2. ✅ **도구 레지스트리** — 위 인터페이스 구현. LLM 노출 도구는 4개(`open_app` / `tap_node` / `input_text` / `finish`). 화면 관찰은 행동 후 자동 tool_result.
3. ✅ **`CloudLlmClient`** — 도구 `parameters`를 JSON schema로 직렬화해 API `tools` 필드에 전달, 응답 `tool_calls[0]`를 `ToolCall`로 파싱. `Dispatchers.IO`에서 호출.
4. ✅ **ReAct 루프** — observe → reason → act 반복, `finish`/최대 라운드에서 종료.
5. ✅ **음성 입력 연결** — `RecognizerIntent` 결과 텍스트를 `run(goal = text)`로 전달.
6. ✅ **목표 시나리오 검증** — 카톡 전에 "설정 열어 배터리 % 읽기"로 루프를 안정화한 뒤, 음성으로 카톡 전송까지 수행 (M5).

---

## 6. 함정 (디버깅 시간 대부분이 여기서 발생)

- **타이밍이 최대 실패 원인.** 앱이 뜨기 전 `get_screen_info`를 부르면 이전 화면을 읽는다. MVP는 고정 `delay()`로 시작하되, 이후 "화면 전환 이벤트(window state change) 감지 후 진행"으로 개선.
- **접근성 권한 필수.** 사용자가 설정에서 직접 켜지 않으면 `getRootInActiveWindow()`가 null → 트리를 못 읽는다. "설정에 켜짐 / 실제 바인딩됨 / 지금 실행 가능"을 구분 관리하면 좋다.
- **접근성 정보가 부실한 앱 주의.** 커스텀 캔버스·게임·일부 웹뷰는 트리가 비거나 빈약. 이런 경우 스크린샷 기반 보조 경로가 필요할 수 있다(MVP 범위 밖).
- **카톡 한국어 UI는 오히려 유리.** "친구·채팅·전송" 텍스트가 트리에 그대로 들어와 매칭이 쉽다. 단 "딸" 연락처는 검색이 필요할 수 있으니 시스템 프롬프트에 힌트를 준다.
- **되돌릴 수 없는 행동에 안전장치.** 실제 사람에게 실제 메시지를 보낸다. MVP는 확인 없이 전송한다. 오발송 차단용 HITL은 M9 `ask_user`.
- **네트워크는 반드시 백그라운드 스레드.** 메인 스레드에서 LLM을 호출하면 앱이 죽는다. 코루틴 `Dispatchers.IO` 사용.

---

## 7. 이후 확장 경로

### 바로 다음 (포스트 MVP)

상세 설계·DoD·함정은 [포스트 MVP 로드맵](./ReactAgent_포스트MVP.md). 구현 순서는 M6→M10.

| 단계 | 항목 | 한 줄 |
|---|---|---|
| M6 | `speak` | `finish`의 summary를 TTS로 읽어준다. 별도 LLM 도구 아님. |
| M7 | `back` | `GLOBAL_ACTION_BACK`. 잘못된 화면에서 복구. |
| M8 | `scroll` | 트리의 `scroll` 노드를 한 칸 굴린다. 목록·시간표. |
| M9 | `ask_user` | 정보 부족 질문 + 전송·결제 등 위험 탭 직전 HITL. |
| M10 | 다단계 테스트 | 기차 예매·회원가입. 실결제는 하지 않음. |

### 그 다음 (아직 설계하지 않음)

- **로컬 모델 전환:** `LlmClient` 구현체만 LiteRT-LM 기반으로 교체. 도구·루프·서비스는 그대로 재사용.
- **스킬(플레이북):** 자주 쓰는 흐름(자동답장 등)을 도구 순서로 묶어 약한 모델의 신뢰성 확보.
- **라우터:** 단순 명령은 LLM 없이 즉시 실행해 토큰·라운드 절약.
- **외부 트리거:** Tasker·MacroDroid 인텐트로 태스크 시작.
