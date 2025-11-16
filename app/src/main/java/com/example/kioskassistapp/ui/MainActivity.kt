// File: MainActivity.kt
package com.example.kioskassistapp.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.kioskassistapp.R
import com.example.kioskassistapp.camera.CameraXManager
import com.example.kioskassistapp.ocr.HapticFeedbackManager
import com.example.kioskassistapp.ocr.MultiAnalyzer
import com.example.kioskassistapp.ocr.OverlayView
import com.example.kioskassistapp.voice.SpeechRecognizerManager
import com.example.kioskassistapp.voice.TtsManager
import kotlin.math.hypot // hypot 함수 import

class MainActivity : AppCompatActivity() {

    private lateinit var cameraManager: CameraXManager
    private lateinit var previewView: PreviewView
    private lateinit var hapticManager: HapticFeedbackManager
    private lateinit var ttsManager: TtsManager
    private lateinit var speechManager: SpeechRecognizerManager
    private lateinit var overlayView: OverlayView // OverlayView 변수
    // private var lastTextBox: Rect? = null

    private var lastRecognizedText: String = ""
    private var lastSpokenTime = 0L

    companion object {
        private const val CAMERA_PERMISSION_REQUEST_CODE = 100
    }

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
        }

        // 권한 확인 후 카메라 시작
        if (isCameraPermissionGranted()) {
            startCameraProcess()
        } else {
            requestCameraPermission()
        }

//        findViewById<Button>(R.id.btn_voice).setOnClickListener {
//            ttsManager.speak("말씀해주세요. 예: 햄버거 주문, 결제하기")
//            speechManager.startListening()
//        }
    }

    private fun isCameraPermissionGranted(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
    }

    private fun requestCameraPermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.CAMERA),
            CAMERA_PERMISSION_REQUEST_CODE
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCameraProcess()
            } else {
                Toast.makeText(this, "카메라 권한이 거부되었습니다.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startCameraProcess() {
        cameraManager = CameraXManager(this, previewView)

        val multiAnalyzer = MultiAnalyzer(
            // 4개의 모든 결과(텍스트+박스, 손가락, 너비, 높이)를 받는 단일 콜백
            onAnalysisComplete = { textsAndBoxes, fingerPoint, imageWidth, imageHeight ->

                // 1. OverlayView 업데이트 (항상 실행)
                // textsAndBoxes에서 Rect 리스트만 추출
                val boxes = textsAndBoxes.map { it.second }
                // UI 스레드에서 overlayView를 업데이트하도록 post
                overlayView.post {
                    overlayView.updateResults(boxes, fingerPoint,imageWidth, imageHeight)
                }

                // 2. TTS 로직 (음성으로 찾은 메뉴가 있을 때)
                // (Pair의 첫 번째(text)와 두 번째(box) 요소를 사용)
                val targetBox = textsAndBoxes.find { (text, _) ->
                    lastRecognizedText.isNotEmpty() && text.contains(lastRecognizedText, ignoreCase = true)
                }?.second // .second로 Rect만 가져옴

                if (targetBox != null) {
                    val currentTime = System.currentTimeMillis()
                    if (currentTime - lastSpokenTime > 10000) { // 10초 쿨타임
                        lastSpokenTime = currentTime
                        ttsManager.speak("화면에서 ${lastRecognizedText} 메뉴를 찾았습니다. 손으로 가리켜주세요.")
                    }
                }

                // 3. 진동 로직 (손가락이 감지되었을 때)
                if (fingerPoint == null) return@MultiAnalyzer // 손가락 없으면 진동 로직 중단

                // 손가락과 가장 가까운 텍스트 박스를 찾음
                val closestBox = textsAndBoxes.minByOrNull { (_, box) ->
                    val centerX = box.centerX().toFloat()
                    val centerY = box.centerY().toFloat()
                    hypot(fingerPoint.x - centerX, fingerPoint.y - centerY)
                }?.second // .second로 Rect만 가져옴

                // 4. 가장 가까운 박스를 기준으로 진동
                hapticManager.checkAndVibrate(fingerPoint, boxes)
            }
        )

        // 카메라 시작 (멀티 분석기 연결)
        cameraManager.startCamera(multiAnalyzer)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::cameraManager.isInitialized) cameraManager.stopCamera()
        ttsManager.shutdown()
        speechManager.stopListening()
    }
}