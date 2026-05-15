package com.crossvision.f.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * PaddleOCR (ONNX) を使用したOCR処理クラス
 * OcrEngine.runFullOcr() を呼び出し、オーバーレイ描画・クロップ保存・ラベル照合を行う
 */
class OcrProcessor private constructor(private val context: Context) {

    companion object {
        @Volatile
        private var instance: OcrProcessor? = null

        fun getInstance(context: Context): OcrProcessor {
            return instance ?: synchronized(this) {
                instance ?: OcrProcessor(context.applicationContext).also { instance = it }
            }
        }
    }

    private var engine: OcrEngine? = null
    private val preprocessor = ImagePreprocessor()
    private var labelMatcher: LabelMatcher? = null

    /**
     * 画像からテキストを認識する
     */
    suspend fun recognizeText(
        bitmap: Bitmap,
        maxPolygons: Int = 12,
        detectOnly: Boolean = false
    ): List<DomainOcrResult> = withContext(Dispatchers.Default) {
        if (labelMatcher == null) labelMatcher = LabelMatcher.create(context)
        if (engine == null) engine = OcrEngine(context)

        val processedImage = preprocessor.preprocess(bitmap)
        val output = engine!!.runFullOcr(processedImage, maxPolygons, detectOnly)

        output.items.mapNotNull { item ->
            val text = item.result.text.trim()
            // 検出のみモードの場合はテキストが空でも処理を継続する
            if (!detectOnly && text.isEmpty()) return@mapNotNull null

            val cleanedCode = ProductCodeValidator.cleanProductCode(text)
            val validation = ProductCodeValidator.validate(cleanedCode)
            val match = labelMatcher!!.findBest(cleanedCode)
            val topCandidates = labelMatcher!!.findTopCandidates(cleanedCode, maxResults = 3)

            DomainOcrResult(
                rawText = text,
                cleanedCode = cleanedCode,
                confidence = item.result.confidence,
                isValid = validation.isValid,
                matchedLabel = match?.label,
                matchDistance = match?.distance ?: Int.MAX_VALUE,
                isExactMatch = match?.isExactMatch ?: false,
                labelCandidates = topCandidates.map { it.label },
                polygon = item.polygon
            )
        }
    }

    /**
     * 複数製品の一括認識
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
            if (labelMatcher == null) labelMatcher = LabelMatcher.create(context)
            if (engine == null) engine = OcrEngine(context)

            val processedImage = preprocessor.preprocess(bitmap)
            val output = engine!!.runFullOcr(processedImage)

            // 検出ポリゴンをオリジナル画像上に描画
            val overlayBitmap = buildOverlay(processedImage, output.items)

            val domainResults = output.items.mapIndexedNotNull { idx, item ->
                val text = item.result.text.trim()
                if (text.isEmpty()) return@mapIndexedNotNull null

                val cleanedCode   = ProductCodeValidator.cleanProductCode(text)
                val validation    = ProductCodeValidator.validate(cleanedCode)
                val match         = labelMatcher!!.findBest(cleanedCode)
                val topCandidates = labelMatcher!!.findTopCandidates(cleanedCode, maxResults = 3)
                // 認識に使用したビットマップ（向き補正済み）をクロップ画像として保存
                val cropPath = saveCropImage(idx, item.recognitionBitmap)

                DomainOcrResult(
                    rawText         = text,
                    cleanedCode     = cleanedCode,
                    confidence      = item.result.confidence,
                    isValid         = validation.isValid,
                    matchedLabel    = match?.label,
                    matchDistance   = match?.distance ?: Int.MAX_VALUE,
                    isExactMatch    = match?.isExactMatch ?: false,
                    labelCandidates = topCandidates.map { it.label },
                    cropImagePath   = cropPath,
                    polygon         = item.polygon
                )
            }
            Pair(overlayBitmap, domainResults)
        }

    /** 検出ポリゴンと番号バッジを元画像上に描画してオーバーレイ画像を返す */
    private fun buildOverlay(bitmap: Bitmap, items: List<OcrEngine.OcrDetectionItem>): Bitmap {
        val overlay = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(overlay)

        items.forEachIndexed { idx, item ->
            val poly = item.polygon
            val conf = item.result.confidence
            val color = when {
                conf >= 0.6f -> Color.rgb(34, 197, 94)   // 緑: 高信頼度
                conf >= 0.3f -> Color.rgb(251, 191, 36)  // 黄: 中信頼度
                else         -> Color.rgb(239, 68, 68)   // 赤: 低信頼度
            }

            val path = Path().apply {
                moveTo(poly[0], poly[1])
                for (i in 1 until poly.size / 2) lineTo(poly[i * 2], poly[i * 2 + 1])
                close()
            }

            // 白い縁（視認性向上）
            canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = Color.WHITE
                style = Paint.Style.STROKE
                strokeWidth = 10f
            })
            // 信頼度カラーの縁
            canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color
                style = Paint.Style.STROKE
                strokeWidth = 6f
                strokeJoin = Paint.Join.ROUND
            })
            // 半透明塗り
            canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = (color and 0x00FFFFFF) or 0x33000000
                style = Paint.Style.FILL
            })

            drawBadge(canvas, idx + 1, poly[0], poly[1], color)
        }
        return overlay
    }

    /** 番号バッジ（丸＋数字）を描画する */
    private fun drawBadge(canvas: Canvas, num: Int, x: Float, y: Float, color: Int) {
        val r = 22f
        canvas.drawCircle(x, y, r, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
        })
        canvas.drawText(
            num.toString(), x, y + 8f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = Color.WHITE
                textSize = 22f
                textAlign = Paint.Align.CENTER
                typeface = Typeface.DEFAULT_BOLD
            }
        )
    }

    /** 認識済みクロップ画像をキャッシュに保存してパスを返す */
    private fun saveCropImage(index: Int, bitmap: Bitmap): String? = try {
        val file = File(context.cacheDir, "ocr_crop_$index.jpg")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        file.absolutePath
    } catch (e: Exception) {
        null
    }

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
    val labelCandidates: List<String> = emptyList(), // 候補ラベル上位3件
    val cropImagePath: String? = null,        // クロップ画像キャッシュパス（UI表示用）
    val polygon: FloatArray? = null           // 検出領域のポリゴン座標（[x0, y0, x1, y1, ...]）
) {
    /** 確定表示用コード：ラベルマッチがあれば正解ラベルを、未マッチならOCR結果を使用 */
    val displayCode: String
        get() = matchedLabel ?: cleanedCode
}
