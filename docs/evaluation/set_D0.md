# D0 골든셋

태스크 목록 원본은 [`app/src/main/assets/eval/set_D0.txt`](../../app/src/main/assets/eval/set_D0.txt) 한 곳에 둔다.
앱의 평가 큐(`EvalQueueRunner`)가 이 파일을 그대로 읽어 돌리므로, 셋을 바꿀 때는 이 파일만 고친다.

- 한 줄에 목표 하나. 줄 순서가 `task_index` (1-based)
- 빈 줄과 `#`로 시작하는 줄은 건너뛴다
- 앱에서 목록을 편집하면 그 실행에만 적용되고, `원본 불러오기`로 되돌린다

실행 규정은 [rule.md](./rule.md), 결과 정리는 [result/](./result/)에 둔다.
런당 JSON은 기기의 `filesDir/eval/`에 `_tNNaM`(태스크 번호·시도 번호) 태그로 저장된다.
