# 같이타 (GatiTa)

**"오늘 누구랑 가요?" 한 마디로, 차가 탑승자에 맞춰 바뀌는 음성 동승자 앱** · 팀 RAMIC STUDIO

현대자동차그룹 차량용 앱 개발 공모전 출품작입니다. 출발할 때 음성으로 동승자를 묻고 아이·반려동물·혼자 세 가지 모드로 차량 경험을 바꿉니다.

| 모드 | 주요 기능 | 사용 SDK |
|---|---|---|
| 👶 아이 | 남은 도착 시간에 맞춰 길이가 바뀌는 분기형 이야기, 강을 건널 때 열리는 장면, 장면별 엠비언트 조명, 하차 리마인더 | NaviHelper, Vehicle(Light·Door·Gear), TTS, STT |
| 🐶 반려동물 | 뒷좌석 창문 열림 제한(초과 시 자동 복귀), 하차 리마인더 | Vehicle(Window·Door·Gear), TTS |
| 🧑 혼자 | 연속 주행 시간 기반 깨어있기 코치, 휴게소 제안 | Vehicle(Gear·Light), TTS |

공통: 기어 상태로 **주행 중(D)에는 음성·큰 글자만, 정차 중(P)에는 확장 화면**을 보여 줍니다.

## 실행 방법

1. Android Studio에서 Pleos Connect Emulator를 설치하고 AVD를 만듭니다. ([Pleos 문서](https://document.pleos.ai/docs/connect/guide/getting-started/application-development/setup-connect-sdk-emulator))
2. 에뮬레이터를 실행한 뒤 APK를 설치합니다.
   ```bash
   adb install -r apk/RAMIC_STUDIO_GatiTa_release.apk
   ```
3. 런처에서 **같이타**를 실행합니다.
4. Pleos Connect Virtual Console(`adb forward tcp:8088 tcp:8088` 후 http://127.0.0.1:8088/)에서 차량 신호를 바꿔 가며 동작을 확인합니다.
   - 기어 P → D: 주행 화면으로 전환, 이야기 시작
   - 기어 D → P 후 운전석 문 열기(뒷문은 닫힌 상태): 하차 리마인더 경고
   - 반려동물 모드에서 뒷좌석 창문 열기: 제한 높이로 자동 복귀

## 테스트 환경 및 필요한 설정

| 항목 | 값 |
|---|---|
| Pleos Connect Emulator | v2.1.0 (Android API 34, x86_64) |
| 화면 규격 | 2560 × 1440, mdpi(160dpi), 가로형 (Pleos Connect v2.1.0 AVD 기본값) |
| minSdk / targetSdk | 28 / 34 |
| 빌드 | Gradle 8.9, AGP 8.5.2, Kotlin 1.9.25, JDK 17 |
| Pleos SDK | Vehicle 2.3.2, NaviHelper 3.0.5, TextToSpeech 3.3.1, SpeechToText 3.3.1 |

- 마이크 권한(`RECORD_AUDIO`)을 허용하면 음성으로 모드와 이야기 갈림길을 고를 수 있습니다. 음성 인식이 안 되는 환경에서는 화면 버튼으로 고를 수 있습니다.
- 내비게이션 경로가 없으면 5초 뒤 **시연용 13분 20초 경로를 시뮬레이션**합니다(남은 시간 표시에 "시뮬레이션" 표기). 실제 경로가 설정되면 NaviHelper의 남은 시간을 사용합니다.
- Pleos TTS 초기화에 실패하면 Android 기본 TTS로 대체합니다.

## 빌드

```bash
./gradlew assembleRelease
```

release 서명 키(`release.jks`)는 저장소에 포함하지 않습니다. 키가 없으면 debug 키로 서명됩니다.

## APK 위치

`apk/RAMIC_STUDIO_GatiTa_release.apk`

## 프로젝트 구조

```
app/src/main/java/ai/gatita/app/
├─ MainActivity.kt   모드 선택, 주행/정차 화면 전환, 하차 리마인더, 깨어있기 코치
├─ VehicleBridge.kt  Vehicle SDK: 기어·도어·창문·엠비언트 조명
├─ NaviBridge.kt     NaviHelper SDK: 남은 시간·현재 위치·도착
├─ Voice.kt          TextToSpeech / SpeechToText SDK (On-Device, 기본 TTS 대체)
└─ Story.kt          남은 시간 기반 이야기 개수 계산, 분기형 이야기
```
