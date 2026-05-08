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
    private var labelMatcher: LabelMatcher? = null

    /**
     * 画像からテキストを認識する
     * @param bitmap 撮影画像
     * @return 認識結果のリスト（製品コード候補情報）
     */
    suspend fun recognizeText(bitmap: Bitmap): List<DomainOcrResult> = withContext(Dispatchers.Default) {
        // バックグラウンドスレッドで重いモデルを初期化する
        // LabelMatcherを渡すことで向き選択をラベル距離優先にする
        if (labelMatcher == null) {
            labelMatcher = LabelMatcher.create(context)
        }
        if (engine == null) {
            engine = OcrEngine(context, labelMatcher!!)
        }

        val processedImage = preprocessor.preprocess(bitmap)
        // 多角形検出(runOcrPolygon)または標準矩形検出(runOcr)を選択。
        // パイプや鉄骨など斜めの文字に強い多角形検出をデフォルト使用。
        val rawResults = engine!!.runOcrPolygon(processedImage)
        
        val ocrResults = mutableListOf<DomainOcrResult>()

        for (res in rawResults) {
            val text = res.text.trim()
            if (text.isEmpty()) continue

            val cleanedCode = ProductCodeValidator.cleanProductCode(text)
            val validation = ProductCodeValidator.validate(cleanedCode)

            val match = labelMatcher!!.findBest(cleanedCode)
            val topCandidates = labelMatcher!!.findTopCandidates(cleanedCode, maxResults = 3)

            // ラベル距離 > 3（正解ラベルに近い候補なし）は除外
            if (match == null) continue

            ocrResults.add(
                DomainOcrResult(
                    rawText = text,
                    cleanedCode = cleanedCode,
                    confidence = res.confidence,
                    isValid = validation.isValid,
                    matchedLabel = match.label,
                    matchDistance = match.distance,
                    isExactMatch = match.isExactMatch,
                    labelCandidates = topCandidates.map { it.label }
                )
            )
        }
        ocrResults
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
        maxResults: Int = Int.MAX_VALUE
    ): List<DomainOcrResult> {
        val results = recognizeText(bitmap)
        return if (maxResults == Int.MAX_VALUE) results else results.take(maxResults)
    }

    /**
     * OCR実行 + 検出領域オーバーレイ画像を同時に返す。
     * matchedLabel != null → ラベル距離 ≤ 3（登録候補）
     * matchedLabel == null → ラベル距離 > 3（参考表示用）
     */
    suspend fun recognizeWithOverlay(bitmap: Bitmap): Pair<Bitmap, List<DomainOcrResult>> =
        withContext(Dispatchers.Default) {
            if (labelMatcher == null) {
                labelMatcher = LabelMatcher.create(context)
            }
            if (engine == null) engine = OcrEngine(context, labelMatcher!!)

            val processedImage = preprocessor.preprocess(bitmap)
            val (overlayBitmap, rawResults) = engine!!.runOcrPolygonWithOverlay(processedImage)

            val domainResults = rawResults.mapNotNull { res ->
                val text = res.text.trim()
                if (text.isEmpty()) return@mapNotNull null
                val cleanedCode   = ProductCodeValidator.cleanProductCode(text)
                val validation    = ProductCodeValidator.validate(cleanedCode)
                val match         = labelMatcher!!.findBest(cleanedCode)
                val topCandidates = labelMatcher!!.findTopCandidates(cleanedCode, maxResults = 3)
                DomainOcrResult(
                    rawText         = text,
                    cleanedCode     = cleanedCode,
                    confidence      = res.confidence,
                    isValid         = validation.isValid,
                    matchedLabel    = match?.label,          // null = 距離 > 3
                    matchDistance   = match?.distance ?: Int.MAX_VALUE,
                    isExactMatch    = match?.isExactMatch ?: false,
                    labelCandidates = topCandidates.map { it.label }
                )
            }
            Pair(overlayBitmap, domainResults)
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
    val rawText: String,                      // OCR生テキスト
    val cleanedCode: String,                  // クリーニング済み製品コード
    val confidence: Float,                    // 認識信頼度
    val isValid: Boolean,                     // フォーマットバリデーション結果
    val matchedLabel: String? = null,         // 正解ラベルとの照合結果（null=未マッチ）
    val matchDistance: Int = Int.MAX_VALUE,   // 編集距離（0=完全一致）
    val isExactMatch: Boolean = false,        // 完全一致フラグ
    val labelCandidates: List<String> = emptyList() // 候補ラベル上位3件
) {
    /** 確定表示用コード：ラベルマッチがあれば正解ラベルを、未マッチならOCR結果を使用 */
    val displayCode: String
        get() = matchedLabel ?: cleanedCode
}
