package com.example.kioskassistapp.ocr

import android.content.Context
import android.graphics.PointF
import android.graphics.Rect
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

class HapticFeedbackManager(private val context: Context) {

    private val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    private var lastVibrateTime = 0L

    // 설정 값
    private val DISTANCE_THRESHOLD = 1200 // 감지 시작 거리 (기존 600 -> 1200으로 확장)

    // 진동 간격 (ms) : 가까우면 100ms, 멀면 800ms
    private val MIN_INTERVAL = 100L
    private val MAX_INTERVAL = 1200L

    // 진동 세기 (1~255) : 가까우면 255(최대), 멀면 150(약함)
    private val MIN_AMPLITUDE = 150
    private val MAX_AMPLITUDE = 255

    /**
     * fingerPoint: 손가락 좌표
     * selectedBox: 목표 타겟 박스
     */
    fun checkAndVibrate(fingerPoint: PointF?, selectedBox: Rect?) {
        if (fingerPoint == null || selectedBox == null) return

        // 1. 거리 계산
        val centerX = selectedBox.centerX().toFloat()
        val centerY = selectedBox.centerY().toFloat()
        val distance = hypot(fingerPoint.x - centerX, fingerPoint.y - centerY)

        // 2. 임계값 벗어나면 리턴
        if (distance > DISTANCE_THRESHOLD) {
            return
        }

        // 3. 거리 비율 계산 (0.0: 중심점 일치 ~ 1.0: 임계값 끝자락)
        // distance가 0에 가까울수록 ratio는 0, 멀수록 1
        val ratio = (distance / DISTANCE_THRESHOLD).coerceIn(0.0f, 1.0f)

        // 4. 동적 인터벌 계산 (가까우면 MIN_INTERVAL, 멀면 MAX_INTERVAL)
        // 거리가 가까울수록(ratio -> 0) 간격이 짧아짐
        val currentInterval = MIN_INTERVAL + (ratio * (MAX_INTERVAL - MIN_INTERVAL)).toLong()

        // 쿨다운 체크
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastVibrateTime < currentInterval) {
            return
        }

        lastVibrateTime = currentTime

        // 5. 진동 실행 (거리별 세기 조절)
        // 거리가 가까울수록(ratio -> 0) 세기(amplitude)가 강해짐
        val amplitude = (MAX_AMPLITUDE - (ratio * (MAX_AMPLITUDE - MIN_AMPLITUDE))).toInt()

        Log.d("HapticDebug", "Dist: ${distance.toInt()} | Interval: $currentInterval | Amp: $amplitude")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(
                VibrationEffect.createOneShot(
                    50, // 진동 지속 시간은 짧게(50ms) 끊어쳐야 "두두두두" 느낌이 잘 남
                    amplitude // 계산된 세기 적용
                )
            )
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(50) // 구형 기기는 세기 조절 불가, 시간만 짧게 설정
        }
    }
}