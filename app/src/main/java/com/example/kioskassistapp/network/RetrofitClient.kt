package com.example.kioskassistapp.network

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
object RetrofitClient {
//    private const val BASE_URL = "http://172.30.1.44:3000/"
    private const val BASE_URL = "http://192.168.105.227:3000/"

    private val okHttpClient by lazy {
        // ⭐ [핵심] 통신 로그를 'BODY' 레벨로 설정
        // 이렇게 하면 주고받는 모든 JSON 내용이 Logcat에 찍힙니다.
        val logging = HttpLoggingInterceptor { message ->
            Log.d("API_RAW_LOG", message) // "API_RAW_LOG" 태그로 검색하세요
        }.apply {
            level = HttpLoggingInterceptor.Level.BODY
        }

        OkHttpClient.Builder()
            .addInterceptor(logging)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    val api: AiApiService by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(AiApiService::class.java)
    }

}
