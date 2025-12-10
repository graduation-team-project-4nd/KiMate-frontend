package com.example.kioskassistapp.model

import com.google.gson.annotations.SerializedName
import com.google.gson.JsonArray
import com.google.gson.JsonParser

// --- 요청 모델 (Request) ---
data class AnalyzeRequest(
    @SerializedName("session_id") val sessionId: String,
    @SerializedName("user_input") val userInput: String? = null,
    @SerializedName("ocr_texts") val ocrTexts: List<String>,
    @SerializedName("dialogue_history") val dialogueHistory: List<DialogueItem>,
    @SerializedName("last_btn") val lastBtn: String? = null
)

data class DialogueItem(
    val role: String,      // "user" or "assistant"
    val utterance: String
)

// --- 세션 생성 요청 ---
data class SessionRequest(
    val locale: String = "ko-KR",
    val kioskType: String? = "KIOSK_APP",
    val deviceId: String? = null
)

// --- 세션 응답 ---
data class SessionResponse(
    val id: String,
    val status: String
)
// --- AI 응답 모델 (Response) ---
data class AiResponse(
    val status: String,
    val confidence: Double,
    @SerializedName("response_message") val responseMessage: String,
    val action: AiAction
) {
    /**
     * 최종 타겟 텍스트를 가져오는 함수
     * 1순위: action.params에 명시된 값
     * 2순위: responseMessage에서 '버튼' 앞의 단어 파싱
     */
    fun getTargetText(): String? {
        // 1. action params에 값이 있으면 그걸 우선 사용 (기존 로직 유지)
        val explicitTarget = action.getTargetText()
        if (!explicitTarget.isNullOrBlank()) {
            return explicitTarget
        }

        // 2. 없으면 responseMessage에서 파싱 ("텍스트 + 버튼" 패턴 찾기)
        return parseTargetFromMessage(responseMessage)
    }

    private fun parseTargetFromMessage(message: String): String? {
        // 정규식 설명:
        // (\S+) : 공백이 아닌 문자가 1개 이상 연속됨 (캡쳐 그룹 1 -> 타겟 텍스트)
        // \s* : 공백이 0개 이상 (붙어있거나 띄어쓰기 있거나)
        // 버튼   : 리터럴 "버튼"
        val regex = Regex("""(\S+)\s*버튼""")

        // 예: "음료 버튼으로 안내하겠습니다" -> "음료" 추출
        val matchResult = regex.find(message)

        // groupValues[1]이 우리가 원하는 타겟 텍스트입니다.
        return matchResult?.groupValues?.get(1)
    }
}

data class AiAction(
    val type: String,
    val params: Map<String, Any>? = null
) {
    fun getTargetText(): String? {
        if (params == null) return null

        // params에서 찾아보기
        val raw = params["target_text"] ?: params["text"] ?: params["target"]

        return when (raw) {
            is String -> raw
            is Number -> raw.toString()
            is Boolean -> raw.toString()
            else -> null
        }
    }
    /** JSON 배열에서 문자열 하나 꺼내기 (이중배열도 처리) */
    private fun extractFirstStringFromArray(array: JsonArray): String? {
        if (array.size() == 0) return null

        return when {
            // ["콜라"]
            array[0].isJsonPrimitive -> array[0].asString

            // [["콜라"]]
            array[0].isJsonArray -> {
                val inner = array[0].asJsonArray
                if (inner.size() > 0 && inner[0].isJsonPrimitive) inner[0].asString else null
            }

            else -> null
        }
    }

    /** Kotlin List에서 문자열 하나 꺼내기 (이중배열도 처리) */
    private fun extractFirstStringFromList(list: List<*>): String? {
        if (list.isEmpty()) return null

        return when (val first = list[0]) {
            is String -> first                      // ["콜라"]
            is List<*> -> first.firstOrNull() as? String   // [["콜라"]]
            else -> null
        }
    }

}
