package com.example.kioskassistapp.model

import android.graphics.RectF

data class MenuItem(
    val id: String,
    val text: String,
    val boundingBox: RectF, // 뷰 좌표계
    val confidence: Float
)