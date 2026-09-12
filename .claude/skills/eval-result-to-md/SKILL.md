---
name: eval-result-to-md
description: docs/evaluation/result/device/files/기록용/초기AB테스트/<model>/ 아래 eval json 결과들을 양식.txt 형식의 md 리포트로 정리할 때 사용. "eval 결과 정리해줘", "<model> 결과 md로 만들어줘" 같은 요청에 사용.
---

# eval 결과 → md 정리

`docs/evaluation/result/device/files/기록용/초기AB테스트/<model>/` 아래 eval json 파일들을 읽어
`docs/evaluation/result/device/files/eval/양식.txt` 형식에 맞는 md 리포트를 만든다.

## 실행

```
python .claude/skills/eval-result-to-md/build_md.py <model_dir_name>
```

- `<model_dir_name>`: `기록용/초기AB테스트/` 바로 아래 폴더명 (예: `gemini-3-flash-preview`)
  - 폴더명에 괄호/한글이 섞여 있어 쉘에서 그대로 넘기기 까다로우면(예: `구버전(4o)`),
    부분 문자열(예: `4o`)만 넘겨도 자동으로 유일하게 일치하는 폴더를 찾아 사용한다.
- 출력: `docs/evaluation/result/device/files/eval/<model_dir_name>.md` (파일명에서 괄호는 제거)
- 필요시 `--queue-id ID`로 정식 배치를 직접 지정, `--out PATH`로 출력 경로 지정 가능
- 구버전 기록처럼 `tokens_total` 필드가 없으면 `tokens_in + tokens_out`으로 자동 계산

## 동작 방식 (확정된 규칙)

1. **queue_id 중복 제거**: 같은 task_index/attempt 조합이 여러 queue_id에 걸쳐 존재할 수 있음
   (예: 정식 배치 전에 실행한 단발 테스트). 파일 수가 가장 많은 queue_id를 "정식 배치"로 자동
   선택하고, 나머지는 제외한다. 애매하면 실행 로그의 "제외된 파일" 목록을 사용자에게 보여줄 것.
2. **표는 task당 1행**. attempt(a1/a2/a3...)는 `a1: x / a2: y / a3: z` 형식으로 한 셀에 요약.
   - 소요시간(s) ← `elapsed_s`
   - 라운드 ← `rounds`
   - 총 토큰/금액$ ← `tokens_total`(`$cost_usd`)
   - **결과 열은 비워둔다** (사용자가 직접 채워 넣음)
3. **flow는 표 아래 별도 섹션**(`## flow 상세`)에 task별로, attempt별로 나눠서 작성.
   tool 호출을 `{round}. \`{name}\` — {reason} ({round_s}s)` 형식으로 나열하되, 단순 나열이 아니라
   **왜 실패했는지까지 표시**한다. `{round_s}`는 그 라운드에 소모된 시간(`llm_ms + tool_ms`, 초 단위
   소수 첫째자리)이다. 구버전 기록처럼 `llm_ms`/`tool_ms` 필드 자체가 없으면 `(0.0s)`로 잘못
   표시하지 말고 시간 표시를 생략한다:
   - `ok: false`면 → `❌ 실패: {result}` (result에 실패 사유 텍스트가 들어있음, 예:
     "존재하지 않는 id 또는 제스처 실패"). `node_id`가 있는 액션(`tap_node` 등)이면
     **그 실패한 tool 자신의 `screen` 텍스트에 그 `node_id`(`[nXX]` 패턴)가 실제로 있었는지 대조**해서
     추가로 표시한다(`screen`은 "그 라운드의 액션을 결정할 때 모델이 실제로 본 화면"이므로,
     한 라운드 전 tool의 screen이 아니라 실패한 tool 자신의 screen을 봐야 함):
     - 있었으면 → `[해당 화면엔 id 존재 → 타이밍/화면갱신 이슈로 추정]`
       (LLM 판단은 맞았는데 탭 제스처나 화면 갱신 타이밍이 실패한 케이스)
     - 없었으면 → `[해당 화면에 id 없음 → LLM이 없는 id를 지어낸 것으로 추정]`
       (LLM 환각 케이스 — 이게 나오면 모델 추론 문제로 분류)
     - 그 tool 자신에게 기록된 screen이 없으면(1라운드째 실패 등) → `[해당 화면 기록 없음 — 판단 불가]`
   - `ok: true`인데 `settle`이 `null`/`"matched"`가 아니면(예: `"hard timeout"`) →
     `⚠️ {settle}: {result}`. `hard timeout`은 액션 자체는 성공했지만 이후 화면이
     10초 안에 안정되지 않아 강제로 다음 단계로 넘어갔다는 뜻(`AgentAccessibilityService.kt`의
     `waitForUiSettle`, `HARD_TIMEOUT_MS = 10_000L`). task 실패는 아니지만 `elapsed_s`를
     크게 늘리는 원인.
   - `name == "ask_user"`면(HITL 개입) → `🙋 **\`ask_user\`(HITL)**`로 표시하고
     질문(`args.question`, `args.kind`)과 결과(`result`)를 하위 줄에 별도로 보여준다.
     응답을 못 받으면 result가 "60초 동안 응답 없음..." 형태로 남는데, 같은 질문이
     반복되면(재시도 루프) 문제군 후보로 바로 눈에 띔. attempt 헤더에는 실제 사용자가
     응답한 횟수(`hitl_count`, 실패한 시도는 미포함)를 `HITL N회`로 함께 표시.
   - `name == "finish"`면 그 줄 바로 아래에 `finish_summary`를 `- **결과**: "{finish_summary}"`로
     들여써서 보여준다 (원본 json을 다시 안 열어도 flow만 보고 판단할 수 있게). finish_summary는
     agent의 자기 보고일 뿐이므로, 실제로 맞는 답인지는 이 텍스트를 사람이 직접 판단해야 함 —
     스텝이 다 성공해도 답이 틀렸을 수 있음.

## 참고

- 소스 json 구조: `task`, `elapsed_s`, `rounds`, `tokens_total`, `cost_usd`, `tools[]`
  (`tools[].round/name/reason/ok/result/settle`) 등.
- 이 스크립트는 task_index/attempt 개수에 의존하지 않고 실제 존재하는 만큼만 처리하므로
  10개/3회 구성이 아니어도 그대로 동작한다.
