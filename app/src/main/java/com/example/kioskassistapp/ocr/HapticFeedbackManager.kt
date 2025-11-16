// File: HapticFeedbackManager.kt
package com.example.kioskassistapp.ocr

import android.content.Context
import android.graphics.PointF
import android.graphics.Rect
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import kotlin.math.hypot

class HapticFeedbackManager(private val context: Context) {

    private val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    private var lastVibrateTime = 0L
    private val VIBRATE_COOLDOWN = 1000L // 1초 쿨타임
    private val DISTANCE_THRESHOLD = 200 // px

    fun checkAndVibrate(fingerPoint: PointF?, textBoxes: List<Rect>?) {

        Log.d("HapticDebug", "--- New Frame ---")
        Log.d("HapticDebug", "Finger: $fingerPoint | Box count: ${textBoxes?.size}")


        if (fingerPoint == null || textBoxes.isNullOrEmpty()) {
            Log.d("HapticDebug", "-> RETURN (Null or Empty)") // 👈 [로그 추가]
            return
        }

        // ▼▼▼ [디버깅을 위해 로직 수정] ▼▼▼
        // 가장 가까운 박스와의 거리를 계산
        var minDistance = Float.MAX_VALUE
        textBoxes.forEach { box ->
            val centerX = box.centerX().toFloat()
            val centerY = box.centerY().toFloat()
            val distance = hypot(fingerPoint.x - centerX, fingerPoint.y - centerY)
            if (distance < minDistance) {
                minDistance = distance
            }
        }

        // 가장 가까운 거리를 로그로 출력
        Log.d("HapticDebug", "Min distance to a box: $minDistance | Threshold: $DISTANCE_THRESHOLD")

        // 임계값(Threshold) 비교
        val isCloseToAnyBox = minDistance <= DISTANCE_THRESHOLD

        if (!isCloseToAnyBox) {
            Log.d("HapticDebug", "-> RETURN (Not close enough)") // 👈 [로그 추가]
            return
        }


        val currentTime = System.currentTimeMillis()
        if (currentTime - lastVibrateTime < VIBRATE_COOLDOWN) {
            Log.d("HapticDebug", "-> RETURN (Cooldown active)") // 👈 [로그 추가]
            return
        }

        Log.d("HapticDebug", "!!! VIBRATING !!!") // 👈 [로그 추가]
        lastVibrateTime = currentTime

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(150, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            vibrator.vibrate(150)
        }
    }
}