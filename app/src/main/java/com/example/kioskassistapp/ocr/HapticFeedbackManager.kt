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
    private val DISTANCE_THRESHOLD = 600 // px

    /**
     * fingerPoint: 손가락 좌표
     * selectedBox: 음성 인식 결과와 유사도가 가장 높은, 선택된 텍스트 박스 하나
     */
    fun checkAndVibrate(fingerPoint: PointF?, selectedBox: Rect?) {

        Log.d("HapticDebug", "--- New Frame ---")
        Log.d("HapticDebug", "Finger: $fingerPoint | selectedBox: $selectedBox")

        if (fingerPoint == null || selectedBox == null) {
            Log.d("HapticDebug", "-> RETURN (fingerPoint or selectedBox is null)")
            return
        }

        // 선택된 박스의 중심점 계산
        val centerX = selectedBox.centerX().toFloat()
        val centerY = selectedBox.centerY().toFloat()
        val distance = hypot(fingerPoint.x - centerX, fingerPoint.y - centerY)

        Log.d(
            "HapticDebug",
            "Distance to selectedBox center: $distance | Threshold: $DISTANCE_THRESHOLD"
        )

        if (distance > DISTANCE_THRESHOLD) {
            Log.d("HapticDebug", "-> RETURN (Not close enough to selectedBox)")
            return
        }

        val currentTime = System.currentTimeMillis()
        if (currentTime - lastVibrateTime < VIBRATE_COOLDOWN) {
            Log.d("HapticDebug", "-> RETURN (Cooldown active)")
            return
        }

        Log.d("HapticDebug", "!!! VIBRATING on selectedBox !!!")
        lastVibrateTime = currentTime

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(
                VibrationEffect.createOneShot(
                    150,
                    VibrationEffect.DEFAULT_AMPLITUDE
                )
            )
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(150)
        }
    }
}
