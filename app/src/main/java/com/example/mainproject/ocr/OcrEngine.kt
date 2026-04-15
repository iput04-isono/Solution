package com.example.mainproject.ocr

import android.content.Context
import android.graphics.*
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import java.util.*
import kotlin.math.max
import kotlin.math.min

/**
 * PaddleOCR（ONNX Runtime）による文字認識エンジン。
 *
 * 【使い方】
 *   val engine = OcrEngine(context)
 *   val preprocessor = ImagePreprocessor()
 *
 *   val processed = preprocessor.preprocess(bitmap)
 *   val results: List<OcrResult> = engine.runOcr(processed)
 *
 *   for (result in results) {
 *       // result.text       → 認識文字列（例: "BISb30N-7A"）
 *       // result.confidence → 信頼度（0.0〜1.0）
 *   }
 *
 *   engine.close() // 使い終わったら解放
 *
 * 【信頼度の目安（アプリ側UI分岐用）】
 *   ≥ 0.85  → 自動確定候補
 *   0.60〜0.85 → ユーザー確認を促す
 *   < 0.60  → 再撮影を促す
 *
 * 【必要なassets】
 *   det.onnx       テキスト領域検出モデル（PP-OCRv4 DBNet）
 *   ppocr_rec.onnx テキスト認識モデル（PP-OCRv4 mobile rec）
 *   dict.txt       認識文字辞書
 */
class OcrEngine(private val context: Context) {

    companion object {
        /** この信頼度未満の認識結果は誤検出とみなして除外する */
        private const val CONFIDENCE_THRESHOLD = 0.15f

        /** この信頼度未満なら180°回転して再認識し、良い方を採用する */
        private const val RETRY_THRESHOLD = 0.30f
    }

    private val env = OrtEnvironment.getEnvironment()
    private var detSession: OrtSession? = null
    private var recSession: OrtSession? = null
    private val labelList = mutableListOf<String>()

    init {
        try {
            detSession = env.createSession(context.assets.open("det.onnx").readBytes())
            recSession = env.createSession(context.assets.open("ppocr_rec.onnx").readBytes())
            loadLabels()
        } catch (e: Exception) {
            android.util.Log.e("OcrEngine", "モデル読み込みエラー", e)
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // 公開 API
    // ──────────────────────────────────────────────────────────────────────

    /**
     * 画像から文字列を認識する。
     *
     * @param  bitmap 前処理済み画像（ImagePreprocessor.preprocess() の出力）
     * @return 認識結果リスト（信頼度 CONFIDENCE_THRESHOLD 以上のもの）
     */
    fun runOcr(bitmap: Bitmap): List<OcrResult> {
        val rawBoxes   = detectText(bitmap)
        val mergedBoxes = mergeRects(rawBoxes)
        val results    = mutableListOf<OcrResult>()

        for (box in mergedBoxes) {
            val cropped  = cropBitmap(bitmap, box)
            val enhanced = enhanceContrast(cropped)
            val result   = recognizeBestOrientation(enhanced)
            if (result.confidence >= CONFIDENCE_THRESHOLD) {
                results.add(result)
            }
        }
        return results
    }

    /** リソースを解放する。使い終わったら必ず呼ぶ。 */
    fun close() {
        detSession?.close()
        recSession?.close()
        env.close()
    }

    // ──────────────────────────────────────────────────────────────────────
    // 向き推定（逆文字・縦書き対応）
    // ──────────────────────────────────────────────────────────────────────

    /**
     * アスペクト比から初期方向を決め、確信度が低ければ逆向きも試す。
     * 対応: 横書き(0°) / 横書き逆(180°) / 縦書き(90°) / 縦書き逆(270°)
     */
    private fun recognizeBestOrientation(bitmap: Bitmap): OcrResult {
        val isVertical  = bitmap.height > bitmap.width * 1.2f
        val firstAngle  = if (isVertical) 90f else 0f
        val secondAngle = firstAngle + 180f

        val firstBitmap = if (firstAngle == 0f) bitmap else rotateBitmap(bitmap, firstAngle)
        val first = recognize(firstBitmap)
        if (first.confidence >= RETRY_THRESHOLD) return first

        val second = recognize(rotateBitmap(bitmap, secondAngle))
        return if (second.confidence > first.confidence) second else first
    }

    private fun rotateBitmap(bitmap: Bitmap, degrees: Float): Bitmap {
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    // ──────────────────────────────────────────────────────────────────────
    // コントラスト強化（クロップ後に適用）
    // ──────────────────────────────────────────────────────────────────────

    private fun enhanceContrast(bitmap: Bitmap): Bitmap {
        val w = bitmap.width; val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        var minL = 255f; var maxL = 0f
        for (p in pixels) {
            val lum = Color.red(p) * 0.299f + Color.green(p) * 0.587f + Color.blue(p) * 0.114f
            if (lum < minL) minL = lum
            if (lum > maxL) maxL = lum
        }
        val range = maxL - minL
        if (range < 20f) return bitmap

        val scale = 255f / range
        val bias  = -minL * scale
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cm = ColorMatrix(floatArrayOf(
            scale, 0f, 0f, 0f, bias,
            0f, scale, 0f, 0f, bias,
            0f, 0f, scale, 0f, bias,
            0f, 0f, 0f, 1f, 0f
        ))
        Canvas(out).drawBitmap(bitmap, 0f, 0f, Paint().apply {
            colorFilter = ColorMatrixColorFilter(cm)
        })
        return out
    }

    // ──────────────────────────────────────────────────────────────────────
    // テキスト検出（DBNet ヒートマップ解析）
    // ──────────────────────────────────────────────────────────────────────

    private fun detectText(bitmap: Bitmap): List<Rect> {
        val session = detSession ?: return emptyList()
        val detSize = 640
        val resized = Bitmap.createScaledBitmap(bitmap, detSize, detSize, true)
        val imgData = FloatBuffer.allocate(1 * 3 * detSize * detSize)
        for (c in 0 until 3) {
            for (y in 0 until detSize) {
                for (x in 0 until detSize) {
                    val p = resized.getPixel(x, y)
                    val v = when (c) {
                        0 -> Color.red(p); 1 -> Color.green(p); else -> Color.blue(p)
                    } / 255f
                    imgData.put((v - 0.485f) / 0.229f)
                }
            }
        }
        imgData.rewind()

        val inputName   = session.inputNames.iterator().next()
        val inputTensor = OnnxTensor.createTensor(
            env, imgData, longArrayOf(1, 3, detSize.toLong(), detSize.toLong())
        )
        val boxes = mutableListOf<Rect>()
        session.run(Collections.singletonMap(inputName, inputTensor)).use { results ->
            val heatMap = extract2DArray(results[0].value) ?: return emptyList()
            val threshold = 0.35f
            val step = 10
            val visited = Array(detSize) { BooleanArray(detSize) }
            for (y in 0 until detSize step step) {
                for (x in 0 until detSize step step) {
                    if (heatMap[y][x] > threshold && !visited[y][x]) {
                        var minX = x; var maxX = x; var minY = y; var maxY = y
                        for (dy in -20..20 step 5) {
                            for (dx in -50..50 step 5) {
                                val ny = y + dy; val nx = x + dx
                                if (ny in 0 until detSize && nx in 0 until detSize
                                    && heatMap[ny][nx] > threshold) {
                                    minX = min(minX, nx); maxX = max(maxX, nx)
                                    minY = min(minY, ny); maxY = max(maxY, ny)
                                    visited[ny][nx] = true
                                }
                            }
                        }
                        val scaleX = bitmap.width.toFloat()  / detSize
                        val scaleY = bitmap.height.toFloat() / detSize
                        boxes.add(Rect(
                            (max(0, minX - 10) * scaleX).toInt(),
                            (max(0, minY - 5)  * scaleY).toInt(),
                            (min(detSize, maxX + 10) * scaleX).toInt(),
                            (min(detSize, maxY + 5)  * scaleY).toInt()
                        ))
                    }
                }
            }
        }
        return boxes
    }

    // ──────────────────────────────────────────────────────────────────────
    // テキスト認識（SVTR / ppocr_rec.onnx）
    // ──────────────────────────────────────────────────────────────────────

    private fun recognize(bitmap: Bitmap): OcrResult {
        val session = recSession ?: return OcrResult("", 0f)
        val targetH = 48
        val aspect  = bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1)
        val targetW = (targetH * aspect).toInt().coerceIn(32, 1280).let { w ->
            if (w % 32 == 0) w else (w / 32 + 1) * 32
        }

        val resized = Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
        val imgData = FloatBuffer.allocate(1 * 3 * targetH * targetW)
        for (c in 0 until 3) {
            for (y in 0 until targetH) {
                for (x in 0 until targetW) {
                    val p = resized.getPixel(x, y)
                    val v = when (c) {
                        0 -> Color.red(p); 1 -> Color.green(p); else -> Color.blue(p)
                    } / 255f
                    imgData.put((v - 0.5f) / 0.5f)
                }
            }
        }
        imgData.rewind()

        val inputName   = session.inputNames.iterator().next()
        val inputTensor = OnnxTensor.createTensor(
            env, imgData, longArrayOf(1, 3, targetH.toLong(), targetW.toLong())
        )
        session.run(Collections.singletonMap(inputName, inputTensor)).use { results ->
            val output = extract2DArray(results[0].value) ?: return OcrResult("", 0f)
            return decode(output)
        }
    }

    private fun decode(probabilities: Array<FloatArray>): OcrResult {
        val sb = StringBuilder()
        var lastIdx = -1
        var totalScore = 0f; var count = 0
        var maxConf = 0f; var minConf = 1.0f

        for (probs in probabilities) {
            val maxIdx = probs.indices.maxByOrNull { probs[it] } ?: 0
            val conf   = probs[maxIdx]
            if (maxIdx > 0 && maxIdx != lastIdx && maxIdx < labelList.size) {
                sb.append(labelList[maxIdx])
                totalScore += conf; count++
                if (conf > maxConf) maxConf = conf
                if (conf < minConf) minConf = conf
            }
            lastIdx = maxIdx
        }
        val avg = if (count > 0) totalScore / count else 0f
        return OcrResult(
            text          = sb.toString(),
            confidence    = avg,
            maxConfidence = if (count > 0) maxConf else 0f,
            minConfidence = if (count > 0) minConf else 0f
        )
    }

    // ──────────────────────────────────────────────────────────────────────
    // ユーティリティ
    // ──────────────────────────────────────────────────────────────────────

    private fun loadLabels() {
        context.assets.open("dict.txt").bufferedReader().useLines { lines ->
            labelList.add("blank")
            labelList.addAll(lines)
            labelList.add(" ")
        }
    }

    private fun cropBitmap(bitmap: Bitmap, rect: Rect): Bitmap {
        val x = max(0, rect.left); val y = max(0, rect.top)
        val w = min(rect.width(),  bitmap.width  - x)
        val h = min(rect.height(), bitmap.height - y)
        return if (w > 0 && h > 0) Bitmap.createBitmap(bitmap, x, y, w, h) else bitmap
    }

    private fun mergeRects(rects: List<Rect>): List<Rect> {
        if (rects.isEmpty()) return emptyList()
        val result = mutableListOf<Rect>()
        for (rect in rects.sortedBy { it.top }) {
            var merged = false
            for (res in result) {
                if (Rect.intersects(Rect(res).apply { inset(-50, -50) }, rect)) {
                    res.union(rect); merged = true; break
                }
            }
            if (!merged) result.add(Rect(rect))
        }
        return result.filter { it.width() > 20 && it.height() > 20 }
    }

    private fun extract2DArray(value: Any): Array<FloatArray>? {
        return try {
            var current = value
            while (current is Array<*>) {
                if (current.isEmpty()) return null
                val first = current[0]
                if (first is FloatArray) return current as Array<FloatArray>
                current = first as Any
            }
            null
        } catch (e: Exception) { null }
    }
}
