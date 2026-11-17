# 빌드·구조·검증 안내

앱이 하는 일과 한계는 [README](../README.md)에 있습니다. 이 문서는 코드를 확인하거나 고치려는 사람을 위한 설명입니다.

## 빌드 환경

- JDK 17 이상(검증에는 JDK 21 사용), Android SDK Platform 35와 Build Tools
- 컴파일·대상 SDK 35, 최소 SDK 29(Android 10)
- 도구 버전은 [버전 카탈로그](../gradle/libs.versions.toml)와 [wrapper 설정](../gradle/wrapper/gradle-wrapper.properties)에 고정돼 있습니다.

프로젝트 루트의 `local.properties`에 SDK 경로를 지정합니다. Android Studio가 만들어 주기도 합니다. 이 파일은 Git에서 제외합니다.

```properties
sdk.dir=C:/Android/Sdk
```

```shell
./gradlew testDebugUnitTest assembleDebug lintDebug
```

| 결과 | 경로 |
| --- | --- |
| APK | `app/build/outputs/apk/debug/app-debug.apk` |
| 테스트 보고서 | `app/build/reports/tests/testDebugUnitTest/index.html` |
| Lint 보고서 | `app/build/reports/lint-results-debug.html` |

직접 빌드한 APK는 배포판과 서명이 달라 배포판 위에 덮어 깔리지 않습니다. 정식 서명 빌드는 [RELEASE.ko.md](RELEASE.ko.md)를 참고하세요.

## 구성

| 구성 | 역할 |
| --- | --- |
| `MainActivity` | 화면 전환(감독관 화면·위치 찾기·안내), 권한 요청, 화면 켜 두기 |
| `AppViewModel` | 측정·감시 상태, 빈 교실 기록 저장, 화면 밖 일시 중지와 재개, 비행기 모드·무음 확인 |
| `scanner/BleScanner` | BLE 광고 수신, 실제 패킷 시각 검사, 수신 상태·오류 구분과 보수적 주기 갱신 |
| `scanner/WifiScanner` | 30초 간격 탐색 요청, 완료 알림에서 새 결과만 받기 |
| `core/DeviceTracker`, `ObservationPeriods` | 무선 종류+주소별 누적 관측, 수신 구간, 최근/대기/기록 상태 |
| `core/SignalGroups` | 주소별 관측 보존, 불확실한 BLE 그룹·보조 목록·세션 내 최근 관측 구성 |
| `core/SignalSnapshot`, `SnapshotStore` | 빈 교실 기록과 그 JSON 저장 |
| `core/SignalHints`, `SignalDetails` | 알림 신호에서 종류 단서와 부가 정보 읽기 |
| `core/DeviceIdentity`, `AudioNameCatalog`, `WearableNameCatalog` | 회사 코드·주소 등록부·이름 규칙으로 제조사와 제품군 후보 찾기 |
| `core/RssiFilter`, `DistanceEstimate` | 공통 유효값 검사·세기 평활/등급. 거리 모델은 화면의 거리 측정에 사용하지 않음 |
| `core/Geiger`, `SilentMode` | 위치 찾기 진동, 측정·감시 중 방해 금지 |
| `sim/RadioSimulator` | 가상 전파. 화면에서는 켤 수 없고 자동 테스트만 씁니다. |
| `ui/GuardScreen` | 감독관 화면, 위치 찾기, 안내 |
| `core/ScanHealth`, `ui/ScanHealthPanel` | 수신 중지·대기·수신·정상 완료·오류와 수동 복구 안내 |

## 수신과 저장의 규칙

- `radioKey`는 무선 종류와 대문자로 맞춘 주소의 조합입니다. BLE와 와이파이는 주소 문자열이 같아도 별개입니다.
- 콜백에서 바로 추적기에 기록하고 화면용 집계는 100ms마다 갱신합니다. 화면 갱신 속도가 탐지율을 뜻하지는 않습니다.
- BLE는 탐색 시작 전·미래·0 이하의 시각을 가진 패킷을 버립니다. 늦게 도착한 패킷은 실제 관측 시각을 유지합니다.
- BLE는 보수적으로 4분마다 갱신하지만 무수신만으로 시스템 제한을 단정하거나 재시작을 반복하지 않습니다. 오류 후에는 감독관이 설정을 확인하고 수신을 다시 시작합니다.
- 와이파이는 완료 알림이 온 탐색에서 이번 측정 시작 이후의 결과만 받습니다. 같은 주소의 같거나 이전 시각은 다시 세지 않습니다.
- 비어 있지 않은 Wi-Fi 검색 결과가 전부 이전·만료·중복 패킷이면 정상 빈 검색으로 바꾸거나 기준선 완료 횟수를 늘리지 않습니다. 성공 브로드캐스트의 실제 빈 결과는 별개로 처리합니다.
- 빈 교실 저장은 정상 활성 수집 120초와 Wi-Fi 완료 2회 이상을 ViewModel에서도 검사합니다. 일시중지·실패·오래된 완료 상태는 수집 시간을 늘리지 않습니다.
- BLE 15초, 와이파이 90초를 넘는 공백을 수신 구간의 경계로 봅니다.
- 화면이 꺼지거나 앱이 뒤로 가면(`ON_STOP`) 수신을 멈추고, 돌아오면(`ON_START`) 같은 감시를 이어 갑니다. 비어 있던 횟수와 시간은 `ScanSession`에 남습니다. 백그라운드 수신 서비스는 없습니다.
- 묶음·확인·보조 목록 규칙은 [README](../README.md#관측을-잃지-않으면서-묶어-보는-방법)를 참고하세요. 초기 설계 문서는 과거 선택의 배경으로 보존합니다.

빈 교실 기록은 마지막 하나만 앱 전용 저장소에 JSON으로 둡니다. 임시 파일에 쓰고 동기화한 뒤 교체합니다. 파일 형식 번호는 2이며 Wi-Fi 이름 숨김 여부를 추가로 보존합니다. 구형 파일의 숨김 여부는 기존 기본 이름으로 추정하되, 새 파일에서는 실제로 같은 문자열인 SSID와 구분합니다. 이전 배포판이 쓴 파일도 읽습니다. 백업과 기기 간 이전 대상에서 제외합니다.

## 세기 표시

유효한 RSSI(-127..-1 dBm)만 EMA(α=0.25)와 강도 등급에 사용합니다. 최신 값이 무효라면 관측 자체는 남기되 현재 세기·등급·진동은 미측정으로 둡니다. 과거 peak와 늦게 온 패킷은 최근 값을 덮어쓰지 않습니다.

화면에는 미보정 거리 수치를 표시하지 않습니다. BLE 광고 Tx Power와 1m 보정 RSSI는 다르므로 광고 출력을 보정값으로 사용하지 않습니다. `DistanceEstimate`의 별도 로그 거리 계산 역시 같은 유효값 검사를 사용하지만, 실제 위치나 좌석을 보장하지 않습니다. [Bluetooth SIG의 RSSI 설명](https://www.bluetooth.com/blog/proximity-and-rssi/) 참고.

## 검증 범위

자동 테스트는 묶음 판정, 추적, 저장, 식별, 스캐너 콜백, 감독관 화면 흐름(가상 전파)을 다룹니다. 다음은 자동 테스트가 대신하지 못합니다.

- 실제 무선 환경에서의 수신률과 누락률
- 기종·Android 버전별 권한 화면과 시스템의 탐색 제한
- 발열과 배터리 소모

실기기 결과를 알려 줄 때는 기종, Android 버전, 앱 버전, 켠 기기와 그 상태, 잡힌 시각과 안 잡힌 시각을 구분해 적어 주세요. 개인 이름이나 실제 현장을 알 수 있는 자료는 그대로 올리지 마세요.

## 저장소에 올리지 않는 것

`local.properties`, 서명 키, 빌드 출력, 실제 현장에서 받은 기록. 사용자에게 보이는 설명에는 근거 없는 탐지율, AI 판정, 정확한 위치 보장을 넣지 않습니다.
