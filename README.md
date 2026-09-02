# MyStar Agent

음성 기반 ReAct 폰 에이전트 (Android MVP).

## M0 상태

프로젝트 셋업 + 접근성 서비스 등록까지 완료.

### 빌드

1. Android SDK 경로와(선택) API 키를 `local.properties`에 설정합니다.

```properties
sdk.dir=C\:\\Users\\YOU\\AppData\\Local\\Android\\Sdk
LLM_API_KEY=
```

예시: [`local.properties.example`](local.properties.example)

2. 디버그 APK 빌드:

```bash
./gradlew assembleDebug
```

출력: `app/build/outputs/apk/debug/app-debug.apk`

### 접근성 서비스 검증 (DoD)

1. APK 설치 후 앱 실행
2. **설정 > 접근성**에서 **MyStar Agent** 활성화
3. Logcat / 앱 로그에 `onServiceConnected: instance bound` 확인
4. 앱 화면 상태가 **접근성 서비스: 연결됨**으로 바뀌는지 확인

마이크 버튼은 M5에서 연결합니다. 지금은 비활성입니다.
