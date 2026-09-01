# MVP 설계

## 1. 목표

시니어가 음성으로 말하면, **안전한 범위 내에서** 휴대폰의 반복 작업을 대신 수행하는 AI 에이전트.

```
갤럭시 음성 인식 → 백엔드 단일 에이전트(Planning + ReAct) → Android 화면 제어
```

### 핵심 원칙

- **LLM은 판단만**, 실제 실행과 최종 안전 판단은 Android 앱이 담당
- 금융·결제·보안 영역은 AI가 조작하지 않음
- 외부 영향이 있는 작업(전화, 메시지 전송 등)은 반드시 사용자 확인 후 실행

---

## 2. MVP 범위

### 지원 작업 (4개)

| 작업 | 예시 |
|------|------|
| 앱 열기 | "카카오톡 열어줘", "지도 켜줘" |
| 전화 걸기 | "딸에게 전화해" — 후보 확인 후 발신 |
| 메시지 전송 | "철수에게 '늦을 것 같아' 보내줘" — 내용 읽어주고 확인 후 전송 |
| 알람/타이머 | "내일 오전 8시에 약 먹는 알람 맞춰줘" |

### 제외 대상

- 금융, 결제, 송금, 주식, 보험
- 비밀번호, OTP, 생체인증, 인증서
- 앱 설치/삭제, 권한·보안 설정 변경
- 사진·파일·연락처 외부 공유
- 이메일 전송, 온라인 구매, 예약 변경
- "모두 삭제" 등 되돌리기 어려운 작업

### 성공 기준

- 핵심 시나리오 완료율 **80% 이상**
- 잘못된 수신자/메시지 전송 **0건**
- 차단 대상 화면 자동 조작 **0건**
- 음성 명령부터 완료까지 평균 **10초 내외**
- 실패 시 사용자가 스스로 안전하게 중단 가능

---

## 3. 아키텍처

```
[사용자 음성]
    ↓
Android SpeechRecognizer
    ↓
[음성 텍스트 + 현재 화면 요약]
    ↓ HTTPS
Backend 단일 Agent
  - 의도 파악
  - 단계 계획
  - ReAct: 관찰 → 다음 도구 호출 결정
    ↓
Android Agent Bridge
  - 정책 검사
  - AccessibilityService / Intent 실행
  - 화면 변화 관찰
    ↑
[실행 결과 + 정제된 UI 상태]
```

### ReAct 루프

백엔드 단일 에이전트가 Planning과 ReAct를 모두 담당한다. Android 앱은 에이전트의 "도구 실행기" 역할.

```
observe → decide_next_action → validate_policy
    ├─ confirm_required → wait_for_user_answer
    ├─ execute_action → observe
    ├─ completed
    └─ failed_or_blocked
```

### 예시 흐름

"딸에게 '병원에 도착했어' 카카오톡 보내줘"

1. STT 결과를 서버로 전송
2. Agent가 `메시지 전송` 의도로 판별
3. 앱이 카카오톡 실행
4. 접근성 정보에서 "딸"을 찾고 대화방 진입
5. 메시지 입력
6. TTS로 확인: "딸에게 '병원에 도착했어'를 보낼까요?"
7. 사용자 "응" 응답 시에만 전송
8. 성공 여부를 음성으로 안내

---

## 4. 기술 스택

### Android 앱

| 구성 | 선택 |
|------|------|
| 언어/UI | Kotlin + Jetpack Compose |
| STT | Android `SpeechRecognizer` |
| TTS | Android `TextToSpeech` |
| 화면 제어 | `AccessibilityService` (요소 탐색/클릭/입력) |
| 공식 API | Android Intent — 전화, 알람, 지도 등 우선 사용 |
| 로컬 저장 | Room 또는 DataStore |
| 민감 정보 | Android Keystore |

### 백엔드

| 구성 | 선택 |
|------|------|
| 런타임 | Python 3.12 |
| API | FastAPI |
| LLM | 공식 SDK (OpenAI / Anthropic / Gemini 중 택1) |
| 검증 | Pydantic — tool 인자·응답 JSON 검증 |
| 세션 | Redis — ReAct 상태, 작업 ID, rate limit |
| 영속 | PostgreSQL — 사용자 설정, 감사 로그 |
| 에이전트 | **LangChain 없이** 공식 SDK + 직접 ReAct 루프 |

> LangGraph는 도구 호출·안전 정책이 안정된 뒤 도입 검토. 초기에는 직접 루프가 단순하고 디버깅이 쉬움.

### 에이전트 루프 (개념)

```python
while state.steps < MAX_STEPS:
    decision = llm.decide(
        user_request=state.request,
        ui_state=state.current_ui,
        allowed_tools=allowed_tools,
    )

    validate_with_pydantic(decision)
    policy_engine.assert_allowed(decision, state)

    if decision.type == "request_confirmation":
        return ask_user(decision.summary)

    result = android_bridge.execute(decision)
    state.current_ui = result.ui_state
```

`PolicyEngine`은 LLM 바깥에 둔다. 모델이 `send_message`를 호출해도 서버·Android 양쪽에서 거절 가능해야 한다.

---

## 5. 에이전트 도구

에이전트에 노출하는 도구는 제한한다. 자유 좌표 클릭·셸 실행은 금지.

```
observe_screen()
open_app(app_id)
search_contact(name)
call_contact(contact_id)
find_element(text, role)
tap(element_id)
enter_text(element_id, text)
scroll(direction)
go_back()
request_confirmation(summary)
speak(message)
```

### 화면 상태 응답 (정제된 UI)

전체 접근성 XML·스크린샷 대신 최소 정보만 전송.

```json
{
  "app": "com.kakao.talk",
  "screen": "chat_list",
  "elements": [
    { "id": "n1", "text": "딸", "role": "button", "clickable": true },
    { "id": "n2", "text": "친구", "role": "tab", "clickable": true }
  ]
}
```

### 에이전트 출력 예시

```json
{
  "intent": "SEND_MESSAGE",
  "steps": [
    { "type": "FIND_CONTACT", "name": "딸" },
    { "type": "COMPOSE_MESSAGE", "text": "지금 출발해" },
    { "type": "REQUIRE_CONFIRMATION" },
    { "type": "SEND" }
  ],
  "risk": "medium"
}
```

---

## 6. 안전 정책

정책은 LLM 프롬프트가 아니라 **Android 앱 코드에서 최종 강제**한다.

### 차단 규칙

- 금융/인증 앱 패키지 및 화면 키워드 차단
- 허용 앱 allowlist: 전화, 메시지, 카카오톡, 지도, 시계 등
- OTP, 비밀번호, 생체인증 화면 진입 시 즉시 중단

### 확인 규칙

- 전화 발신, 메시지 전송, 위치·사진·파일 공유 → 반드시 재확인
- 전송 직전: 수신자 + 본문을 TTS로 읽어줌
- 확인 토큰 없이는 `call_contact`, `send` 실행 불가

### 실행 제한

- 최대 행동 수: 10회
- 동일 화면 반복·요소 미탐색 시 즉시 중단
- 실패 시: "제가 여기까지 했습니다. 화면을 확인해 주세요."

### 이중 방어

| 계층 | 역할 |
|------|------|
| 서버 PolicyEngine | 도구·앱·화면·행동별 허용 여부 |
| Android 정책 레이어 | 서버 요청 재검증, 최종 실행 거부 권한 |

### 감사 로그 (최소 항목)

- 작업 ID, 의도, 도구 이름
- 인자 마스킹본
- 정책 허용/차단 결과
- 사용자 확인 여부
- 성공/실패

음성 원문, 메시지 본문, UI 전체 정보는 기본 저장하지 않음.

---

## 7. UI 원칙 (시니어 친화)

- 화면당 행동 하나만 강조
- 큰 글씨, 고대비
- 상태를 항상 음성으로 설명: "카카오톡을 열고 있어요"
- 취소 발화 전역 처리: "그만", "취소", "아니"
- 확인은 예/아니오만
- 오류 시 기술 메시지 대신 다음 행동 제시

---

## 8. 구현 우선순위

공식 Android API를 먼저, AccessibilityService는 나중.

```
알람 → 전화 → SMS → 카카오톡
```

### 첫 번째 세로 슬라이스

> "딸에게 전화해" → 연락처 후보 확인 → 사용자 '예' → 전화 발신 → 결과 음성 안내

STT, 백엔드 agent, 정책 검사, 확인 UX, Android 실행, TTS를 한 번에 검증.

---

## 9. 리스크

| 리스크 | 대응 |
|--------|------|
| AccessibilityService Play 정책 | 초기는 테스트 단말 직접 배포, 출시 전 정책 검토 |
| 앱 UI 변경 | ReAct 루프 + 실패 시 안전 중단 |
| STT 오인식 | 재질문, 전송 전 TTS 확인 |
| LLM 오작동 | 이중 정책 엔진, 허용 도구만 노출 |
