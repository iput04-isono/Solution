package com.crossvision.f.ocr

import android.content.Context
import android.graphics.*
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.FloatBuffer
import java.util.*
import java.util.concurrent.Executors
import kotlin.math.*

/**
 * PaddleOCR（ONNX Runtime）による文字認識エンジン。
 *
 * 改善点（prototype_Ver2.0 からの変更）:
 *   - コントラスト強化: スキップ閾値 20f→5f、パーセンタイルクリッピング追加
 *   - DBNet入力: 縦横比保持＋グレーパディング（アスペクト比が崩れない）
 *   - 向き認識: 曖昧なアスペクト比のとき4方向すべて試す
 *   - クロップ画像をキャッシュに保存し結果に添付（UI表示用）
 */
class OcrEngine(private val context: Context, private val labelMatcher: LabelMatcher? = null) {

    companion object {
        private const val CONFIDENCE_THRESHOLD = 0.15f
        private const val RETRY_THRESHOLD = 0.30f
        private const val DET_SIZE = 640
        private const val DET_THRESHOLD = 0.28f
        private const val BFS_MIN_PX = 25
        private const val MIN_POLY_AREA = 100f
        private const val UNCLIP_RATIO = 2.0f
        private const val MAX_REGIONS = 12
        private const val MAX_REC_WIDTH = 640

        // スキップ閾値: 20f → 5f（錆で輝度差が小さい場合もコントラスト処理する）
        private const val CONTRAST_SKIP_THRESHOLD = 5f
        // パーセンタイルクリッピング: 上下2%の外れ値を除外してからストレッチ
        // 反射光1点があっても stretch が台無しにならない
        private const val CONTRAST_CLIP_PERCENT = 0.02f
    }

    private val env = OrtEnvironment.getEnvironment()
    private var detSession: OrtSession? = null
    private var recSession: OrtSession? = null
    private val labelList = mutableListOf<String>()

    // 4方向認識に対応するためスレッド数を4に拡張
    private val recPool = Executors.newFixedThreadPool(4)

    /** DBNet実行結果：ヒートマップ＋パディング補正情報 */
    private data class DetOutput(
        val heatMap: Array<FloatArray>,
        val padLeft: Int,
        val padTop: Int,
        val scale: Float
    )

    init {
        try {
            val numCores = Runtime.getRuntime().availableProcessors().coerceAtMost(4)
            fun makeOpts(threads: Int) = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(threads)
                setInterOpNumThreads(2)
                try { addNnapi() } catch (_: Exception) {}
            }
            detSession = env.createSession(context.assets.open("det.onnx").readBytes(), makeOpts(numCores))
            val recThreads = (numCores / 2).coerceAtLeast(2)
            recSession = env.createSession(context.assets.open("ppocr_rec.onnx").readBytes(), makeOpts(recThreads))
            loadLabels()
        } catch (e: Exception) {
            android.util.Log.e("OcrEngine", "モデル読み込みエラー", e)
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // 公開 API
    // ──────────────────────────────────────────────────────────────────────

    fun runOcr(bitmap: Bitmap): List<OcrResult> {
        val rawBoxes    = detectText(bitmap)
        val mergedBoxes = mergeRects(rawBoxes)
        val results     = mutableListOf<OcrResult>()
        for (box in mergedBoxes) {
            val cropped  = cropBitmap(bitmap, box)
            val enhanced = enhanceContrast(cropped)
            val result   = recognizeBestOrientation(enhanced)
            if (result.text.isNotEmpty()) results.add(result)
        }
        return results
    }

    fun runOcrPolygon(bitmap: Bitmap): List<OcrResult> = runOcrPolygonInternal(bitmap).second

    fun runOcrPolygonWithOverlay(bitmap: Bitmap): Pair<Bitmap, List<OcrResult>> =
        runOcrPolygonInternal(bitmap)

    private fun runOcrPolygonInternal(bitmap: Bitmap): Pair<Bitmap, List<OcrResult>> {
        val polygons = detectTextPolygon(bitmap)
        val results  = mutableListOf<OcrResult>()

        val overlay = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas  = Canvas(overlay)

        polygons.forEachIndexed { index, poly ->
            val cropped  = perspectiveCrop(bitmap, poly)
            val enhanced = enhanceContrast(cropped)
            val (result, bestAngle) = recognizeBestOrientationParallel(enhanced)
            if (result.text.isEmpty()) return@forEachIndexed

            // 認識に使った角度でクロップ画像を回転（文字が読める向きにする）
            var displayCrop = if (bestAngle == 0f) cropped else rotateBitmap(cropped, bestAngle)
            // 目視確認用に横長を強制（縦長のまま残る場合は追加で90°回転）
            if (displayCrop.height > displayCrop.width) {
                displayCrop = rotateBitmap(displayCrop, 90f)
            }
            val cropPath = saveCropImage(results.size, displayCrop)
            results.add(result.copy(cropImagePath = cropPath))

            val color = confidenceColor(result.confidence)
            val path  = Path().apply {
                moveTo(poly[0], poly[1])
                for (i in 1 until poly.size / 2) lineTo(poly[i * 2], poly[i * 2 + 1])
                close()
            }
            canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 10f
            })
            canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color; style = Paint.Style.STROKE
                strokeWidth = 6f; strokeJoin = Paint.Join.ROUND
            })
            canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = (color and 0x00FFFFFF) or 0x33000000; style = Paint.Style.FILL
            })
            drawBadge(canvas, index + 1, poly[0], poly[1], color)
        }
        return Pair(overlay, results)
    }

    /** クロップ画像をキャッシュに保存してパスを返す（UIでの表示用） */
    private fun saveCropImage(index: Int, bitmap: Bitmap): String? = try {
        val file = File(context.cacheDir, "ocr_crop_$index.jpg")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        file.absolutePath
    } catch (e: Exception) { null }

    private fun confidenceColor(confidence: Float): Int = when {
        confidence >= 0.6f -> Color.rgb(34, 197, 94)
        confidence >= 0.3f -> Color.rgb(251, 191, 36)
        else               -> Color.rgb(239, 68, 68)
    }

    private fun drawBadge(canvas: Canvas, num: Int, x: Float, y: Float, color: Int) {
        val radius = 24f; val cx = x + radius + 4f; val cy = y + radius + 4f
        canvas.drawCircle(cx, cy, radius + 3f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 4f
        })
        canvas.drawCircle(cx, cy, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color; style = Paint.Style.FILL
        })
        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.WHITE; textSize = 28f; isFakeBoldText = true
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("$num", cx, cy - (tp.descent() + tp.ascent()) / 2f, tp)
    }

    fun close() {
        recPool.shutdown()
        detSession?.close()
        recSession?.close()
        env.close()
    }

    // ──────────────────────────────────────────────────────────────────────
    // 向き推定
    // ──────────────────────────────────────────────────────────────────────

    private fun recognizeBestOrientation(bitmap: Bitmap): OcrResult {
        val isVertical  = bitmap.height > bitmap.width * 1.2f
        val firstAngle  = if (isVertical) 90f else 0f
        val firstBitmap = if (firstAngle == 0f) bitmap else rotateBitmap(bitmap, firstAngle)
        val first = recognize(firstBitmap)
        val matcher = labelMatcher
        if (matcher != null) {
            if (matcher.findBest(first.text)?.distance == 0) return first
        } else {
            if (first.confidence >= RETRY_THRESHOLD) return first
        }
        val second = recognize(rotateBitmap(bitmap, firstAngle + 180f))
        return if (matcher != null) {
            val distA = matcher.findBest(first.text)?.distance ?: Int.MAX_VALUE
            val distB = matcher.findBest(second.text)?.distance ?: Int.MAX_VALUE
            when {
                distA < distB -> first; distB < distA -> second
                first.confidence >= second.confidence -> first; else -> second
            }
        } else {
            if (second.confidence > first.confidence) second else first
        }
    }

    /**
     * 多角形検出用: アスペクト比に応じて認識方向を決定。
     *   ratio > 2.0 (明確な横長) → 0°/180° の2方向
     *   ratio < 0.5 (明確な縦長) → 90°/270° の2方向
     *   それ以外 (曖昧)          → 0°/90°/180°/270° の4方向すべて試す
     * 各方向を並列推論し、LabelMatcherがあれば編集距離優先、なければ確信度優先で選択。
     * @return Pair<最良認識結果, 最良角度(度)>  ← 角度はクロップ画像の表示回転に使用
     */
    private fun recognizeBestOrientationParallel(bitmap: Bitmap): Pair<OcrResult, Float> {
        val ratio  = bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1)
        val angles = when {
            ratio > 2.0f -> floatArrayOf(0f, 180f)
            ratio < 0.5f -> floatArrayOf(90f, 270f)
            else         -> floatArrayOf(0f, 90f, 180f, 270f)
        }
        // (角度, Future) を対で保持し、後で indexOf による誤照合が起きないよう index で管理する
        val futures = angles.map { angle ->
            val bmp = if (angle == 0f) bitmap else rotateBitmap(bitmap, angle)
            angle to recPool.submit<OcrResult> { recognize(bmp) }
        }
        val angleResults: List<Pair<Float, OcrResult>> = futures.map { (angle, f) -> angle to f.get() }
        val matcher = labelMatcher
        val best = if (matcher != null) {
            val bestDist = angleResults.minOf { (_, r) -> matcher.findBest(r.text)?.distance ?: Int.MAX_VALUE }
            angleResults
                .filter { (_, r) -> (matcher.findBest(r.text)?.distance ?: Int.MAX_VALUE) == bestDist }
                .maxByOrNull { (_, r) -> r.confidence }
                ?: angleResults.maxByOrNull { (_, r) -> r.confidence }!!
        } else {
            angleResults.maxByOrNull { (_, r) -> r.confidence }!!
        }
        return Pair(best.second, best.first)
    }

    private fun rotateBitmap(bitmap: Bitmap, degrees: Float): Bitmap {
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    // ──────────────────────────────────────────────────────────────────────
    // コントラスト強化（パーセンタイルクリッピング付きヒストグラムストレッチ）
    // ──────────────────────────────────────────────────────────────────────

    /**
     * 上下 CONTRAST_CLIP_PERCENT の外れ値（反射光・極端な影）を除外してから
     * 輝度を 0〜255 にリニア引き伸ばし。
     * 輝度差が CONTRAST_SKIP_THRESHOLD 未満（錆で均一）の場合はスキップ。
     */
    private fun enhanceContrast(bitmap: Bitmap): Bitmap {
        val w = bitmap.width; val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        val lums = FloatArray(pixels.size) { i ->
            Color.red(pixels[i]) * 0.299f + Color.green(pixels[i]) * 0.587f + Color.blue(pixels[i]) * 0.114f
        }
        lums.sort()
        val loIdx = (lums.size * CONTRAST_CLIP_PERCENT).toInt().coerceIn(0, lums.size - 1)
        val hiIdx = (lums.size * (1f - CONTRAST_CLIP_PERCENT)).toInt().coerceIn(0, lums.size - 1)
        val lo = lums[loIdx]; val hi = lums[hiIdx]
        val range = hi - lo
        if (range < CONTRAST_SKIP_THRESHOLD) return bitmap

        val scale = 255f / range
        val bias  = -lo * scale
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

    /**
     * DBNetモデルを実行。
     * アスペクト比を保ちながら DET_SIZE に収め、グレー(128)でパディング。
     * 返り値の DetOutput にパディング量・スケールを含め、座標変換に使用する。
     */
    private fun runDetectionModel(bitmap: Bitmap): DetOutput? {
        val session = detSession ?: return null

        val scale   = minOf(DET_SIZE.toFloat() / bitmap.width, DET_SIZE.toFloat() / bitmap.height)
        val scaledW = (bitmap.width  * scale).toInt().coerceAtLeast(1)
        val scaledH = (bitmap.height * scale).toInt().coerceAtLeast(1)
        val padLeft = (DET_SIZE - scaledW) / 2
        val padTop  = (DET_SIZE - scaledH) / 2

        val scaledBitmap = Bitmap.createScaledBitmap(bitmap, scaledW, scaledH, true)
        val padded = Bitmap.createBitmap(DET_SIZE, DET_SIZE, Bitmap.Config.ARGB_8888)
        Canvas(padded).apply {
            drawColor(Color.rgb(128, 128, 128))
            drawBitmap(scaledBitmap, padLeft.toFloat(), padTop.toFloat(), null)
        }
        scaledBitmap.recycle()

        val imgData = FloatBuffer.allocate(1 * 3 * DET_SIZE * DET_SIZE)
        val pixels  = IntArray(DET_SIZE * DET_SIZE)
        padded.getPixels(pixels, 0, DET_SIZE, 0, 0, DET_SIZE, DET_SIZE)
        padded.recycle()

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
        val heatMap = session.run(Collections.singletonMap(inputName, inputTensor)).use { output ->
            extract2DArray(output[0].value)
        } ?: return null

        return DetOutput(heatMap, padLeft, padTop, scale)
    }

    private fun detectText(bitmap: Bitmap): List<Rect> {
        val det       = runDetectionModel(bitmap) ?: return emptyList()
        val heatMap   = det.heatMap
        val threshold = 0.35f; val step = 10
        val visited   = Array(DET_SIZE) { BooleanArray(DET_SIZE) }
        val boxes     = mutableListOf<Rect>()

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
                    fun hmX(hx: Int) = ((hx - det.padLeft) / det.scale).toInt()
                    fun hmY(hy: Int) = ((hy - det.padTop)  / det.scale).toInt()
                    boxes.add(Rect(
                        max(0, hmX(minX - 10)),
                        max(0, hmY(minY - 5)),
                        min(bitmap.width,  hmX(maxX + 10)),
                        min(bitmap.height, hmY(maxY + 5))
                    ))
                }
            }
        }
        return boxes
    }

    // ──────────────────────────────────────────────────────────────────────
    // 多角形テキスト検出（BFS + PCA + 透視変換）
    // ──────────────────────────────────────────────────────────────────────

    private fun detectTextPolygon(bitmap: Bitmap): List<FloatArray> {
        val det = runDetectionModel(bitmap) ?: return emptyList()

        return bfsComponents(det.heatMap, threshold = DET_THRESHOLD, minPx = BFS_MIN_PX)
            .mapNotNull { comp ->
                val rr = pcaMinRect(comp) ?: return@mapNotNull null
                val corners = unclipRect(rr, ratio = UNCLIP_RATIO)
                // ヒートマップ座標 → 元画像座標（パディング・スケールを補正）
                FloatArray(8) { i ->
                    if (i % 2 == 0)
                        ((corners[i] - det.padLeft) / det.scale).coerceIn(0f, bitmap.width.toFloat())
                    else
                        ((corners[i] - det.padTop)  / det.scale).coerceIn(0f, bitmap.height.toFloat())
                }
            }
            .filter { polygonArea(it) > MIN_POLY_AREA }
            .sortedByDescending { polygonArea(it) }
            .take(MAX_REGIONS)
    }

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
        val targetH = 48
        val aspect  = bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1)
        val targetW = (targetH * aspect).toInt().coerceIn(32, MAX_REC_WIDTH).let { w ->
            if (w % 32 == 0) w else (w / 32 + 1) * 32
        }
        val resized = Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
        val imgData = FloatBuffer.allocate(1 * 3 * targetH * targetW)
        val pixels  = IntArray(targetH * targetW)
        resized.getPixels(pixels, 0, targetW, 0, 0, targetW, targetH)
        for (c in 0 until 3) {
            val shift = when (c) { 0 -> 16; 1 -> 8; else -> 0 }
            for (i in 0 until targetH * targetW) {
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
        val sb = StringBuilder()
        var lastIdx    = -1
        var totalScore = 0f; var count = 0
        var maxConf    = 0f; var minConf = 1.0f

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
