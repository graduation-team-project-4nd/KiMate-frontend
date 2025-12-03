package com.example.kioskassistapp.model


import com.google.gson.annotations.SerializedName

// 1. 세션 생성 요청/응답
data class SessionRequest(
    val locale: String = "ko-KR",
    val kioskType: String? = "KIOSK_APP",
    val deviceId: String? = null
)

data class SessionResponse(
    val id: String,
    val status: String
    // 필요한 다른 필드가 있다면 추가
)

// 2. AI 분석 요청 (Heavy Analysis)
data class AnalyzeRequest(
    @SerializedName("session_id") val sessionId: String,
    @SerializedName("user_input") val userInput: String?,
    @SerializedName("ocr_texts") val ocrTexts: List<String>,
    @SerializedName("dialogue_history") val dialogueHistory: List<DialogueItem>,
    @SerializedName("last_btn") val lastBtn: String = "unknown"
)

data class DialogueItem(
    val role: String,      // "user" 또는 "assistant"
    val utterance: String
)

// 3. AI 응답 (외부 AI 서버 응답 구조에 맞춤)
data class AiResponse(
    @SerializedName("recommended_actions") val recommendedActions: List<Any>?,
    val confidence: Double?,
    val reasoning: String?
)