package com.example.kioskassistapp.network

import com.example.kioskassistapp.model.* // 위에서 만든 모델 import
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

interface AiApiService {

    // [POST] /v1/sessions : 앱 켜지면 세션 ID 발급
    @POST("v1/sessions")
    suspend fun createSession(
        @Body request: SessionRequest
    ): Response<SessionResponse>

    // [POST] /v1/ai/analyze : OCR 텍스트 + 사용자 발화 보내서 분석
    @POST("v1/ai/analyze")
    suspend fun analyze(
        @Body request: AnalyzeRequest
    ): Response<AiResponse>
}