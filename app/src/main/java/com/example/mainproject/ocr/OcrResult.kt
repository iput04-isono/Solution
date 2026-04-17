package com.example.mainproject.ocr

/**
 * OCR認識1件分の結果。
 *
 * @property text          認識文字列（例: "BISb30N-7A"）
 * @property confidence    全文字の平均信頼度（0.0〜1.0）
 * @property maxConfidence 最も確信度が高い文字の信頼度
 * @property minConfidence 最も確信度が低い文字の信頼度
 */
data class OcrResult(
    val text: String,
    val confidence: Float,
    val maxConfidence: Float = 0f,
    val minConfidence: Float = 0f
)
