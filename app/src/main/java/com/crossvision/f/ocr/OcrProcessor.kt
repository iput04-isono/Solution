package com.crossvision.f.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await

/**
 * ML Kit を使用したOCR処理クラス
 * オンデバイスで動作するため、オフライン環境でも使用可能
 */
class OcrProcessor {

    // ML Kit のテキスト認識エンジン（Latin文字用）
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    /**
     * 画像からテキストを認識する
     * @param bitmap 撮影画像
     * @return 認識結果のリスト（製品コード候補と位置情報）
     */
    suspend fun recognizeText(bitmap: Bitmap): List<OcrResult> {
        val inputImage = InputImage.fromBitmap(bitmap, 0)
        val result = recognizer.process(inputImage).await()

        val ocrResults = mutableListOf<OcrResult>()

        for (block in result.textBlocks) {
            for (line in block.lines) {
                val text = line.text.trim()

                // 製品コードとして妥当性をチェック
                val cleanedCode = ProductCodeValidator.cleanProductCode(text)
                val validation = ProductCodeValidator.validate(cleanedCode)

                if (validation.isValid) {
                    ocrResults.add(
                        OcrResult(
                            rawText = text,
                            cleanedCode = cleanedCode,
                            confidence = line.confidence,
                            boundingBox = line.boundingBox,
                            isValid = true
                        )
                    )
                }
            }
        }

        return ocrResults
    }

    /**
     * 複数製品の一括認識
     * 最大5本までの製品コードを一括で認識する
     * @param bitmap 撮影画像
     * @param maxResults 最大認識数（デフォルト5）
     * @return 認識結果のリスト
     */
    suspend fun recognizeMultipleProducts(
        bitmap: Bitmap,
        maxResults: Int = 5
    ): List<OcrResult> {
        val results = recognizeText(bitmap)
        return results.take(maxResults)
    }

    /**
     * リソースの解放
     */
    fun close() {
        recognizer.close()
    }
}

/**
 * OCR認識結果
 */
data class OcrResult(
    val rawText: String,          // OCR生テキスト
    val cleanedCode: String,      // クリーニング済み製品コード
    val confidence: Float,        // 認識信頼度
    val boundingBox: Rect?,       // 画像上の認識領域
    val isValid: Boolean          // バリデーション結果
)
