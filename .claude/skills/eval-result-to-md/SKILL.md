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
- `--golden PATH`로 성공 기준 md를 바꿀 수 있다. 기본은 `docs/evaluation/set_D0.md`
- 성공 판정은 `local.properties`의 LLM으로 한다. 캐시는 `docs/evaluation/.judge_cache.json`. `--no-judge`면 판정을 건너뛴다
- 구버전 기록처럼 `tokens_total` 필드가 없으면 `tokens_in + tokens_out`으로 자동 계산

## 동작 방식 (확정된 규칙)

1. **queue_id 중복 제거**: 같은 task_index/attempt 조합이 여러 queue_id에 걸쳐 존재할 수 있음
   (예: 정식 배치 전에 실행한 단발 테스트). 파일 수가 가장 많은 queue_id를 "정식 배치"로 자동
   선택하고, 나머지는 제외한다. 애매하면 실행 로그의 "제외된 파일" 목록을 사용자에게 보여줄 것.
2. **표는 task당 1행**. attempt(a1/a2/a3...)는 `a1: x / a2: y / a3: z` 형식으로 한 셀에 요약.
   - 성공 ← LLM judge (`set_D0.md` 성공 기준 + `final_screen` / `final_package` / `finish_summary` / `ask_user(confirm)`). `pass` | `fail`
   - RRR ← 사람 최단 스텝 / `tools[]` 길이. **성공한 런만**. 실패는 `—`
   - 초과 스텝 ← 에이전트 스텝 − 사람 스텝. 성공한 런만
   - 소요시간(s) ← `elapsed_s`
   - 라운드 ← `rounds`
   - 총 토큰/금액$ ← `tokens_total`(`$cost_usd`)
   - 표 위에 전체 성공률, pass@k, pass^k, RRR(micro, 성공 런), 평균 초과 스텝을 한 줄로 적는다
3. **flow는 표 아래 별도 섹션**(`## flow 상세`)에 task별로, attempt별로 나눠서 작성.
   attempt마다 아래만 적는다. 실패 추정, HITL 강조, settle 경고, 중간 화면은 넣지 않는다.
   - `verdict` / `verdict_evidence` (judge)
   - `RRR` (성공 런만. 실패는 `—`)
   - `finish_summary`
   - `final_package`
   - `final_screen` (없으면 `(없음)`)
   - 플로우: `{round}. \`{name}\` {args} — {reason} ({round_s}s)`
     `{args}`는 `reason`/`summary`를 제외한 실제 인자
     (`package`, `node_id`, `text`, `query`, `direction`, `question`).
     `{round_s}`는 `llm_ms + tool_ms`. 구버전처럼 두 필드가 없으면 시간 표시를 생략한다.

## 참고

- 소스 json 구조: `task`, `elapsed_s`, `rounds`, `tokens_total`, `cost_usd`,
  `finish_summary`, `final_package`, `final_screen`, `tools[]`
  (`tools[].round/name/reason/args`) 등.
- 이 스크립트는 task_index/attempt 개수에 의존하지 않고 실제 존재하는 만큼만 처리하므로
  10개/3회 구성이 아니어도 그대로 동작한다.
