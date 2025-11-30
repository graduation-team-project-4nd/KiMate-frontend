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
import com.example.kioskassistapp.R
import com.example.kioskassistapp.camera.CameraXManager
import com.example.kioskassistapp.ocr.HapticFeedbackManager
import com.example.kioskassistapp.ocr.MultiAnalyzer
import com.example.kioskassistapp.ocr.OverlayView
import com.example.kioskassistapp.util.PermissionUtils
import com.example.kioskassistapp.voice.SpeechRecognizerManager
import com.example.kioskassistapp.voice.TtsManager
import com.example.kioskassistapp.util.TextSimilarity

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
}
