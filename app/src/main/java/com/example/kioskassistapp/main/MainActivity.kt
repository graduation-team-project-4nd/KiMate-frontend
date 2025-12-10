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
import com.example.kioskassistapp.model.AiResponse
import com.example.kioskassistapp.network.RetrofitClient
import com.example.kioskassistapp.ocr.HapticFeedbackManager
import com.example.kioskassistapp.ocr.MultiAnalyzer
import com.example.kioskassistapp.ocr.OverlayView
import com.example.kioskassistapp.util.PermissionUtils
import com.example.kioskassistapp.voice.SpeechRecognizerManager
import com.example.kioskassistapp.voice.TtsManager
import com.example.kioskassistapp.util.TextSimilarity
import kotlinx.coroutines.launch
import com.example.kioskassistapp.model.AnalyzeRequest
import com.example.kioskassistapp.model.DialogueItem
import com.example.kioskassistapp.model.SessionRequest

class MainActivity : AppCompatActivity() {

    private lateinit var cameraManager: CameraXManager
    private lateinit var previewView: PreviewView
    private lateinit var hapticManager: HapticFeedbackManager
    private lateinit var ttsManager: TtsManager
    private lateinit var speechManager: SpeechRecognizerManager
    private lateinit var overlayView: OverlayView

    private var lastRecognizedText: String = ""
    private var currentOcrResults: List<Pair<String, Rect>> = emptyList()
    private var aiTargetText: String? = null
    private var dialogueHistory = mutableListOf<DialogueItem>()
    private var lastBtnClicked: String? = null

    // 0.6점 이상이면 매칭 인정
    private val MATCH_THRESHOLD = 0.6

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
            Log.d("SpeechRecognizer", "인식된 텍스트: $recognized")
            if (recognized.isNotEmpty()) {
                processUserRequest(recognized)
            }
        }

        if (PermissionUtils.hasAllPermissions(this)) {
            startCameraProcess()
        } else {
            PermissionUtils.requestAllPermissions(this)
        }

        findViewById<Button>(R.id.btn_voice).setOnClickListener {
            ttsManager.speak("말씀해주세요.")
            handler.postDelayed({ speechManager.startListening() }, 1000L)
        }
        createSession()
    }

    // ------------------------------------------------------------------------
    // ⭐ [핵심 1] 매칭 로직을 하나로 통일 (가장 중요)
    // 사용자 말로 찾을 때도 이거 쓰고, AI가 시킨 단어 찾을 때도 이거 씀
    // ------------------------------------------------------------------------
    private fun findBestMatch(
        target: String,
        candidates: List<Pair<String, Rect>>
    ): Triple<String?, Rect?, Double> {
        var bestText: String? = null
        var bestBox: Rect? = null
        var maxScore = 0.0

        val cleanTarget = target.replace("\\s".toRegex(), "") // 타겟 공백 제거

        for ((screenText, box) in candidates) {
            val cleanScreen = screenText.replace("\\s".toRegex(), "") // 화면 글자 공백 제거

            // 1. 기본 유사도 점수 (0.0 ~ 1.0)
            var score = TextSimilarity.calculate(cleanTarget, cleanScreen)

            // 2. [안전한 포함 관계]
            // "화면(Screen)"이 "타겟(Target)"을 포함하고 있는가?
            // 예: Screen="음료/사이드", Target="음료" -> 포함됨(OK) -> 점수 1.0 (만점)
            // 예: Screen="버거", Target="치즈버거" -> 포함안됨(NO) -> 점수 낮음
            if (cleanScreen.contains(cleanTarget, ignoreCase = true)) {
                score = 1.0
            }

            if (score > maxScore) {
                maxScore = score
                bestText = screenText
                bestBox = box
            }
        }
        return Triple(bestText, bestBox, maxScore)
    }

    // ------------------------------------------------------------------------
    // 사용자 요청 처리
    // ------------------------------------------------------------------------
    private fun processUserRequest(userUtterance: String) {
        if (currentOcrResults.isEmpty()) {
            requestAiAnalysis(userUtterance)
            return
        }

        // 1. 로컬 매칭 시도 (위에서 만든 함수 사용)
        val (bestText, _, maxScore) = findBestMatch(userUtterance, currentOcrResults)

        Log.d("Hybrid", "User: $userUtterance, BestMatch: $bestText, Score: $maxScore")

        // 2. 점수가 높으면 로컬 성공 -> 바로 안내
        if (bestText != null && maxScore >= MATCH_THRESHOLD) {
            handleLocalSuccess(bestText, userUtterance)
        } else {
            // 3. 점수가 낮으면 AI 호출
            Log.d("Hybrid", "유사도 낮음 -> AI 호출")
            requestAiAnalysis(userUtterance)
        }
    }

    private fun handleLocalSuccess(matchedText: String, userUtterance: String) {
        Log.d("Hybrid", "로컬 매칭 성공! Target: $matchedText")
        aiTargetText = matchedText // ⭐ 타겟 설정 -> 카메라 루프가 이걸 보고 박스 그림

        ttsManager.speak("화면에서 $matchedText 버튼을 찾았습니다.")
        dialogueHistory.add(DialogueItem("user", userUtterance))
        dialogueHistory.add(DialogueItem("assistant", "$matchedText 버튼을 안내했습니다."))
    }

    // ------------------------------------------------------------------------
    // AI 통신
    // ------------------------------------------------------------------------
    private fun requestAiAnalysis(userUtterance: String) {
        val sessionId = currentSessionId
        if (sessionId == null) {
            ttsManager.speak("잠시만 기다려주세요.")
            createSession()
            return
        }

        val currentScreenTexts = currentOcrResults.map { it.first }
        dialogueHistory.add(DialogueItem("user", userUtterance))

        lifecycleScope.launch {
            try {
                val request = AnalyzeRequest(
                    sessionId = sessionId,
                    userInput = userUtterance,
                    ocrTexts = currentScreenTexts,
                    dialogueHistory = dialogueHistory,
                    lastBtn = lastBtnClicked ?: "unknown"
                )
                val response = RetrofitClient.api.analyze(request)
                if (response.isSuccessful && response.body() != null) {
                    handleAiResponse(response.body()!!)
                } else {
                    ttsManager.speak("죄송합니다. 다시 말씀해 주세요.")
                }
            } catch (e: Exception) {
                Log.e("API", "Error", e)
            }
        }
    }

    private fun handleAiResponse(response: AiResponse) {
        dialogueHistory.add(DialogueItem("assistant", response.responseMessage))
        ttsManager.speak(response.responseMessage)

        Log.d("DEBUG_CHK", "--------- 응답 분석 시작 ---------")
        Log.d("DEBUG_CHK", "Action Type: '${response.action.type}'")

        // ⭐ 헬퍼 함수 사용
        val targetText = response.getTargetText()
        Log.d("DEBUG_CHK", "Target Text via getTargetText(): $targetText")

        if (targetText == null) {
            Log.e("DEBUG_CHK", "❌ target_text를 가져오지 못했습니다!")
        }

        Log.d("DEBUG_CHK", "-----------------------------------")

        when (response.action.type) {
            "click_text" -> {
                if (targetText != null) {
                    aiTargetText = targetText
                    Log.d("AI", "타겟 설정 성공: $targetText")
                } else {
                    Log.e("AI", "❌ 타겟 설정 실패 (params가 비어있거나 target_text가 없음)")
                    aiTargetText = null
                }
            }
            else -> aiTargetText = null
        }
    }


    // ------------------------------------------------------------------------
    // 카메라 루프 (실시간 박스 그리기)
    // ------------------------------------------------------------------------
    private fun startCameraProcess() {
        cameraManager = CameraXManager(this, previewView)

        val multiAnalyzer = MultiAnalyzer(
            this,
            onAnalysisComplete = { textsAndBoxes, fingerPoint, imageWidth, imageHeight ->

                currentOcrResults = textsAndBoxes
                val targetText = aiTargetText

                if (targetText == null || textsAndBoxes.isEmpty()) {
                    overlayView.post {
                        overlayView.updateResults(emptyList(), fingerPoint, imageWidth, imageHeight)
                    }
                    return@MultiAnalyzer
                }

                // ⭐ [핵심 2] AI가 시킨 타겟을 찾을 때도 똑같은 로직(findBestMatch) 사용
                // 이렇게 하면 "음료"가 "음료/사이드"에 포함되어 있으니 100% 찾아짐
                val (_, bestBox, bestScore) = findBestMatch(targetText, textsAndBoxes)

                // 점수가 너무 낮은 건 무시 (엉뚱한 박스 방지)
                val finalBox = if (bestScore >= 0.5) bestBox else null

                // UI 업데이트
                overlayView.post {
                    val boxList = if (finalBox != null) listOf(finalBox) else emptyList()
                    overlayView.updateResults(boxList, fingerPoint, imageWidth, imageHeight)
                }

                if (fingerPoint != null && finalBox != null) {
                    hapticManager.checkAndVibrate(fingerPoint, finalBox)
                }
            }
        )
        cameraManager.startCamera(multiAnalyzer)
    }

    // ... createSession, onRequestPermissionsResult, onDestroy 등 기존 코드 유지 ...
    private fun createSession() {
        lifecycleScope.launch {
            try {
                val response = RetrofitClient.api.createSession(SessionRequest())
                if (response.isSuccessful && response.body() != null) {
                    currentSessionId = response.body()?.id
                    Log.d("API", "Session: $currentSessionId")
                }
            } catch (e: Exception) {
                Log.e("API", "Session Fail", e)
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PermissionUtils.ALL_PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults.all { it == android.content.pm.PackageManager.PERMISSION_GRANTED }) {
                startCameraProcess()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::cameraManager.isInitialized) cameraManager.stopCamera()
        ttsManager.shutdown()
        speechManager.stopListening()
        handler.removeCallbacksAndMessages(null)
    }
}