package com.example.android_2

data class OcrResult(
    val text: String,
    val confidence: Float,
    val maxConfidence: Float = 0f,
    val minConfidence: Float = 0f
)