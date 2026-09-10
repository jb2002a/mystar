# CLAUDE.md

이 저장소에서 코드를 작성·리뷰·리팩터링할 때 항상 적용한다.

---

# Karpathy behavioral guidelines

Behavioral guidelines to reduce common LLM coding mistakes. Merge with project-specific instructions as needed.

**Tradeoff:** These guidelines bias toward caution over speed. For trivial tasks, use judgment.

## 1. Think Before Coding

**Don't assume. Don't hide confusion. Surface tradeoffs.**

Before implementing:
- State your assumptions explicitly. If uncertain, ask.
- If multiple interpretations exist, present them - don't pick silently.
- If a simpler approach exists, say so. Push back when warranted.
- If something is unclear, stop. Name what's confusing. Ask.

## 2. Simplicity First

**Minimum code that solves the problem. Nothing speculative.**

- No features beyond what was asked.
- No abstractions for single-use code.
- No "flexibility" or "configurability" that wasn't requested.
- No error handling for impossible scenarios.
- If you write 200 lines and it could be 50, rewrite it.

Ask yourself: "Would a senior engineer say this is overcomplicated?" If yes, simplify.

## 3. Surgical Changes

**Touch only what you must. Clean up only your own mess.**

When editing existing code:
- Don't "improve" adjacent code, comments, or formatting.
- Don't refactor things that aren't broken.
- Match existing style, even if you'd do it differently.
- If you notice unrelated dead code, mention it - don't delete it.

When your changes create orphans:
- Remove imports/variables/functions that YOUR changes made unused.
- Don't remove pre-existing dead code unless asked.

The test: Every changed line should trace directly to the user's request.

## 4. Goal-Driven Execution

**Define success criteria. Loop until verified.**

Transform tasks into verifiable goals:
- "Add validation" → "Write tests for invalid inputs, then make them pass"
- "Fix the bug" → "Write a test that reproduces it, then make it pass"
- "Refactor X" → "Ensure tests pass before and after"

For multi-step tasks, state a brief plan:
```
1. [Step] → verify: [check]
2. [Step] → verify: [check]
3. [Step] → verify: [check]
```

Strong success criteria let you loop independently. Weak criteria ("make it work") require constant clarification.

## Language

ALWAYS ANSWER IN KOREAN.

**These guidelines are working if:** fewer unnecessary changes in diffs, fewer rewrites due to overcomplication, and clarifying questions come before implementation rather than after mistakes.

---

# 버전 부여 (0.xx)

기능 구현·수정이 끝날 때마다 앱 버전을 **반드시** 한 단계 올린다. 기능만 넣고 버전을 그대로 두지 않는다.

## 형식

- `versionName`: `0.xx` (1.0 이전). 예: `0.01` → `0.02` → `0.03`
- `versionCode`: 양의 정수. +0.01
- 위치: `app/build.gradle.kts` — `defaultConfig`
- 같은 작업에서 파일을 여러 개 고쳐도 **한 번만** 올린다

## 올릴 때 / 올리지 않을 때

- 올린다: 기능 추가, 동작 수정, 버그 수정
- 올리지 않는다: 문서·주석·포맷만 변경

## 마일스톤 라벨과 구분

홈 화면 왼쪽 상단 라벨(`M6 — …`)은 마일스톤 단계 표시다. 앱 버전(`0.xx`)을 대체하지 않는다. 마일스톤을 끝낼 때는 라벨과 버전을 **둘 다** 갱신한다.
