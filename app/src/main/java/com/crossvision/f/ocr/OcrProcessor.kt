package com.crossvision.f.ocr

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * PaddleOCR (ONNX) を使用したOCR処理クラス
 * オンデバイスで動作するため、オフライン環境でも使用可能
 */
class OcrProcessor(private val context: Context) {

    private var engine: OcrEngine? = null
    private val preprocessor = ImagePreprocessor()

    /**
     * 画像からテキストを認識し、あわせて認識箇所に枠を描画した画像を生成する。
     * @param bitmap 撮影画像
     * @return 加工済みBitmapと認識結果リストのペア
     */
    suspend fun recognizeTextWithOverlay(bitmap: Bitmap): Pair<Bitmap, List<DomainOcrResult>> = withContext(Dispatchers.Default) {
        if (engine == null) {
            engine = OcrEngine(context)
        }

        val processedImage = preprocessor.preprocess(bitmap)
        val (overlayBitmap, rawResults) = engine!!.runOcrPolygonWithOverlay(processedImage)
        
        val ocrResults = mutableListOf<DomainOcrResult>()
        for (res in rawResults) {
            val text = res.text.trim()
            if (text.isEmpty()) continue

            val cleanedCode = ProductCodeValidator.cleanProductCode(text)
            val validation = ProductCodeValidator.validate(cleanedCode)

            if (validation.isValid) {
                val cleanedCandidates = res.candidates.map { 
                    ProductCodeValidator.cleanProductCode(it)
                }.filter { it.isNotEmpty() && it != cleanedCode }.distinct()

                ocrResults.add(
                    DomainOcrResult(
                        rawText = text,
                        cleanedCode = cleanedCode,
                        confidence = res.confidence,
                        isValid = true,
                        candidates = cleanedCandidates,
                        polygon = res.polygon
                    )
                )
            }
        }
        Pair(overlayBitmap, ocrResults)
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
    ): List<DomainOcrResult> {
        val results = recognizeTextWithOverlay(bitmap).second
        return results.take(maxResults)
    }

    /**
     * リソースの解放
     */
    fun close() {
        engine?.close()
        engine = null
    }
}

/**
 * 画面へ引き渡す用のOCR認識結果
 */
data class DomainOcrResult(
    val rawText: String,          // OCR生テキスト
    val cleanedCode: String,      // クリーニング済み製品コード
    val confidence: Float,        // 認識信頼度
    val isValid: Boolean,         // バリデーション結果
    val candidates: List<String> = emptyList(), // 候補リスト
    val polygon: FloatArray? = null // 多角形座標
)
