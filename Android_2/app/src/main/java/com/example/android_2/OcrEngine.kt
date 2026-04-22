package com.example.android_2

import android.content.Context
import android.graphics.*
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import java.util.Collections
import kotlin.math.*

class OcrEngine(private val context: Context) {

    companion object {
        private const val DET_SIZE = 512
        private const val MAX_POLYGON_REGIONS = 10
        private const val MAX_REC_WIDTH = 640
    }

    private val env = OrtEnvironment.getEnvironment()
    private var detSession: OrtSession? = null
    private var recSession: OrtSession? = null
    private val labelList = mutableListOf<String>()

    enum class ColorMode(val label: String) {
        NONE("指定なし"),
        YELLOW("黄色"),
        GREEN("緑"),
        BLUE("青"),
        RED("赤"),
        PINK("ピンク"),
        CYAN("シアン"),
        WHITE("白"),
        BLACK("黒")
    }

    data class OcrOutput(
        val originalBitmap: Bitmap,
        val items: List<OcrDetectionItem>
    )

    data class OcrDetectionItem(
        val index: Int,
        val rect: Rect?,
        val polygon: FloatArray?,
        val croppedBitmap: Bitmap,
        val normalResult: OcrResult,
        val rotated180Result: OcrResult,
        val normalSuggestions: List<ProductCodeMatcher.MatchedCode> = emptyList(),
        val rotated180Suggestions: List<ProductCodeMatcher.MatchedCode> = emptyList()
    )

    private data class RotatedRect(
        val cx: Float,
        val cy: Float,
        val w: Float,
        val h: Float,
        val angle: Float
    )

    init {
        try {
            val numCores = Runtime.getRuntime().availableProcessors().coerceAtMost(4)

            fun makeOpts(threads: Int) = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(threads)
                setInterOpNumThreads(2)
                try { addNnapi() } catch (_: Exception) {}
            }

            detSession = env.createSession(
                context.assets.open("det.onnx").readBytes(),
                makeOpts(numCores)
            )

            val recThreads = (numCores / 2).coerceAtLeast(2)
            recSession = env.createSession(
                context.assets.open("ppocr_rec.onnx").readBytes(),
                makeOpts(recThreads)
            )

            loadLabels()
        } catch (e: Exception) {
            android.util.Log.e("OcrEngine", "Model load error", e)
        }
    }

    fun runFullOcr(
        originalBitmap: Bitmap,
        colorMode: ColorMode = ColorMode.NONE
    ): OcrOutput {
        val filteredBitmap = applySelectedColorFilter(originalBitmap, colorMode)
        return runPolygonOcr(originalBitmap, filteredBitmap)
    }

    private fun runPolygonOcr(
        originalBitmap: Bitmap,
        filteredBitmap: Bitmap
    ): OcrOutput {
        val polygons = detectTextPolygon(filteredBitmap)
        val items = mutableListOf<OcrDetectionItem>()

        for ((i, poly) in polygons.withIndex()) {
            val expandedPoly = expandPolygon(
                polygon = poly,
                imageWidth = filteredBitmap.width,
                imageHeight = filteredBitmap.height,
                scale = 1.32f
            )

            val boundingRect = expandRectForRecognition(
                rect = polygonToBoundingRect(expandedPoly),
                imageWidth = filteredBitmap.width,
                imageHeight = filteredBitmap.height,
                expandXRatio = 0.20f,
                expandYRatio = 0.14f,
                minExpandX = 24,
                minExpandY = 12
            )

            val displayCropRaw = perspectiveCrop(originalBitmap, expandedPoly)
            val polyFiltered = perspectiveCrop(filteredBitmap, expandedPoly)
            val rectFiltered = cropBitmap(filteredBitmap, boundingRect)

            val recognitionBase = if (polyFiltered.width >= rectFiltered.width * 0.7f) {
                polyFiltered
            } else {
                rectFiltered
            }

            val displayCrop = normalizeToHorizontal(displayCropRaw)
            val normalized = normalizeToHorizontal(recognitionBase)
            val (normalResult, rotated180Result) = recognizeBothDirections(normalized)

            if (normalResult.text.isNotBlank() || rotated180Result.text.isNotBlank()) {
                items.add(
                    OcrDetectionItem(
                        index = i + 1,
                        rect = boundingRect,
                        polygon = expandedPoly,
                        croppedBitmap = displayCrop,
                        normalResult = normalResult,
                        rotated180Result = rotated180Result
                    )
                )
            }
        }

        return OcrOutput(originalBitmap = originalBitmap, items = items)
    }

    private fun loadLabels() {
        context.assets.open("dict.txt").bufferedReader().useLines { lines ->
            labelList.add("blank")
            labelList.addAll(lines)
            labelList.add(" ")
        }
    }

    private fun applySelectedColorFilter(bitmap: Bitmap, colorMode: ColorMode): Bitmap {
        if (colorMode == ColorMode.NONE) return bitmap

        val width = bitmap.width
        val height = bitmap.height
        val srcPixels = IntArray(width * height)
        val outPixels = IntArray(width * height)

        bitmap.getPixels(srcPixels, 0, width, 0, 0, width, height)
        val hsv = FloatArray(3)

        for (i in srcPixels.indices) {
            val p = srcPixels[i]
            Color.RGBToHSV(Color.red(p), Color.green(p), Color.blue(p), hsv)
            val h = hsv[0]
            val s = hsv[1]
            val v = hsv[2]

            val matched = when (colorMode) {
                ColorMode.YELLOW -> h in 35f..75f && s > 0.20f && v > 0.20f
                ColorMode.GREEN -> h in 75f..165f && s > 0.18f && v > 0.18f
                ColorMode.BLUE -> h in 180f..255f && s > 0.18f && v > 0.18f
                ColorMode.RED -> (h in 0f..20f || h >= 340f) && s > 0.20f && v > 0.18f
                ColorMode.PINK -> h in 300f..340f && s > 0.20f && v > 0.20f
                ColorMode.CYAN -> h in 160f..195f && s > 0.18f && v > 0.18f
                ColorMode.WHITE -> s < 0.18f && v > 0.72f
                ColorMode.BLACK -> v < 0.28f
                ColorMode.NONE -> true
            }

            outPixels[i] = if (matched) Color.BLACK else Color.WHITE
        }

        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            setPixels(outPixels, 0, width, 0, 0, width, height)
        }
    }

    private fun addRecognitionPadding(
        bitmap: Bitmap,
        padXRatio: Float = 0.10f,
        padYRatio: Float = 0.18f,
        minPadX: Int = 24,
        minPadY: Int = 16
    ): Bitmap {
        val padX = max(minPadX, (bitmap.width * padXRatio).toInt())
        val padY = max(minPadY, (bitmap.height * padYRatio).toInt())

        return Bitmap.createBitmap(
            bitmap.width + padX * 2,
            bitmap.height + padY * 2,
            Bitmap.Config.ARGB_8888
        ).also { out ->
            val canvas = Canvas(out)
            canvas.drawColor(Color.WHITE)
            canvas.drawBitmap(bitmap, padX.toFloat(), padY.toFloat(), null)
        }
    }

    private val recPool = java.util.concurrent.Executors.newFixedThreadPool(2)

    private fun recognizeBothDirections(bitmap: Bitmap): Pair<OcrResult, OcrResult> {
        val padded = addRecognitionPadding(bitmap)
        val enhanced = enhanceContrast(padded)
        val rotatedBitmap = rotateBitmap(enhanced, 180f)

        val futureNormal = recPool.submit<OcrResult> { recognize(enhanced) }
        val futureRotated = recPool.submit<OcrResult> { recognize(rotatedBitmap) }

        return futureNormal.get() to futureRotated.get()
    }



    private fun normalizeToHorizontal(bitmap: Bitmap): Bitmap {
        return if (bitmap.height > bitmap.width * 1.2f) rotateBitmap(bitmap, 90f) else bitmap
    }

    private fun enhanceContrast(bitmap: Bitmap): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        var minL = 255f
        var maxL = 0f

        for (p in pixels) {
            val lum = Color.red(p) * 0.299f + Color.green(p) * 0.587f + Color.blue(p) * 0.114f
            if (lum < minL) minL = lum
            if (lum > maxL) maxL = lum
        }

        val range = maxL - minL
        if (range < 20f) return bitmap

        val scale = 255f / range
        val bias = -minL * scale
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cm = ColorMatrix(
            floatArrayOf(
                scale, 0f, 0f, 0f, bias,
                0f, scale, 0f, 0f, bias,
                0f, 0f, scale, 0f, bias,
                0f, 0f, 0f, 1f, 0f
            )
        )

        Canvas(out).drawBitmap(bitmap, 0f, 0f, Paint().apply {
            colorFilter = ColorMatrixColorFilter(cm)
        })
        return out
    }

    private fun recognize(bitmap: Bitmap): OcrResult {
        val session = recSession ?: return OcrResult("Error", 0f, 0f, 0f)

        val targetH = 48
        val aspect = bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1)
        val targetW = (targetH * aspect).toInt().coerceIn(32, MAX_REC_WIDTH).let { w ->
            if (w % 32 == 0) w else (w / 32 + 1) * 32
        }

        val resized = Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
        val pixels = IntArray(targetH * targetW)
        resized.getPixels(pixels, 0, targetW, 0, 0, targetW, targetH)

        val imgData = FloatBuffer.allocate(1 * 3 * targetH * targetW)
        for (c in 0 until 3) {
            val shift = when (c) { 0 -> 16; 1 -> 8; else -> 0 }
            for (i in pixels.indices) {
                imgData.put(((pixels[i] shr shift) and 0xFF) / 127.5f - 1f)
            }
        }
        imgData.rewind()

        val inputName = session.inputNames.iterator().next()
        val inputTensor = OnnxTensor.createTensor(
            env, imgData, longArrayOf(1, 3, targetH.toLong(), targetW.toLong())
        )

        session.run(Collections.singletonMap(inputName, inputTensor)).use { results ->
            val output = extract2DArray(results[0].value)
                ?: return OcrResult("FormatErr", 0f, 0f, 0f)
            return decode(output)
        }
    }

    private fun decode(probabilities: Array<FloatArray>): OcrResult {
        val sb = StringBuilder()
        var lastIdx = -1
        var totalScore = 0f
        var count = 0
        var maxConf = 0f
        var minConf = 1.0f

        for (probs in probabilities) {
            val maxIdx = probs.indices.maxByOrNull { probs[it] } ?: 0
            val conf = probs[maxIdx]

            if (maxIdx > 0 && maxIdx != lastIdx && maxIdx < labelList.size) {
                sb.append(labelList[maxIdx])
                totalScore += conf
                count++
                if (conf > maxConf) maxConf = conf
                if (conf < minConf) minConf = conf
            }
            lastIdx = maxIdx
        }

        val avg = if (count > 0) totalScore / count else 0f
        return OcrResult(
            text = sb.toString(),
            confidence = avg,
            maxConfidence = if (count > 0) maxConf else 0f,
            minConfidence = if (count > 0) minConf else 0f
        )
    }

    private fun runDetectionModel(bitmap: Bitmap): Array<FloatArray>? {
        val session = detSession ?: return null
        val resized = Bitmap.createScaledBitmap(bitmap, DET_SIZE, DET_SIZE, true)
        val pixels = IntArray(DET_SIZE * DET_SIZE)
        resized.getPixels(pixels, 0, DET_SIZE, 0, 0, DET_SIZE, DET_SIZE)

        val imgData = FloatBuffer.allocate(1 * 3 * DET_SIZE * DET_SIZE)
        for (c in 0 until 3) {
            val shift = when (c) { 0 -> 16; 1 -> 8; else -> 0 }
            for (i in pixels.indices) {
                val v = ((pixels[i] shr shift) and 0xFF) / 255f
                imgData.put((v - 0.485f) / 0.229f)
            }
        }
        imgData.rewind()

        val inputName = session.inputNames.iterator().next()
        val inputTensor = OnnxTensor.createTensor(
            env, imgData, longArrayOf(1, 3, DET_SIZE.toLong(), DET_SIZE.toLong())
        )

        return session.run(Collections.singletonMap(inputName, inputTensor)).use { output ->
            extract2DArray(output[0].value)
        }
    }

    private fun detectTextPolygon(bitmap: Bitmap): List<FloatArray> {
        val heatMap = runDetectionModel(bitmap) ?: return emptyList()
        val scaleX = bitmap.width.toFloat() / DET_SIZE
        val scaleY = bitmap.height.toFloat() / DET_SIZE

        return bfsComponents(heatMap, threshold = 0.38f, minPx = 60)
            .mapNotNull { comp ->
                val rr = pcaMinRect(comp) ?: return@mapNotNull null
                val corners = unclipRect(rr, ratio = 1.5f)
                FloatArray(8) { i -> if (i % 2 == 0) corners[i] * scaleX else corners[i] * scaleY }
            }
            .filter { polygonArea(it) > 300f }
            .sortedByDescending { polygonArea(it) }
            .take(MAX_POLYGON_REGIONS)
    }

    private fun polygonToBoundingRect(polygon: FloatArray): Rect {
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE

        for (i in polygon.indices step 2) {
            val x = polygon[i]
            val y = polygon[i + 1]
            if (x < minX) minX = x
            if (y < minY) minY = y
            if (x > maxX) maxX = x
            if (y > maxY) maxY = y
        }

        return Rect(minX.toInt(), minY.toInt(), maxX.toInt(), maxY.toInt())
    }

    private fun bfsComponents(
        map: Array<FloatArray>,
        threshold: Float,
        minPx: Int = 60
    ): List<List<Pair<Int, Int>>> {
        val h = map.size
        val w = map[0].size
        val visited = Array(h) { BooleanArray(w) }
        val result = mutableListOf<List<Pair<Int, Int>>>()

        for (r in 0 until h step 2) {
            for (c in 0 until w step 2) {
                if (map[r][c] > threshold && !visited[r][c]) {
                    val queue = ArrayDeque<Int>()
                    val comp = mutableListOf<Pair<Int, Int>>()
                    visited[r][c] = true
                    queue.add(r * w + c)

                    while (queue.isNotEmpty()) {
                        val code = queue.removeFirst()
                        val cr = code / w
                        val cc = code % w
                        comp.add(cc to cr)

                        if (cr > 0 && !visited[cr - 1][cc] && map[cr - 1][cc] > threshold) {
                            visited[cr - 1][cc] = true
                            queue.add((cr - 1) * w + cc)
                        }
                        if (cr < h - 1 && !visited[cr + 1][cc] && map[cr + 1][cc] > threshold) {
                            visited[cr + 1][cc] = true
                            queue.add((cr + 1) * w + cc)
                        }
                        if (cc > 0 && !visited[cr][cc - 1] && map[cr][cc - 1] > threshold) {
                            visited[cr][cc - 1] = true
                            queue.add(cr * w + cc - 1)
                        }
                        if (cc < w - 1 && !visited[cr][cc + 1] && map[cr][cc + 1] > threshold) {
                            visited[cr][cc + 1] = true
                            queue.add(cr * w + cc + 1)
                        }
                    }

                    if (comp.size >= minPx) result.add(comp)
                }
            }
        }

        return result
    }

    private fun pcaMinRect(pts: List<Pair<Int, Int>>): RotatedRect? {
        if (pts.size < 3) return null

        val cx = pts.sumOf { it.first }.toDouble() / pts.size
        val cy = pts.sumOf { it.second }.toDouble() / pts.size

        var cxx = 0.0
        var cxy = 0.0
        var cyy = 0.0

        for ((x, y) in pts) {
            val dx = x - cx
            val dy = y - cy
            cxx += dx * dx
            cxy += dx * dy
            cyy += dy * dy
        }

        val n = pts.size.toDouble()
        cxx /= n
        cxy /= n
        cyy /= n

        val trace = cxx + cyy
        val disc = sqrt(max(0.0, trace * trace / 4.0 - (cxx * cyy - cxy * cxy)))
        val angle = if (abs(cxy) > 1e-10) {
            atan2(cxy, trace / 2.0 + disc - cyy).toFloat()
        } else {
            if (cxx >= cyy) 0f else (PI / 2).toFloat()
        }

        val cosA = cos(angle.toDouble())
        val sinA = sin(angle.toDouble())

        var minX = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE
        var minY = Double.MAX_VALUE
        var maxY = -Double.MAX_VALUE

        for ((x, y) in pts) {
            val dx = x - cx
            val dy = y - cy
            val rx = dx * cosA + dy * sinA
            val ry = -dx * sinA + dy * cosA
            if (rx < minX) minX = rx
            if (rx > maxX) maxX = rx
            if (ry < minY) minY = ry
            if (ry > maxY) maxY = ry
        }

        return RotatedRect(
            cx = cx.toFloat(),
            cy = cy.toFloat(),
            w = (maxX - minX).toFloat(),
            h = (maxY - minY).toFloat(),
            angle = angle
        )
    }

    private fun unclipRect(rr: RotatedRect, ratio: Float = 1.5f): FloatArray {
        val cosA = cos(rr.angle.toDouble()).toFloat()
        val sinA = sin(rr.angle.toDouble()).toFloat()
        val hw = rr.w / 2 * sqrt(ratio)
        val hh = rr.h / 2 * sqrt(ratio)

        return floatArrayOf(
            rr.cx + (-hw) * cosA - (-hh) * sinA, rr.cy + (-hw) * sinA + (-hh) * cosA,
            rr.cx + (hw) * cosA - (-hh) * sinA, rr.cy + (hw) * sinA + (-hh) * cosA,
            rr.cx + (hw) * cosA - (hh) * sinA, rr.cy + (hw) * sinA + (hh) * cosA,
            rr.cx + (-hw) * cosA - (hh) * sinA, rr.cy + (-hw) * sinA + (hh) * cosA
        )
    }

    private fun expandPolygon(
        polygon: FloatArray,
        imageWidth: Int,
        imageHeight: Int,
        scale: Float = 1.18f
    ): FloatArray {
        if (polygon.size < 8) return polygon

        var cx = 0f
        var cy = 0f
        val pointCount = polygon.size / 2

        for (i in 0 until pointCount) {
            cx += polygon[i * 2]
            cy += polygon[i * 2 + 1]
        }
        cx /= pointCount
        cy /= pointCount

        val expanded = FloatArray(polygon.size)
        for (i in 0 until pointCount) {
            val x = polygon[i * 2]
            val y = polygon[i * 2 + 1]

            val ex = cx + (x - cx) * scale
            val ey = cy + (y - cy) * scale

            expanded[i * 2] = ex.coerceIn(0f, imageWidth.toFloat())
            expanded[i * 2 + 1] = ey.coerceIn(0f, imageHeight.toFloat())
        }

        return expanded
    }

    private fun perspectiveCrop(bitmap: Bitmap, srcPtsRaw: FloatArray): Bitmap {
        val srcPts = orderQuadPoints(srcPtsRaw)

        fun dist(i: Int, j: Int): Float {
            val dx = srcPts[i * 2] - srcPts[j * 2]
            val dy = srcPts[i * 2 + 1] - srcPts[j * 2 + 1]
            return sqrt(dx * dx + dy * dy)
        }

        val tw = ((dist(0, 1) + dist(3, 2)) / 2f).toInt().coerceIn(1, 2000)
        val th = ((dist(1, 2) + dist(0, 3)) / 2f).toInt().coerceIn(1, 2000)

        val dstPts = floatArrayOf(
            0f, 0f,
            tw.toFloat(), 0f,
            tw.toFloat(), th.toFloat(),
            0f, th.toFloat()
        )

        val matrix = Matrix()
        matrix.setPolyToPoly(srcPts, 0, dstPts, 0, 4)

        return Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888).also { out ->
            val canvas = Canvas(out)
            canvas.drawColor(Color.WHITE)
            canvas.drawBitmap(bitmap, matrix, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        }
    }

    private fun orderQuadPoints(src: FloatArray): FloatArray {
        if (src.size != 8) return src

        val pts = Array(4) { i -> PointF(src[i * 2], src[i * 2 + 1]) }
        val sumSorted = pts.sortedBy { it.x + it.y }
        val diffSorted = pts.sortedBy { it.y - it.x }

        val topLeft = sumSorted.first()
        val bottomRight = sumSorted.last()
        val topRight = diffSorted.first()
        val bottomLeft = diffSorted.last()

        return floatArrayOf(
            topLeft.x, topLeft.y,
            topRight.x, topRight.y,
            bottomRight.x, bottomRight.y,
            bottomLeft.x, bottomLeft.y
        )
    }

    private fun polygonArea(pts: FloatArray): Float {
        var area = 0f
        val n = pts.size / 2
        for (i in 0 until n) {
            val j = (i + 1) % n
            area += pts[i * 2] * pts[j * 2 + 1] - pts[j * 2] * pts[i * 2 + 1]
        }
        return abs(area) / 2f
    }

    private fun expandRectForRecognition(
        rect: Rect,
        imageWidth: Int,
        imageHeight: Int,
        expandXRatio: Float = 0.20f,
        expandYRatio: Float = 0.10f,
        minExpandX: Int = 20,
        minExpandY: Int = 8
    ): Rect {
        val expandX = max(minExpandX, (rect.width() * expandXRatio).toInt())
        val expandY = max(minExpandY, (rect.height() * expandYRatio).toInt())

        return Rect(
            max(0, rect.left - expandX),
            max(0, rect.top - expandY),
            min(imageWidth, rect.right + expandX),
            min(imageHeight, rect.bottom + expandY)
        )
    }

    private fun cropBitmap(bitmap: Bitmap, rect: Rect): Bitmap {
        val x = max(0, rect.left)
        val y = max(0, rect.top)
        val w = min(rect.width(), bitmap.width - x)
        val h = min(rect.height(), bitmap.height - y)

        return if (w > 0 && h > 0) Bitmap.createBitmap(bitmap, x, y, w, h) else bitmap
    }

    private fun rotateBitmap(bitmap: Bitmap, degrees: Float): Bitmap {
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
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
        } catch (_: Exception) {
            null
        }
    }

    fun close() {
        detSession?.close()
        recSession?.close()
        env.close()
        recPool.shutdown()
    }
}