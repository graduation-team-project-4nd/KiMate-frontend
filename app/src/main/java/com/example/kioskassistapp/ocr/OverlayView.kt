// File: OverlayView.kt
package com.example.kioskassistapp.ocr

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.util.Log
import android.view.View

class OverlayView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    private val boxes = mutableListOf<RectF>()
    private var fingerPoint: PointF? = null // 👈 [수정] 이 변수에 값이 할당되도록 수정합니다.

    // 텍스트 박스용 페인트 (기존)
    private val paint = Paint().apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 8.0f // 8px 굵기
    }

    // 👈 [추가] 손가락 좌표를 그릴 페인트 (빨간색, 채우기)
    private val fingerPaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.FILL
    }

    // 이 변수들은 좌표 변환에 사용됩니다.
    private var analysisImageWidth = 0
    private var analysisImageHeight = 0
    private val transformMatrix = Matrix()

    /**
     * Analyzer에서 감지된 원본 박스(Rect) 리스트, 손가락 좌표(PointF),
     * 원본 이미지 크기를 받아 좌표를 변환하고 화면을 갱신합니다.
     */
    // ▼▼▼ [수정됨] fingerPoint: PointF? 매개변수 추가 ▼▼▼
    fun updateResults(
        originalBoxes: List<Rect>,
        fingerPoint: PointF?, // 👈 [추가] 손가락 좌표 받기
        imageWidth: Int,
        imageHeight: Int
    ) {
        Log.d("OverlayDebug", "Received fingerPoint: $fingerPoint")
        // 1. 분석 이미지 크기 저장
        analysisImageWidth = imageWidth
        analysisImageHeight = imageHeight

        // 2. 변환 매트릭스 계산
        updateTransformationMatrix()

        // 3. 👈 [추가] 손가락 좌표 저장
        this.fingerPoint = fingerPoint

        // 4. 원본 좌표(Rect)를 화면 좌표(RectF)로 변환
        boxes.clear()
        for (box in originalBoxes) {
            val boxF = RectF(box)
            transformMatrix.mapRect(boxF)
            boxes.add(boxF)
        }

        // 5. View를 다시 그리도록 요청 (onDraw 호출)
        Log.d("OverlayDebug", "Stored fingerPoint: ${this.fingerPoint}, image: $$ {analysisImageWidth}x $${analysisImageHeight}")
        invalidate()
    }

    private fun updateTransformationMatrix() {
        if (analysisImageWidth == 0 || analysisImageHeight == 0 || width == 0 || height == 0) {
            return
        }

        // CameraX PreviewView의 기본값인 CENTER_CROP을 기준으로 매트릭스를 계산합니다.
        val scaleX = width.toFloat() / analysisImageWidth
        val scaleY = height.toFloat() / analysisImageHeight
        val scale = maxOf(scaleX, scaleY)
        val offsetX = (width.toFloat() - analysisImageWidth * scale) / 2f
        val offsetY = (height.toFloat() - analysisImageHeight * scale) / 2f

        transformMatrix.reset()
        transformMatrix.postScale(scale, scale)
        transformMatrix.postTranslate(offsetX, offsetY)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateTransformationMatrix()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)


        for (box in boxes) {
            canvas.drawRect(box, paint)
        }

        //  ▼▼▼ 손가락 좌표 그리기 ▼▼▼
        fingerPoint?.let { point ->
            // PointF를 Matrix로 변환하기 위해 FloatArray 사용
            val pointArray = floatArrayOf(point.x, point.y)

            // 박스와 '동일한' 매트릭스를 적용하여 좌표 변환
            transformMatrix.mapPoints(pointArray)

            // 변환된 좌표 추출
            val scaledX = pointArray[0]
            val scaledY = pointArray[1]

            // 화면에 빨간색 원(반지름 25px) 그리기
            canvas.drawCircle(scaledX, scaledY, 25f, fingerPaint)
        }
    }
}