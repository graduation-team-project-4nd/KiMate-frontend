# KioskAssist — 시각장애인 키오스크 보조 앱

> 카메라로 키오스크 화면을 인식하고, 음성·진동·시각 피드백으로 시각장애인이 독립적으로 키오스크를 사용할 수 있도록 돕는 안드로이드 앱.

[![Platform](https://img.shields.io/badge/platform-Android-green)]()
[![Min SDK](https://img.shields.io/badge/minSDK-26-blue)]()
[![Language](https://img.shields.io/badge/kotlin-1.9-orange)]()

---

## ✨ 주요 기능

- 📷 **실시간 OCR** — ML Kit로 키오스크 화면의 한글 텍스트 인식
- ✋ **손가락 추적** — MediaPipe로 사용자 손가락 위치 검출
- 🔊 **음성 안내** — TTS로 화면 정보 안내, STT로 음성 명령 인식
- 📳 **거리 기반 햅틱** — 손가락이 타겟에 가까워질수록 진동이 빨라짐 (가이거 카운터 방식)
- 👁 **접근성 UI** — 색맹/저시력자도 보이는 이중 외곽선 + 모서리 마커
- 🔄 **화면 전환 감지** — Fuzzy matching으로 OCR 노이즈에 강건한 감지
- 💳 **결제 완료 자동 종료** — 결제 완료 시 모든 안내 자동 정리
- 🤖 **하이브리드 AI** — 로컬 매칭 우선, 필요 시 백엔드 AI 호출

---

## 🎬 데모

📺 **시연 영상**: [영상 링크 추가]
📄 **상세 설계 문서**: [`docs/최종설계문서.pdf`](docs/)

---

## 🚀 빠른 시작

### 옵션 1: APK 설치 (가장 빠름)

1. `release/kiosk-assist-app-v1.0.0.apk` 파일을 안드로이드 폰으로 전송
2. APK 탭 → "출처를 알 수 없는 앱 설치" 허용 → "설치"
3. 앱 실행 → 카메라/마이크 권한 허용

> 📱 **요구사항**: Android 8.0 (API 26) 이상

### 옵션 2: 소스에서 빌드

```bash
# 1. 저장소 클론
git clone https://github.com/<your-org>/kiosk-assist-app.git
cd kiosk-assist-app

# 2. MediaPipe 모델 다운로드 (필수!)
curl -L -o app/src/main/assets/hand_landmarker_full.task \
  "https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/latest/hand_landmarker.task"

# 3. local.properties 설정
echo "sdk.dir=$ANDROID_HOME" > local.properties
echo "API_BASE_URL=https://your-backend.example.com/" >> local.properties

# 4. 빌드 & 설치 (단말 USB 연결 필요)
./gradlew installDebug
```

> 💡 Android Studio Hedgehog (2023.1.1) 이상, JDK 17 필요

---

## 📱 사용 방법

| 단계 | 동작 |
|---|---|
| 1️⃣ | 앱 실행 후 카메라를 키오스크 화면에 향함 |
| 2️⃣ | 인식된 텍스트가 화면에 노란/검정 박스로 표시됨 |
| 3️⃣ | 화면 하단 🎤 버튼 탭 → "불고기버거" 등 메뉴명 발화 |
| 4️⃣ | 해당 버튼이 강조 표시되며 음성 안내 |
| 5️⃣ | 손가락을 화면에 대고 이동 → 진동으로 버튼 위치 안내 |
| 6️⃣ | 결제 완료 시 자동으로 안내 종료 |

---

## 🏗 시스템 구조

```
[CameraX] → [MultiAnalyzer] ─┬─► OCR (ML Kit)
                              └─► 손가락 (MediaPipe)
                                     │
                ┌────────────────────┼────────────────────┐
                ▼                    ▼                    ▼
       [ScreenChangeDetector] [PaymentDetector]   [HapticManager]
                │                    │                    │
                └────────────────────┼────────────────────┘
                                     ▼
                              [MainActivity]
                          ┌──────────┼──────────┐
                          ▼          ▼          ▼
                   [OverlayView] [TtsManager] [AI Backend]
```

---

## 📂 프로젝트 구조

```
kiosk-assist-app/
├── app/src/main/
│   ├── assets/hand_landmarker_full.task     # MediaPipe 모델
│   ├── java/com/example/kioskassistapp/
│   │   ├── main/MainActivity.kt             # 오케스트레이션
│   │   ├── camera/CameraXManager.kt
│   │   ├── ocr/
│   │   │   ├── MultiAnalyzer.kt             # OCR + 손 검출
│   │   │   ├── OverlayView.kt               # 접근성 UI
│   │   │   ├── ScreenChangeDetector.kt      # 화면 전환 감지
│   │   │   ├── PaymentCompletionDetector.kt # 결제 완료 감지
│   │   │   └── HapticFeedbackManager.kt     # 거리 기반 진동
│   │   ├── voice/
│   │   │   ├── TtsManager.kt
│   │   │   └── SpeechRecognizerManager.kt
│   │   └── network/RetrofitClient.kt
│   └── res/                                 # 레이아웃, 리소스
├── docs/                                    # 설계 문서
├── release/                                 # 빌드된 APK
└── README.md
```

---

## 🛠 기술 스택

| 영역 | 사용 기술 |
|---|---|
| 언어 | Kotlin 1.9 |
| 카메라 | CameraX 1.3 |
| OCR | ML Kit Text Recognition (Korean) |
| 손 검출 | MediaPipe HandLandmarker |
| 음성 | Android TTS, SpeechRecognizer |
| 네트워크 | Retrofit + OkHttp |
| 비동기 | Kotlin Coroutines |

---

## 🧪 빌드 명령어

```bash
./gradlew assembleDebug      # 디버그 APK 생성
./gradlew assembleRelease    # 릴리스 APK 생성
./gradlew installDebug       # 단말에 설치
./gradlew clean              # 빌드 정리
```

---

## ⚠️ 주의사항

- **MediaPipe 모델 파일 필수**: `app/src/main/assets/hand_landmarker_full.task`가 없으면 빌드는 되지만 손가락 검출 실패
- **백엔드 서버**: AI 분석 기능을 사용하려면 백엔드 서버 URL을 `local.properties`에 설정. 미설정 시에도 **로컬 OCR 매칭 기능은 정상 작동**
- **권한 거부 시**: 카메라/마이크 권한 거부하면 앱 사용 불가 → 시스템 설정에서 수동 허용 필요
- **TTS 한국어 엔진**: 단말에 한국어 TTS 엔진이 없으면 음성 안내 안 됨 (`설정 → 접근성 → TTS 출력`)

---

## 📋 개발 환경

| 항목 | 버전 |
|---|---|
| Android Studio | Hedgehog 2023.1.1+ |
| JDK | 17 |
| Gradle | 8.0+ |
| Android Gradle Plugin | 8.1.0+ |
| compileSdk | 34 |
| minSdk | 26 |
| targetSdk | 34 |

---

- [Google ML Kit](https://developers.google.com/ml-kit)
- [MediaPipe Solutions](https://developers.google.com/mediapipe)
- [CameraX](https://developer.android.com/training/camerax)
