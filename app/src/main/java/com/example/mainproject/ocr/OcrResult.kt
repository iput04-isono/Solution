package com.example.mainproject.ocr

/**
 * OCR認識1件分の結果。
 *
 * @param text          認識した文字列（例: "BISb30N-7A"）
 * @param confidence    平均信頼度（0.0〜1.0）。アプリ側の分岐判定に使う
 *                        ≥ 0.85 → 自動確定候補
 *                        0.60〜0.85 → ユーザー確認
 *                        < 0.60 → 再撮影を促す
 * @param maxConfidence 文字単位の最高信頼度
 * @param minConfidence 文字単位の最低信頼度
 */
data class OcrResult(
    val text: String,
    val confidence: Float,
    val maxConfidence: Float = 0f,
    val minConfidence: Float = 0f
)
