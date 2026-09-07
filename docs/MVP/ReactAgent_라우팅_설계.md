# ReAct 앱 라우팅 일관성 — 플래너 · 플레이북 · 인텐트 툴

> **상태:** 설계 확정, 미구현 (2026-09-07). attempt1 실측 기반.
> 대상: [M9 완료 기준선](./ReactAgent_포스트MVP.md) 이후. 평가 셋은 [D0](../evaluation/set_D0.md).

---

## 0. 문제 제기

같은 목표를 줘도 라운드마다 경로가 달라진다. 고정 라우팅보다 앱 업데이트에 강하다는 장점은 있으나,
동작이 예측 불가능해 신뢰성이 낮다.

---

## 1. 진단 — 흔들리는 건 "앱 선택"이 아니다

`docs/evaluation/result/attempt1` 트레이스 22개에서 툴 시퀀스를 추출한 결과:

| # | 태스크 | 앱 선택 | 라운드 수 | 비고 |
|---|---|---|---|---|
| 1 | 카톡 전송 | 전부 kakao | 7 / 6 / 7 | `ask_user` 위치만 편차 |
| 2 | 카톡 읽기 | 전부 kakao | 4 / **7** / **3** | 종료 판단 편차 |
| 3 | 날씨 | 전부 naver | 6 / 6 | 안정 |
| 4 | 약국 | **naver / web_search / naver** | 6 / **2** / **15** | 라우팅 분기 ★ |
| 5 | 소요시간 | 전부 web_search | 2 / 2 | 안정 |
| 6 | 설정 글자크기 | 전부 settings | 6 / 4 | |
| 7 | 알람 | 전부 clock | 8 / 9 / 7 | 7-3 **6시로 오설정 후 finish** ★ |
| 8 | 네이버 뉴스 | 전부 naver | 5 / 3 | |
| 9 | 전화 | **앱을 안 엶** | 2 / 2 | 둘 다 실패 ★ |
| 10 | 문자 | **앱을 안 엶** | 3 | 실패 ★ |

**앱 라우팅이 실제로 갈린 건 4번 하나뿐이다.** 나머지는 앱이 100% 일정하고,
앱 내부 경로 길이와 종료 판단만 흔들린다.

### 실패 유형 3가지

**(A) 조회 시도 없이 정보 부재를 단정** — 9·10

```
9-1: ask_user("막내의 전화번호를 말씀해 주세요") → finish("번호를 몰라 전화 못 검")
9-2: ask_user → ask_user (같은 질문 반복, 진전 0)
10 : 메시지 아이콘 탭 → ask_user("전화번호 알려주세요") → finish
```

전화앱을 열어 연락처를 검색한다는 선택지 자체가 없다. 시스템 프롬프트의
`필수 정보가 없으면 ask_user` 규칙에 그대로 빨려 들어갔다.

**(B) 완료 검증 부재** — 7-3. 6시로 설정하고 `finish`. 화면을 읽어 목표와 대조하는 단계가 없다.

**(C) 종료 조건 부재로 인한 방황** — 4-3(15라운드), 2-2(7라운드). `MAX_ROUNDS = 20`이
유일한 안전장치다.

### 결론

태스크→앱 매핑표를 만드는 것은 4번 하나만 고치고 (A)(B)(C)를 그대로 남긴다.
투자 지점이 다르다.

---

## 2. 채택 구조 — 플래너(설정자) + ReAct(실행자)

```
목표 발화
  │
  ├─ [플래너] 1회 LLM 호출 ─ intent 분류 · 슬롯 채우기 · 플레이북 로드
  │     산출: intent, 슬롯값, success_criteria, hitl_points, 노출 툴 서브셋
  │
  ├─ [선질문] 플레이북이 askable로 선언한 미충족 슬롯만, 1턴에 배치
  │
  └─ [ReAct 루프] 좁혀진 툴로 실행
        ├─ 인텐트 툴 (결정론 구간)
        ├─ 실패 시 → UI 툴 노출 (폴백 강등)
        └─ success_criteria 대조 후 finish
```

**플래너는 라우터가 아니다.** 분류하고 대신 실행하는 게(bypass) 아니라, 분류하고
루프를 설정한다. 이 구분이 설계의 핵심이다.

---

## 3. 결정 사항과 근거

### D1. 태스크별 앱 명시 ✗ → capability 라우팅 + 절차 + 완료조건 ○

시스템 프롬프트에 `- 날씨는 네이버앱을 사용한다` 같은 태스크별 규칙을 누적하는 현재 방식은
스케일하지 않는다(토큰, 무관한 규칙 간섭, 미설치 앱에 대한 거짓 지시).

3계층으로 분리한다.

| 층 | 내용 | 해결하는 실패 |
|---|---|---|
| L1 | capability → 앱 라우팅 (데이터, 설치 검증 + 폴백) | 4번 분기 |
| L2 | capability별 절차(플레이북) | (A) 9·10 |
| L3 | `finish` 전 검증 조건 | (B) 7-3, (C) 4-3·2-2 |

L1은 얇게 가고 투자는 L2·L3에 한다. 고정하는 것은 *어느 앱을 여는가*뿐이고,
*앱 안에서 어디를 누르는가*는 트리 기반으로 남으므로 앱 업데이트 강건성은 유지된다.

### D2. 최상위 라우터(bypass) ✗ → 플래너(설정자) ○

**기각 이유 1 — 완전 bypass가 가능한 케이스가 사실상 없다.**

| capability | 인텐트 실행 후 상태 | 루프 복귀 |
|---|---|---|
| 알람 | 설정됨 — 8시가 맞는지 확인 필요 (7-3) | 검증 1라운드 |
| 전화 | 다이얼러만 뜸 | 필수 (통화 탭) |
| 문자 | 본문 채워진 화면 | 필수 (confirm + 전송 탭) |
| 설정 | 근처 화면까지만 | 필수 (1~2탭) |

**기각 이유 2 — 분류기가 LLM이면 결정론이 아니다.** 확률적 선택을 1회로 줄인 것이지
없앤 게 아니다. 4번의 분기가 라우터 안에서 똑같이 일어나되, 이번엔 복구가 불가능하다.

**기각 이유 3 — 복합 발화를 못 쪼갠다.** `"막내한테 전화해보고 안 받으면 문자 남겨줘"`.

**기각 이유 4 — 성능 이득이 이미 회수된다.** 알람 8~9라운드 → 툴+게이팅으로 2라운드.
완전 bypass는 여기서 1라운드(약 2초)를 더 줄일 뿐이다.

결정론은 LLM을 빼서가 아니라 **선택지를 좁혀서** 얻는다(D5).

완전 bypass는 플레이북 플래그(`fast_path: true`)로 **사후 최적화**로 남긴다.
데이터로 안전이 확인되면 켠다. 되돌릴 수 있는 결정으로 유지한다.

### D3. 선질문은 플레이북 선언 기반만 허용

위험한 것은 선질문 자체가 아니라 **LLM이 재량으로 "이건 없네"를 판정하는 것**이다(9-1).
플레이북이 슬롯을 선언하면 그 재량이 사라진다.

```yaml
CALL:
  slots:
    target: {source: app, resolver: contacts_search, required: true}
  # number 슬롯이 존재하지 않음 → 9-1의 질문은 발화 자체가 불가능
```

**원칙: 정보의 부재는 앱에서 조회를 시도해 실패한 뒤에만 확정된다.**

| 선질문 가능 (`source: user`) | 선질문 금지 (`source: app`) |
|---|---|
| 메시지 본문, 알람 시각, 목적지, 동의 여부 | 연락처·전화번호, 대화방, 설정 항목 위치, 검색 결과 |

- 미충족 ∧ `source: user` ∧ `askable` → 라운드 0에서 **1턴 배치** 질문
- 미충족 ∧ `source: app` → 절대 묻지 않음. resolver 실행, 실패 후에만 질문

`ask_upfront`는 플레이북이 askable로 선언한 슬롯 이름의 **enum**으로만 받는다.
자유 문자열이면 9-1로 회귀한다.

**배치의 이득:** 음성 UI에서 진행 중 질문은 비용이 크다. 사용자가 손을 놓은 뒤
3라운드 만에 말을 건다. 라운드 0에 모아 묻고 무중단 완주하는 편이 낫다.
1-1/1-2/1-3의 `ask_user` 위치 편차도 같은 문제다.

**주의:** "채워짐"과 "완전함"은 다르다. `"8시로 알람"`은 오전/오후가 미상이다(7-3 인접).
슬롯마다 `validator`를 두고 통과 못 하면 채워진 것으로 치지 않는다.

### D4. 인텐트/API를 툴로 — 갈림선은 "목표 상태를 인텐트로 표현 가능한가"

알람은 "8시"가 곧 목표 상태 전체다 → 툴. 카톡 대화는 파라미터로 표현이 안 된다 → UI.

3분류로 나뉜다.

| 분류 | capability |
|---|---|
| 툴 단독 완결 | 알람 (+ 검증 1라운드) |
| 툴 점프 → UI 인계 | 설정, 전화, 문자, 지도, 유튜브 |
| UI 전용 | 카카오톡, 네이버 |

**표준 인텐트는 접근성 트리보다 안정적이다.** 깨지는 것은 UI 좌표와 라벨이지 공개 인텐트
계약이 아니다. 처음 우려한 "고정 라우팅은 앱 업데이트에 깨진다"의 반례다.

**경계선: AOSP 표준 인텐트와 공식 문서화된 딥링크만.** 서드파티 비공식 딥링크를 추적하기
시작하면 앱 개수만큼 툴이 늘고, 그건 실제로 조용히 깨진다. 카톡을 UI로 두는 것은
타협이 아니라 올바른 선택이다.

### D5. 툴 게이팅 — 툴 증가 비용을 플레이북이 갚는다

툴이 8개에서 15개가 되면 툴 선택 자체가 새 변동성 원천이 된다(4번 현상).
플래너가 intent를 확정하면 **그 플레이북이 허용한 툴만 스키마에 노출**한다.

```
intent=ALARM  → [set_alarm, show_alarms, finish]                       (3개)
              → set_alarm 실패 시에만 [open_app, tap_node, scroll] 추가
intent=KAKAO  → [open_app, tap_node, input_text, scroll, ask_user, finish]
```

툴 총량이 늘어도 **매 라운드 LLM이 보는 툴 수는 줄어든다.** 분기가 3개면 사실상
결정론이면서 폴백은 살아 있다. 라우터의 결정론과 툴의 폴백을 둘 다 갖는 지점.

---

## 4. 플레이북 스키마 (초안)

```yaml
CALL:
  apps: [{package: com.samsung.android.dialer, fallback: com.android.dialer}]
  tools: [resolve_contact, dial, tap_node, ask_user, finish]
  slots:
    target: {source: app, resolver: contacts_search, required: true}
  steps:
    - 연락처에서 이름을 검색한다
    - 후보가 1개면 다이얼러를 연다. 2개 이상이면 ask_user로 고른다
    - 통화 버튼은 confirm 후 tap_node로 누른다
  success_criteria:
    - 통화 화면이 떴고 상대 이름이 목표와 일치한다
  hitl_points: [{before: 통화 버튼 탭, kind: confirm}]

ALARM:
  tools: [set_alarm, show_alarms, finish]
  slots:
    time:  {source: user, askable: true, validator: time_of_day}
    label: {source: user, askable: false, default: null}
  success_criteria:
    - 알람 목록에 설정한 시각이 보이고 목표 시각과 일치한다
```

**제약**
- 플레이북은 Kotlin 문자열이 아니라 리소스 파일(`assets/playbooks/*.yaml`)로 둔다.
  현재는 프롬프트 한 줄 바꾸려면 재빌드 + 수동 재실행이다.
- `steps`에 node id·구체 UI를 쓰지 않는다. 화면을 보기 전 계획이므로 추상 수준만.
  환각한 UI에 락인된다.
- 플레이북은 권장이지 강제가 아니다. "화면이 다르면 트리를 우선한다"를 명시한다.
- 플래너 출력은 자유 텍스트가 아니라 고정 스키마 tool call로 받는다
  (`tool_choice: required` 패턴 재사용).
- **re-plan 트리거**를 둔다: 연속 툴 실패 2회 / N라운드 진전 없음 / 계획한 패키지 미설치.
  4-3은 계획이 틀렸는데 계속 밀어붙인 것에 가깝다.

---

## 5. 인텐트 카탈로그

### D0 커버리지

| # | 태스크 | 인텐트 | 현재 → 기대 |
|---|---|---|---|
| 7 | 알람 | `AlarmClock.ACTION_SET_ALARM` | 7~9 → **1~2** |
| 9 | 전화 | 연락처 조회 + `ACTION_DIAL` | 실패 → 2~3 |
| 10 | 문자 | 연락처 조회 + `ACTION_SENDTO`+`sms_body` | 실패 → 3 |
| 4 | 약국 | `geo:0,0?q=익산 약국` | 2~15 → 1~2 |
| 6 | 설정 | `Settings.ACTION_DISPLAY_SETTINGS` | 4~6 → 2~3 |
| 5 | 소요시간 | 현행 `web_search` 유지 | 2 |
| 3·8 | 날씨·네이버뉴스 | 없음 | UI 유지 |
| 1·2 | 카톡 | 없음 | UI 유지 |

### 사용 가능 (AOSP 표준)

| 영역 | 액션 / URI | 비고 |
|---|---|---|
| 알람 | `ACTION_SET_ALARM` (HOUR, MINUTES, MESSAGE, DAYS, SKIP_UI) | 권한 `SET_ALARM`은 normal, 런타임 프롬프트 없음 |
| | `ACTION_SET_TIMER`, `ACTION_SHOW_ALARMS` | `SHOW_ALARMS`는 설정 검증용 |
| 전화 | `ACTION_DIAL` (`tel:`) | 권한 불필요, 통화 탭 남음 → HITL 보존 |
| 문자 | `ACTION_SENDTO` (`smsto:`) + `sms_body` | 전송 탭 남음 |
| 연락처 | `Phone.CONTENT_FILTER_URI` 쿼리 | `READ_CONTACTS` 필요, 화면 전환 없음 |
| | `ACTION_PICK` + `Contacts.CONTENT_URI` | **권한 불필요**, 사용자가 직접 선택 |
| 설정 | `ACTION_DISPLAY_SETTINGS`, `ACTION_ACCESSIBILITY_SETTINGS`, `ACTION_WIFI_SETTINGS`, `ACTION_SOUND_SETTINGS`, `ACTION_APPLICATION_DETAILS_SETTINGS` 등 | **글자 크기 전용 액션은 AOSP에 없음** — 화면까지 점프 후 UI 인계 |
| 지도 | `geo:0,0?q=`, `google.navigation:q=` | |
| 기타 | 캘린더 `ACTION_INSERT`, 메일 `mailto:`, 카메라 `ACTION_IMAGE_CAPTURE`, `ACTION_WEB_SEARCH`, `market://` | |

**쓰지 않는 것:** `ACTION_CALL`(즉시 발신 — confirm 우회, `CALL_PHONE` 필요),
`SmsManager` 직접 전송(HITL 우회). rule.md상 safety는 100% 통과 요건이다.

MVP는 연락처를 `ACTION_PICK`으로 시작해 권한 없이 9·10을 살리고,
반복 사용이 확인되면 직접 쿼리로 승격한다.

### 사용 불가

카카오톡 · 네이버 · 유튜브 — 공식 딥링크 없음. 유튜브 `https://www.youtube.com/results?search_query=`는
관행이지 계약이 아니고, `vnd.youtube:`는 비공식. 카카오는 앱키·SDK를 요구한다.

### 착수 전 필수 확인

**1) `<queries>` 추가** — `targetSdk = 35`인데 현재 매니페스트에는 LAUNCHER 인텐트만 있다
(`app/src/main/AndroidManifest.xml:9`). 이 상태로는 `resolveActivity`가 항상 null을 반환해
"이 기기가 이 액션을 지원하나"를 판정할 수 없다. `startActivity` 자체는 동작하지만
폴백 로직에는 액션별 `<intent>` 항목이 필요하다.

**2) 실기기 검증** — 위 목록은 AOSP 표준이나 삼성 기기(A6)에서 실제로 어느 액션이 붙는지는
쏴봐야 안다. 특히 6번은 삼성 설정 구조가 AOSP와 달라 어느 화면에 떨어지는지 눈으로 봐야 한다.

```bash
adb shell am start -a android.intent.action.SET_ALARM \
  --ei android.intent.extra.alarm.HOUR 8 --ei android.intent.extra.alarm.MINUTES 0
adb shell am start -a android.settings.DISPLAY_SETTINGS
adb shell am start -a android.intent.action.DIAL -d tel:01012345678
adb shell am start -a android.intent.action.SENDTO -d smsto:01012345678 -e sms_body "테스트"
adb shell am start -a android.intent.action.VIEW -d "geo:0,0?q=익산 약국"
```

**실제로 뜨는 것만 툴로 만든다.**

---

## 6. 구현 단계

한 번에 넣지 않는다. attempt1이 베이스라인이다.

| Step | 내용 | 기대 효과 | 측정 |
|---|---|---|---|
| 1 | 플레이북 리소스 + 플래너(라우팅 + `success_criteria`) + 툴 게이팅. **선질문 없음** | 4번 라우팅 수렴, 7-3 오설정 차단, 2·8 분산 감소 | attempt2 (D0) |
| 2 | 인텐트 툴 — 알람·설정 먼저, 그다음 `resolve_contact` + 전화·문자 | 알람 라운드 급감·분산 0, 9·10 통과 | attempt3 (D0) |
| 3 | `askable` 슬롯 선질문 + 1턴 배치 | 불완전 발화 처리 | D0-under + D0 회귀 |

**Step 3을 Step 2 뒤에 두는 이유:** resolver가 먼저 있어야 "물어볼 필요 없는 것"이
실제로 해소되고, 그래야 선질문이 남은 진짜 결핍만 건드리는지 측정할 수 있다.
순서를 뒤집으면 두 변경의 효과가 섞인다.

Step 2 내에서 알람·설정을 전화·문자보다 먼저 하는 이유: 표준 인텐트가 확실하고,
라운드가 길고, 7-3이라는 실제 오설정 사례가 있다. 여기서 "라운드 급감 + 분산 0"이
나오면 툴 확대 근거가 데이터로 생긴다.

**부수 작업:** 툴로 옮기는 이득의 절반은 "LLM 없이 검증 가능"인데, 현재 `app/src`에
테스트 디렉터리가 없다. Step 2에 최소 유닛테스트 인프라를 포함한다.

---

## 7. 평가 계획

### D0 확장

`set_D0.md`에 기대 경로 컬럼을 추가한다: **기대 앱 / 기대 라운드 상한 / finish 검증 조건**.

지표는 pass rate와 함께 **라운드 수 분산**을 본다. 목표는 평균 감소가 아니라
**분산 감소**다 — 그것이 사용자가 체감하는 신뢰성이다.

### D0-under (신규) — 선질문 검증용

D0는 완전 발화 셋이라 선질문이 발동조차 하지 않는다. 실제 발화는 슬롯이 빈 채로 온다.

| # | 발화 | 결핍 |
|---|---|---|
| U1 | 친구한테 카톡 보내줘 | body, target 모두 |
| U2 | 알람 맞춰줘 | time |
| U3 | 8시로 알람 맞춰줘 | time 부분 결핍 (오전/오후) |
| U4 | 막내한테 전화 | **결핍 아님** — resolver로 해소 ★ |
| U5 | 기흥이한테 카톡 보내줘 | body만. target은 앱이 해소 ★ |

U4·U5가 핵심이다. 선질문이 `source: app` 슬롯까지 물어버리는 회귀를 잡는 가드레일이다.

지표를 둘로 나눈다.

- **결핍 슬롯 질문율** — 높을수록 좋음
- **해소가능 슬롯 오질문율** — **0이어야 함**

과잉 질문은 새 실패 모드다. "두 번 물어보는 앱"은 "한 번 헤매는 앱"보다 신뢰를 더 잃는다.

---

## 8. 미결 사항

- 삼성 A6에서 실제로 지원되는 인텐트 액션 목록 (§5 검증 스크립트 실행 필요)
- 글자 크기 설정이 삼성에서 디스플레이/접근성 중 어디에 있는지
- 연락처 접근을 `ACTION_PICK`으로 갈지 `READ_CONTACTS` 직접 쿼리로 갈지 (MVP는 전자)
- 플레이북 5종(`CALL`/`SMS`/`ALARM`/`SETTINGS`/`WEB_FACT`) 외 추가 대상
- `fast_path` 도입 시점 — Step 2 데이터를 보고 판단
