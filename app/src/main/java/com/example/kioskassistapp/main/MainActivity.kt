// File: MainActivity.kt
package com.example.kioskassistapp.main

import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.view.PreviewView
import androidx.lifecycle.lifecycleScope
import com.example.kioskassistapp.R
import com.example.kioskassistapp.camera.CameraXManager
import com.example.kioskassistapp.network.RetrofitClient
import com.example.kioskassistapp.ocr.HapticFeedbackManager
import com.example.kioskassistapp.ocr.MultiAnalyzer
import com.example.kioskassistapp.ocr.OverlayView
import com.example.kioskassistapp.util.PermissionUtils
import com.example.kioskassistapp.voice.SpeechRecognizerManager
import com.example.kioskassistapp.voice.TtsManager
import com.example.kioskassistapp.util.TextSimilarity
import kotlinx.coroutines.launch
import com.example.kioskassistapp.model.AnalyzeRequest // import 추가
import com.example.kioskassistapp.model.DialogueItem // import 추가
import com.example.kioskassistapp.model.SessionRequest // import 추가
class MainActivity : AppCompatActivity() {

    private lateinit var cameraManager: CameraXManager
    private lateinit var previewView: PreviewView
    private lateinit var hapticManager: HapticFeedbackManager
    private lateinit var ttsManager: TtsManager
    private lateinit var speechManager: SpeechRecognizerManager
    private lateinit var overlayView: OverlayView

    private var lastRecognizedText: String = ""
    private var lastSpokenTime = 0L

    private val handler = Handler(Looper.getMainLooper())
    private var currentSessionId: String? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        overlayView = findViewById(R.id.overlay_view)
        hapticManager = HapticFeedbackManager(this)
        ttsManager = TtsManager(this)
        speechManager = SpeechRecognizerManager(this) { recognized ->
            lastRecognizedText = recognized
            ttsManager.speak("선택한 메뉴는 $recognized 입니다.")
            Log.d("SpeechRecognizer", "인식된 텍스트: $recognized")
        }

        // 앱 시작 시 카메라 + 오디오 권한 동시에 확인
        if (PermissionUtils.hasAllPermissions(this)) {
            startCameraProcess()
        } else {
            PermissionUtils.requestAllPermissions(this)
        }

        // 음성 인식 버튼: 1초 딜레이 후 인식 시작
        findViewById<Button>(R.id.btn_voice).setOnClickListener {
            ttsManager.speak("말씀해주세요.")

            // 1초 후에 음성 인식 시작
            handler.postDelayed({
                speechManager.startListening()
            }, 1000L)
        }
        createSession()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        when (requestCode) {
            PermissionUtils.ALL_PERMISSION_REQUEST_CODE -> {
                // 카메라 + 오디오 두 개 다 들어옴
                if (grantResults.isNotEmpty() &&
                    grantResults.all { it == android.content.pm.PackageManager.PERMISSION_GRANTED }
                ) {
                    startCameraProcess()
                } else {
                    Toast.makeText(this, "카메라/음성 권한이 필요합니다.", Toast.LENGTH_SHORT).show()
                }
            }

        }
    }

    private fun startCameraProcess() {
        cameraManager = CameraXManager(this, previewView)

        val multiAnalyzer = MultiAnalyzer(
            this,
            onAnalysisComplete = { textsAndBoxes, fingerPoint, imageWidth, imageHeight ->

                // 음성 인식된 텍스트가 없으면: 아무 박스도 강조 안 함, 진동도 X
                if (lastRecognizedText.isEmpty() || textsAndBoxes.isEmpty()) {
                    overlayView.post {
                        overlayView.updateResults(emptyList(), fingerPoint, imageWidth, imageHeight)
                    }
                    return@MultiAnalyzer
                }

                // 1. 유사도 가장 높은 텍스트 박스 찾기
                var bestBox: Rect? = null
                var bestScore = 0.0

                for ((text, box) in textsAndBoxes) {
                    val score = TextSimilarity.calculate(lastRecognizedText, text)
                    if (score > bestScore) {
                        bestScore = score
                        bestBox = box
                    }
                }

                // 유사도가 너무 낮으면(예: 0.4 이하면) 매칭 안 된 걸로 간주
                val SIMILARITY_THRESHOLD = 0.4
                if (bestBox == null || bestScore < SIMILARITY_THRESHOLD) {
                    overlayView.post {
                        overlayView.updateResults(emptyList(), fingerPoint, imageWidth, imageHeight)
                    }
                    return@MultiAnalyzer
                }

                // 2. OverlayView에는 가장 유사한 박스 하나만 전달
                val selectedBox = bestBox
                overlayView.post {
                    overlayView.updateResults(listOf(selectedBox), fingerPoint, imageWidth, imageHeight)
                }

                // 3. TTS 로직
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastSpokenTime > 15_000) {  // 15초 쿨타임
                    lastSpokenTime = currentTime
                    ttsManager.speak("화면에서 ${lastRecognizedText} 메뉴를 찾았습니다. 손으로 가리켜주세요.")
                }

                // 4. 진동 로직 - selected박스에만 haptic 적용
                if (fingerPoint == null) return@MultiAnalyzer
                hapticManager.checkAndVibrate(fingerPoint, selectedBox)
            }
        )

        cameraManager.startCamera(multiAnalyzer)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::cameraManager.isInitialized) cameraManager.stopCamera()
        ttsManager.shutdown()
        speechManager.stopListening()
        handler.removeCallbacksAndMessages(null)
    }
    // ⭐ 1. 세션 생성 함수
    private fun createSession() {
        lifecycleScope.launch {
            try {
                val response = RetrofitClient.api.createSession(SessionRequest())
                if (response.isSuccessful && response.body() != null) {
                    currentSessionId = response.body()?.id
                    Log.d("API", "세션 생성 성공: $currentSessionId")

                    // 세션이 만들어진 후 테스트 호출
                    testAiApi()
                } else {
                    Log.e("API", "세션 생성 실패: ${response.code()}")
                }
            } catch (e: Exception) {
                Log.e("API", "네트워크 에러(세션): ${e.message}")
            }
        }
    }

    // ⭐ 2. 실제 AI 분석 요청 테스트 (기존 testAiApi 교체)
    private fun testAiApi() {
        val sessionId = currentSessionId ?: return // 세션 ID 없으면 리턴

        lifecycleScope.launch {
            try {
                // 더미 데이터로 요청 생성
                val request = AnalyzeRequest(
                    sessionId = sessionId,
                    userInput = "커피 주문하고 싶어",
                    ocrTexts = listOf("아메리카노", "라떼", "주문하기"),
                    dialogueHistory = listOf(DialogueItem("user", "안녕")),
                    lastBtn = "unknown"
                )

                val res = RetrofitClient.api.analyze(request)

                if (res.isSuccessful) {
                    val aiResult = res.body()
                    Log.d("API_TEST", "AI 분석 결과: ${aiResult?.reasoning}")
                    Log.d("API_TEST", "추천 행동: ${aiResult?.recommendedActions}")

                    // TTS로 결과 읽어주기 (테스트)
                    aiResult?.reasoning?.let { ttsManager.speak(it) }
                } else {
                    Log.e("API_TEST", "AI 요청 실패: ${res.code()}")
                }
            } catch (e: Exception) {
                Log.e("API_TEST", "에러: ${e.message}", e)
            }
        }
    }

}
