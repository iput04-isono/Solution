package com.crossvision.f.ocr

import android.content.Context
import android.graphics.*
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.*

/**
 * PaddleOCR（ONNX Runtime）による文字認識エンジン。
 *
 * 【使い方】
 *   val engine = OcrEngine(context)
 *   val preprocessor = ImagePreprocessor()
 *
 *   // 標準（矩形検出）
 *   val results: List<OcrResult> = engine.runOcr(preprocessor.preprocess(bitmap))
 *
 *   // 高精度（多角形検出・斜め文字対応）
 *   val results: List<OcrResult> = engine.runOcrPolygon(preprocessor.preprocess(bitmap))
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
 *   ppocr_rec.onnx テキスト認識モデル（PP-OCRv4 mobile rec + SVTR）
 *   dict.txt       認識文字辞書
 */
class OcrEngine(private val context: Context) {

    companion object {
        /** この信頼度未満の認識結果は誤検出とみなして除外する */
        private const val CONFIDENCE_THRESHOLD = 0.15f

        /** この信頼度未満なら逆向きも試して良い方を採用する */
        private const val RETRY_THRESHOLD = 0.30f

        /** 検出モデルへの入力解像度 */
        private const val DET_SIZE = 640

        /** 1枚の画像で処理する多角形領域の上限 */
        private const val MAX_REGIONS = 8

        /** 認識モデルへの入力幅の上限（推論時間を制限） */
        private const val MAX_REC_WIDTH = 640
    }

    private val env = OrtEnvironment.getEnvironment()
    private var detSession: OrtSession? = null
    private var recSession: OrtSession? = null
    private val labelList = mutableListOf<String>()

    // 多角形検出モードで正向き・逆向きを並列推論するスレッドプール
    private val recPool = Executors.newFixedThreadPool(2)

    init {
        try {
            val numCores = Runtime.getRuntime().availableProcessors().coerceAtMost(4)

            fun makeOpts(threads: Int) = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(threads)
                setInterOpNumThreads(2)
                // NNAPI（NPU/DSP）ハードウェアアクセラレーション
                // 非対応デバイスは自動でCPUフォールバック
                try { addNnapi() } catch (_: Exception) {}
            }

            // 検出は単独実行 → 全コアを使う
            detSession = env.createSession(
                context.assets.open("det.onnx").readBytes(), makeOpts(numCores))

            // 認識は2スレッドが並列実行する → 各セッションはコア数の半分を使う
            // （2スレッド × 2並列 = 合計コア数となり最大効率）
            val recThreads = (numCores / 2).coerceAtLeast(2)
            recSession = env.createSession(
                context.assets.open("ppocr_rec.onnx").readBytes(), makeOpts(recThreads))

            loadLabels()
        } catch (e: Exception) {
            android.util.Log.e("OcrEngine", "モデル読み込みエラー", e)
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // 公開 API
    // ──────────────────────────────────────────────────────────────────────

    /**
     * 矩形検出によるOCR。標準的な使い方。
     *
     * @param  bitmap 前処理済み画像（ImagePreprocessor.preprocess() の出力）
     * @return 認識結果リスト（信頼度 CONFIDENCE_THRESHOLD 以上のもの）
     */
    fun runOcr(bitmap: Bitmap): List<OcrResult> {
        val rawBoxes    = detectText(bitmap)
        val mergedBoxes = mergeRects(rawBoxes)
        val results     = mutableListOf<OcrResult>()

        for (box in mergedBoxes) {
            val cropped  = cropBitmap(bitmap, box)
            val enhanced = enhanceContrast(cropped)
            val result   = recognizeBestOrientation(enhanced)
            if (result.confidence >= CONFIDENCE_THRESHOLD) results.add(result)
        }
        return results
    }

    /**
     * 多角形検出によるOCR。斜め文字・歪んだ文字列に対してより高精度。
     *
     * BFS連結成分 + PCA最小外接矩形 + 透視変換クロップ + 並列認識。
     *
     * @param  bitmap 前処理済み画像（ImagePreprocessor.preprocess() の出力）
     * @return 認識結果リスト（信頼度 CONFIDENCE_THRESHOLD 以上のもの）
     */
    fun runOcrPolygon(bitmap: Bitmap): List<OcrResult> {
        val polygons = detectTextPolygon(bitmap)
        val results  = mutableListOf<OcrResult>()

        for (poly in polygons) {
            val cropped  = perspectiveCrop(bitmap, poly)
            val enhanced = enhanceContrast(cropped)
            val result   = recognizeBestOrientationParallel(enhanced)
            if (result.confidence >= CONFIDENCE_THRESHOLD) results.add(result)
        }
        return results
    }

    /** リソースを解放する。使い終わったら必ず呼ぶ。 */
    fun close() {
        recPool.shutdown()
        detSession?.close()
        recSession?.close()
        env.close()
    }

    // ──────────────────────────────────────────────────────────────────────
    // 向き推定（逆文字・縦書き対応）
    // ──────────────────────────────────────────────────────────────────────

    /**
     * 矩形検出用: 確信度が低い場合に逆向きを試す。
     * アスペクト比から初期方向を決め、RETRY_THRESHOLD 以上なら即採用する高速版。
     */
    private fun recognizeBestOrientation(bitmap: Bitmap): OcrResult {
        val isVertical  = bitmap.height > bitmap.width * 1.2f
        val firstAngle  = if (isVertical) 90f else 0f
        val firstBitmap = if (firstAngle == 0f) bitmap else rotateBitmap(bitmap, firstAngle)

        val first = recognize(firstBitmap)
        if (first.confidence >= RETRY_THRESHOLD) return first

        val second = recognize(rotateBitmap(bitmap, firstAngle + 180f))
        return if (second.confidence > first.confidence) second else first
    }

    /**
     * 多角形検出用: 正向き・逆向きを recPool の2スレッドで並列推論し、
     * 確信度の高い方を返す。
     * OrtSession.run() はスレッドセーフ（ONNX Runtime 1.8+）。
     */
    private fun recognizeBestOrientationParallel(bitmap: Bitmap): OcrResult {
        val isVertical = bitmap.height > bitmap.width * 1.2f
        val angleA     = if (isVertical) 90f else 0f
        val angleB     = angleA + 180f
        val bitmapA    = if (angleA == 0f) bitmap else rotateBitmap(bitmap, angleA)
        val bitmapB    = rotateBitmap(bitmap, angleB)

        val futureA: Future<OcrResult> = recPool.submit<OcrResult> { recognize(bitmapA) }
        val futureB: Future<OcrResult> = recPool.submit<OcrResult> { recognize(bitmapB) }

        val resultA = futureA.get()
        if (resultA.confidence >= RETRY_THRESHOLD) {
            futureB.get() // プールスレッドを解放するため wait
            return resultA
        }
        val resultB = futureB.get()
        return if (resultB.confidence > resultA.confidence) resultB else resultA
    }

    private fun rotateBitmap(bitmap: Bitmap, degrees: Float): Bitmap {
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    // ──────────────────────────────────────────────────────────────────────
    // コントラスト強化（クロップ後に適用）
    // ──────────────────────────────────────────────────────────────────────

    /**
     * 切り出したテキスト領域の輝度を 0〜255 にリニア引き伸ばし（ヒストグラムストレッチ）。
     * 二値化なしでコントラストを上げ、認識モデルが文字を読みやすくする。
     * コントラスト差が小さい（錆でほぼ均一）場合は変換をスキップ。
     */
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
            scale, 0f,    0f,    0f, bias,
            0f,    scale, 0f,    0f, bias,
            0f,    0f,    scale, 0f, bias,
            0f,    0f,    0f,    1f, 0f
        ))
        Canvas(out).drawBitmap(bitmap, 0f, 0f, Paint().apply {
            colorFilter = ColorMatrixColorFilter(cm)
        })
        return out
    }

    // ──────────────────────────────────────────────────────────────────────
    // テキスト検出（DBNet ヒートマップ解析）
    // ──────────────────────────────────────────────────────────────────────

    /** DBNetモデルを実行してヒートマップを返す共通メソッド */
    private fun runDetectionModel(bitmap: Bitmap): Array<FloatArray>? {
        val session = detSession ?: return null
        val resized = Bitmap.createScaledBitmap(bitmap, DET_SIZE, DET_SIZE, true)
        val imgData = FloatBuffer.allocate(1 * 3 * DET_SIZE * DET_SIZE)

        // getPixel()の繰り返しJNI呼び出しを避け、一括取得で高速化
        val pixels = IntArray(DET_SIZE * DET_SIZE)
        resized.getPixels(pixels, 0, DET_SIZE, 0, 0, DET_SIZE, DET_SIZE)
        val detScale = 1f / (255f * 0.229f)
        val detBias  = -0.485f / 0.229f
        for (c in 0 until 3) {
            val shift = when (c) { 0 -> 16; 1 -> 8; else -> 0 }
            for (i in 0 until DET_SIZE * DET_SIZE) {
                imgData.put(((pixels[i] shr shift) and 0xFF) * detScale + detBias)
            }
        }
        imgData.rewind()

        val inputName   = session.inputNames.iterator().next()
        val inputTensor = OnnxTensor.createTensor(env, imgData,
            longArrayOf(1, 3, DET_SIZE.toLong(), DET_SIZE.toLong()))
        return session.run(Collections.singletonMap(inputName, inputTensor)).use { output ->
            extract2DArray(output[0].value)
        }
    }

    /** 矩形検出（標準モード） */
    private fun detectText(bitmap: Bitmap): List<Rect> {
        val heatMap   = runDetectionModel(bitmap) ?: return emptyList()
        val threshold = 0.35f; val step = 10
        val visited   = Array(DET_SIZE) { BooleanArray(DET_SIZE) }
        val boxes     = mutableListOf<Rect>()
        val scaleX    = bitmap.width.toFloat()  / DET_SIZE
        val scaleY    = bitmap.height.toFloat() / DET_SIZE

        for (y in 0 until DET_SIZE step step) {
            for (x in 0 until DET_SIZE step step) {
                if (heatMap[y][x] > threshold && !visited[y][x]) {
                    var minX = x; var maxX = x; var minY = y; var maxY = y
                    for (dy in -20..20 step 5) {
                        for (dx in -50..50 step 5) {
                            val ny = y + dy; val nx = x + dx
                            if (ny in 0 until DET_SIZE && nx in 0 until DET_SIZE
                                && heatMap[ny][nx] > threshold) {
                                minX = min(minX, nx); maxX = max(maxX, nx)
                                minY = min(minY, ny); maxY = max(maxY, ny)
                                visited[ny][nx] = true
                            }
                        }
                    }
                    boxes.add(Rect(
                        (max(0, minX - 10)        * scaleX).toInt(),
                        (max(0, minY - 5)          * scaleY).toInt(),
                        (min(DET_SIZE, maxX + 10)  * scaleX).toInt(),
                        (min(DET_SIZE, maxY + 5)   * scaleY).toInt()
                    ))
                }
            }
        }
        return boxes
    }

    // ──────────────────────────────────────────────────────────────────────
    // 多角形テキスト検出（BFS + PCA + 透視変換）
    // ──────────────────────────────────────────────────────────────────────

    /** 多角形検出: BFS連結成分 → PCA最小外接矩形 → アンクリップ → 面積フィルタ */
    private fun detectTextPolygon(bitmap: Bitmap): List<FloatArray> {
        val heatMap = runDetectionModel(bitmap) ?: return emptyList()
        val scaleX  = bitmap.width.toFloat()  / DET_SIZE
        val scaleY  = bitmap.height.toFloat() / DET_SIZE

        return bfsComponents(heatMap, threshold = 0.38f, minPx = 60)
            .mapNotNull { comp ->
                val rr = pcaMinRect(comp) ?: return@mapNotNull null
                val corners = unclipRect(rr, ratio = 1.5f)
                FloatArray(8) { i -> if (i % 2 == 0) corners[i] * scaleX else corners[i] * scaleY }
            }
            .filter { polygonArea(it) > 300f }
            .sortedByDescending { polygonArea(it) }
            .take(MAX_REGIONS)
    }

    /**
     * BFS連結成分検出。
     * キューをInt（r*W+c）でエンコードしてPairオブジェクト生成によるGC負荷を削減。
     * シードスキャンをstep=2にして初期走査コストを1/4に削減。
     */
    private fun bfsComponents(
        map: Array<FloatArray>,
        threshold: Float,
        minPx: Int = 60
    ): List<List<Pair<Int, Int>>> {
        val H = map.size; val W = map[0].size
        val visited = Array(H) { BooleanArray(W) }
        val result  = mutableListOf<List<Pair<Int, Int>>>()

        for (r in 0 until H step 2) {
            for (c in 0 until W step 2) {
                if (map[r][c] > threshold && !visited[r][c]) {
                    val queue = ArrayDeque<Int>()
                    val comp  = mutableListOf<Pair<Int, Int>>()
                    visited[r][c] = true
                    queue.add(r * W + c)
                    while (queue.isNotEmpty()) {
                        val code = queue.removeFirst()
                        val cr = code / W; val cc = code % W
                        comp.add(cc to cr)
                        if (cr > 0     && !visited[cr-1][cc] && map[cr-1][cc] > threshold) { visited[cr-1][cc] = true; queue.add((cr-1)*W+cc) }
                        if (cr < H - 1 && !visited[cr+1][cc] && map[cr+1][cc] > threshold) { visited[cr+1][cc] = true; queue.add((cr+1)*W+cc) }
                        if (cc > 0     && !visited[cr][cc-1] && map[cr][cc-1] > threshold) { visited[cr][cc-1] = true; queue.add(cr*W+cc-1) }
                        if (cc < W - 1 && !visited[cr][cc+1] && map[cr][cc+1] > threshold) { visited[cr][cc+1] = true; queue.add(cr*W+cc+1) }
                    }
                    if (comp.size >= minPx) result.add(comp)
                }
            }
        }
        return result
    }

    /** PCAによる最小外接回転矩形の推定 */
    private fun pcaMinRect(pts: List<Pair<Int, Int>>): RotatedRect? {
        if (pts.size < 3) return null
        val cx = pts.sumOf { it.first }.toDouble()  / pts.size
        val cy = pts.sumOf { it.second }.toDouble() / pts.size

        var cxx = 0.0; var cxy = 0.0; var cyy = 0.0
        for ((x, y) in pts) {
            val dx = x - cx; val dy = y - cy
            cxx += dx * dx; cxy += dx * dy; cyy += dy * dy
        }
        val n = pts.size.toDouble()
        cxx /= n; cxy /= n; cyy /= n

        val trace = cxx + cyy
        val disc  = sqrt(max(0.0, trace * trace / 4.0 - (cxx * cyy - cxy * cxy)))
        val angle = if (abs(cxy) > 1e-10) atan2(cxy, trace / 2.0 + disc - cyy).toFloat()
                    else if (cxx >= cyy) 0f else (PI / 2).toFloat()

        val cosA = cos(angle.toDouble()); val sinA = sin(angle.toDouble())
        var minX = Double.MAX_VALUE; var maxX = -Double.MAX_VALUE
        var minY = Double.MAX_VALUE; var maxY = -Double.MAX_VALUE
        for ((x, y) in pts) {
            val dx = x - cx; val dy = y - cy
            val rx = dx * cosA + dy * sinA; val ry = -dx * sinA + dy * cosA
            if (rx < minX) minX = rx; if (rx > maxX) maxX = rx
            if (ry < minY) minY = ry; if (ry > maxY) maxY = ry
        }
        return RotatedRect(cx.toFloat(), cy.toFloat(),
            (maxX - minX).toFloat(), (maxY - minY).toFloat(), angle)
    }

    /** 回転矩形をアンクリップ（外側に膨張）して4頂点を返す */
    private fun unclipRect(rr: RotatedRect, ratio: Float = 1.5f): FloatArray {
        val cosA = cos(rr.angle.toDouble()).toFloat()
        val sinA = sin(rr.angle.toDouble()).toFloat()
        val hw   = rr.w / 2 * sqrt(ratio); val hh = rr.h / 2 * sqrt(ratio)
        return floatArrayOf(
            rr.cx + (-hw) * cosA - (-hh) * sinA, rr.cy + (-hw) * sinA + (-hh) * cosA,
            rr.cx +   hw  * cosA - (-hh) * sinA, rr.cy +   hw  * sinA + (-hh) * cosA,
            rr.cx +   hw  * cosA -   hh  * sinA, rr.cy +   hw  * sinA +   hh  * cosA,
            rr.cx + (-hw) * cosA -   hh  * sinA, rr.cy + (-hw) * sinA +   hh  * cosA
        )
    }

    /** 多角形領域を透視変換してクロップ（Android Matrix.setPolyToPoly 使用） */
    private fun perspectiveCrop(bitmap: Bitmap, srcPts: FloatArray): Bitmap {
        fun dist(i: Int, j: Int) = sqrt(
            (srcPts[i*2] - srcPts[j*2]).pow(2) + (srcPts[i*2+1] - srcPts[j*2+1]).pow(2))
        val tw = ((dist(0,1) + dist(3,2)) / 2).toInt().coerceIn(1, 2000)
        val th = ((dist(1,2) + dist(0,3)) / 2).toInt().coerceIn(1, 2000)
        val dstPts = floatArrayOf(0f, 0f, tw.toFloat(), 0f, tw.toFloat(), th.toFloat(), 0f, th.toFloat())
        val matrix = Matrix()
        matrix.setPolyToPoly(srcPts, 0, dstPts, 0, 4)
        val out = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(bitmap, matrix, Paint(Paint.ANTI_ALIAS_FLAG))
        return out
    }

    private fun polygonArea(pts: FloatArray): Float {
        var area = 0f; val n = pts.size / 2
        for (i in 0 until n) {
            val j = (i + 1) % n
            area += pts[i * 2] * pts[j * 2 + 1] - pts[j * 2] * pts[i * 2 + 1]
        }
        return abs(area) / 2f
    }

    private data class RotatedRect(
        val cx: Float, val cy: Float,
        val w: Float,  val h: Float,
        val angle: Float
    )

    // ──────────────────────────────────────────────────────────────────────
    // テキスト認識（ppocr_rec.onnx）
    // ──────────────────────────────────────────────────────────────────────

    private fun recognize(bitmap: Bitmap): OcrResult {
        val session = recSession ?: return OcrResult("", 0f)

        // アスペクト比を保ったまま高さ48に正規化（SVTR動的幅対応）
        val targetH = 48
        val aspect  = bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1)
        val targetW = (targetH * aspect).toInt().coerceIn(32, MAX_REC_WIDTH).let { w ->
            if (w % 32 == 0) w else (w / 32 + 1) * 32
        }

        val resized = Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
        val imgData = FloatBuffer.allocate(1 * 3 * targetH * targetW)

        // getPixel()の繰り返し呼び出しを避け、一括取得で高速化
        val pixels = IntArray(targetH * targetW)
        resized.getPixels(pixels, 0, targetW, 0, 0, targetW, targetH)
        for (c in 0 until 3) {
            val shift = when (c) { 0 -> 16; 1 -> 8; else -> 0 }
            for (i in 0 until targetH * targetW) {
                // (v - 0.5) / 0.5 = v * 2 - 1
                imgData.put(((pixels[i] shr shift) and 0xFF) / 127.5f - 1f)
            }
        }
        imgData.rewind()

        val inputName   = session.inputNames.iterator().next()
        val inputTensor = OnnxTensor.createTensor(env, imgData,
            longArrayOf(1, 3, targetH.toLong(), targetW.toLong()))
        return session.run(Collections.singletonMap(inputName, inputTensor)).use { results ->
            val output = extract2DArray(results[0].value) ?: return OcrResult("", 0f)
            decode(output)
        }
    }

    private fun decode(probabilities: Array<FloatArray>): OcrResult {
        // 第1候補（Greedy Search）
        val sb = StringBuilder()
        var lastIdx = -1
        val confidences = mutableListOf<Float>()
        val charPositions = mutableListOf<Int>() // 文字が確定した時の probabilities インデックス

        for (i in probabilities.indices) {
            val probs = probabilities[i]
            val maxIdx = probs.indices.maxByOrNull { probs[it] } ?: 0
            val conf = probs[maxIdx]
            
            if (maxIdx > 0 && maxIdx != lastIdx && maxIdx < labelList.size) {
                sb.append(labelList[maxIdx])
                confidences.add(conf)
                charPositions.add(i)
            }
            lastIdx = maxIdx
        }

        val primaryText = sb.toString()
        val avgConfidence = if (confidences.isNotEmpty()) confidences.average().toFloat() else 0f
        
        // --- 候補（Candidates）の生成 ---
        val candidates = mutableListOf<String>()
        if (primaryText.isNotEmpty()) {
            // 第2候補の生成: 信頼度が最も低い文字を、その場所の「第2位の文字」で置き換えてみる
            // 信頼度の低い上位2箇所のインデックスを取得
            val sortedIndices = confidences.indices.sortedBy { confidences[it] }
            
            // パターン1: 最も自信がない1文字を第2位の文字に置換
            if (sortedIndices.isNotEmpty()) {
                val weakIdx = sortedIndices[0]
                val probPos = charPositions[weakIdx]
                val probs = probabilities[probPos]
                
                // 第2位のインデックスを探す
                val secondMaxIdx = probs.indices
                    .filter { it != 0 && it < labelList.size }
                    .sortedByDescending { probs[it] }
                    .getOrNull(1) ?: -1
                
                if (secondMaxIdx > 0) {
                    val candidateChars = primaryText.toCharArray()
                    candidateChars[weakIdx] = labelList[secondMaxIdx][0]
                    candidates.add(String(candidateChars))
                }
            }
            
            // パターン2: 2番目に自信がない文字も同様に試す（もしあれば）
            if (sortedIndices.size >= 2) {
                val weakIdx2 = sortedIndices[1]
                val probPos = charPositions[weakIdx2]
                val probs = probabilities[probPos]
                val secondMaxIdx = probs.indices
                    .filter { it != 0 && it < labelList.size }
                    .sortedByDescending { probs[it] }
                    .getOrNull(1) ?: -1
                
                if (secondMaxIdx > 0) {
                    val candidateChars = primaryText.toCharArray()
                    candidateChars[weakIdx2] = labelList[secondMaxIdx][0]
                    candidates.add(String(candidateChars))
                }
            }
        }

        return OcrResult(
            text = primaryText,
            confidence = avgConfidence,
            maxConfidence = if (confidences.isNotEmpty()) confidences.maxOrNull() ?: 0f else 0f,
            minConfidence = if (confidences.isNotEmpty()) confidences.minOrNull() ?: 0f else 0f,
            candidates = candidates.distinct()
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
