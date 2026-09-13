---
name: pull-eval
description: 기기 files/eval 의 eval json을 PC로 가져올 때 사용. "eval 뽑아줘", "eval pull", "기기에서 eval 가져와" 같은 요청에 사용.
---

# 기기 eval json pull

기기 앱 내부저장소 `files/eval`의 `*.json`을 PC로 가져온다.
성공한 파일은 기기에서 삭제된다.

## 실행

저장소 루트에서:

```
powershell -File scripts/pull_eval.ps1
```

- 원격: `com.mystar.agent` 의 `files/eval`
- 로컬: `docs/evaluation/result/device/files/eval`
- 전제: adb, 기기 연결, USB 디버깅, 앱 설치

## 끝난 뒤

스크립트 출력의 성공/실패 건수와 저장 경로를 사용자에게 그대로 보고한다.
`No json files to pull`이면 기기에 남은 json이 없는 것이다.

이 스킬은 pull만 한다. md 정리(`eval-result-to-md`)는 사용자가 따로 요청할 때만 이어서 한다.
