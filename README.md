# 처음길

처음 가는 길의 어려운 구간을 1.5km 전과 400m 전에 음성으로 미리 알려주는 Pleos Connect 차량용 앱 (프로토타입).

## 지금 되는 것

- NaviHelper 회전 안내(TBT)와 주의 지점을 받아 어려운 구간(갈림길, 지하차도·고가 옆길, 나들목, 회전교차로, 합류, 300m 안에 이어지는 연속 회전 등)을 고른다.
- 1.5km 앞에서 한 번, 400m 앞에서 한 번 Pleos TextToSpeech(ON_DEVICE)로 읽어 주고 화면 카드에 표시한다. 경로를 시작하면 어려운 구간 수를 먼저 요약한다("이번 경로에 어려운 구간이 2곳 있어요").
- 안내 문장은 차로를 지정하지 않는다. TBT에는 회전 방향만 있고 차로 정보가 없어서, "지하차도와 갈라지니, 지하차도가 아닌 오른쪽 옆길인지 미리 확인하세요"처럼 갈 길을 확인하게 한다.
- 안내 문장은 순서대로 하나씩 읽는다. TTS 준비 전이나 다른 문장을 읽는 중에 생긴 안내도 버리지 않고 기다렸다 읽는다(최대 3개).
- 헤맴 지도: 경로 재탐색, 급제동(브레이크 후 3초 안에 시속 20km 이상 감속), 방향지시등 망설임(30초 안에 4번 켜고 끔), 크루즈 해제를 감지해 `files/struggle_events.csv`에 `종류,위도,경도,시각ms,주행ID`로 저장한다.
- 서로 다른 주행 3회 이상 헤맨 반경 50m 지점을 헤맴 구간으로 묶고, 진행 방향 ±45°, 1.5km 안에 들어오면 음성으로 알린다.
- `데모 주행` 버튼: 에뮬레이터에서 받은 판교 경로 TBT를 2배속으로 재생 (에뮬레이터 차량은 실제로 이동하지 않기 때문).
- `헤맴 지도 데모` 버튼: 가상 헤맴 기록 3건으로 헤맴 구간을 만들고 그쪽으로 주행을 재생 (가상 기록은 저장하지 않음).
- 익숙한 길: 같은 목적지에 3번 이상 도착하면(`files/route_history.tsv`, `목적지<TAB>횟수`) 미리 안내를 생략하고 어려운 구간의 400m 전 안내만 한다. 헤맴 구간·주의 지점 안내는 유지한다.
- 음성 질의: 카드의 `물어보기` → Pleos SpeechToText(ON_DEVICE)로 듣고, 다음 구간·이유·남은 개수·다시 말해줘·안내 줄이기/자세히를 현재 경로 기준으로 답한다.
- 화면: 주행 중(시속 5km 이상)에는 거리·구간 그림·구간 이름만 크게 보여 주고, 정차 중에는 대처법과 이어지는 구간 목록까지 보여 준다. 세로(내비 옆 한 창)는 위아래, 가로(두 창 확장)는 좌우 배치. 데모 버튼과 로그는 debug 빌드에서만 보인다.

## 구성

- `app/`: Pleos Connect 차량 앱 (Android)
- `core/`: 순수 Kotlin 로직. 브리핑, 헤맴 지도 집계, 익숙한 길, 음성 질의, 서버 통신 형식(Wire). 앱과 서버가 함께 쓴다.
- `server/`: 헤맴 지도 집계 서버 (JDK 내장 HTTP 서버, 외부 라이브러리 없음)

## 헤맴 지도 서버

```
./gradlew :server:run            # 기본 포트 8090, 데이터는 ./data/events.csv (PORT, DATA_DIR 환경변수로 변경)
```

- `POST /v1/events`: 본문 한 줄에 `종류,위도,경도,시각ms,주행ID`. 형식이 틀린 줄은 버리고, 같은 이벤트를 다시 보내도 한 번만 센다.
- `GET /v1/hotspots?lat=..&lon=..&radiusM=..`: 응답 한 줄에 `위도,경도,주행수,주된종류`.
- debug 빌드 앱은 에뮬레이터에서 `http://10.0.2.2:8090`(Mac의 서버)으로 접속한다. release 빌드는 서버 주소가 비어 있어 기기 안에서만 집계한다.
- 앱은 주행이 끝나면 아직 보내지 않은 이벤트를 올리고(마지막으로 보낸 시각 기준), 출발지·목적지 300m 안의 이벤트는 보내지 않는다. 출발지·목적지를 아직 모르거나 읽을 수 없으면 올리지 않고 기다리며, 목적지 없이 달린 주행의 이벤트는 보내지 않는다.
- 경로를 시작할 때 주변 헤맴 구간을 받아온다. 이때 위치는 약 1km 격자(소수 둘째 자리)로 뭉개서 보낸다. 서버에 연결할 수 없으면 기기 안 집계로 안내한다.

## 실행 방법

1. Pleos Connect Emulator v2.1.0 (API 34) AVD를 Cold boot로 실행한다.
   - 내비가 "서비스 연결 중"에서 멈추면 에뮬레이터의 DNS 문제다(IPv6 위주 네트워크의 Mac에서 발생). DNS 서버를 지정해 다시 켠다:
     `emulator -avd Pleos_Connect -no-snapshot -dns-server 8.8.8.8,1.1.1.1`
2. `./gradlew installDebug`
3. 런처에서 `처음길`을 실행하고, 내비에서 목적지를 설정하거나 `데모 주행`을 누른다.
4. 로그: `adb logcat -s Cheoeumgil`

## 테스트 환경

- Pleos Connect Emulator 2.1.0, Android 14 (API 34), Apple Silicon Mac
- Gradle 9.1.0, AGP 8.13.0, Kotlin 2.2.20, JDK 25 (Android Studio 내장 JBR)
- SDK: Vehicle 2.3.2, NaviHelper 3.0.5, TextToSpeech 3.3.1, SpeechToText 3.3.1
- 단위 테스트: `./gradlew :core:test :server:test` 29건: 브리핑 8(차로 지정 금지 확인 포함), 헤맴 지도 6, 익숙한 길 2, 음성 질의 3, 음성 출력 순서 2, 서버 통신 형식 4, 아이콘 1, 서버 저장소 3
- 마이크 없이 음성 질의 테스트 (debug 빌드): 16kHz 16-bit mono PCM을 앱의 `files/query.pcm`에 넣고 `adb shell am start -n com.cheoeumgil.app/.MainActivity --ez stt_file_test true`

## 알려진 제약

- NaviHelper 이벤트는 앱이 화면에 보일 때만 전달된다. 그래서 Pleos 멀티 윈도우에서 내비 옆 세로 창에 띄워 쓰도록 설계했다 (내비 창 오른쪽 끝 핸들을 가운데로 끌면 두 창으로 나뉜다). 이 배치에서 실경로 이벤트 수신을 확인했다.
- 크루즈 상태는 대기(ENABLED)와 작동(ACTIVATED)만 구분되고, 차로 유지 보조와 자율주행 상태는 null로 온다.

## APK

제출용 release APK: [`apk/이의용_처음길_release.apk`](apk/이의용_처음길_release.apk)

- APK Signature Scheme v3로 서명 (Pleos 요구사항: 서명 키 교체·lineage 지원, minSdk 28)
- Pleos Connect Emulator 2.1.0에 설치해 실행 확인 (차량 신호, NaviHelper, TTS, STT 초기화 정상)
- 직접 빌드: 서명 정보를 `~/keys/cheoeumgil-signing.properties`(`storeFile`, `keyAlias`, `storePassword`, `keyPassword`)에 두고 `./gradlew :app:assembleRelease`. 이 파일이 없으면 서명하지 않은 APK가 만들어진다.
