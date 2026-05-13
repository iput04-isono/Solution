package com.crossvision.f.ocr

import android.content.Context
import android.graphics.*
import android.os.SystemClock
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import java.util.ArrayDeque
import java.util.Collections
import kotlin.math.*

class OcrEngine(private val context: Context) {

    companion object {
        private const val DET_SIZE = 640
        private const val MAX_POLYGON_REGIONS = 24
        private const val MAX_REC_WIDTH = 640
        private const val REC_HEIGHT = 48
    }

    data class OcrOutput(
        val originalBitmap: Bitmap,
        val items: List<OcrDetectionItem>,
        val timing: OcrTiming
    )

    data class OcrTiming(
        val totalMs: Long,
        val detectionMs: Long,
        val detectionPreprocessMs: Long,
        val detectionModelAndPostprocessMs: Long,
        val normalRecognitionMs: Long,
        val rotatedRecognitionMs: Long,
        val cropMs: Long,
        val cropCheckMs: Long,
        val orientationPrepMs: Long,
        val resultFilterMs: Long,
        val otherMs: Long,
        val normalRecognitionCount: Int,
        val rotatedRecognitionCount: Int
    )

    data class OcrDetectionItem(
        val index: Int,
        val rect: Rect?,
        val polygon: FloatArray,
        val displayBitmap: Bitmap,
        val recognitionBitmap: Bitmap,
        val result: OcrResult
    )

    private data class RotatedRect(
        val cx: Float,
        val cy: Float,
        val w: Float,
        val h: Float,
        val angle: Float
    )

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private var detSession: OrtSession? = null
    private var recSession: OrtSession? = null
    private val labelList = mutableListOf<String>()

    init {
        val cores = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)

        fun sessionOptions(threads: Int): OrtSession.SessionOptions {
            return OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(threads)
                setInterOpNumThreads(1)
                try {
                    addNnapi()
                } catch (_: Exception) {
                }
            }
        }

        detSession = env.createSession(
            context.assets.open("det.onnx").use { it.readBytes() },
            sessionOptions(cores)
        )

        recSession = env.createSession(
            context.assets.open("ppocr_rec.onnx").use { it.readBytes() },
            sessionOptions((cores / 2).coerceAtLeast(1))
        )

        loadLabels()
    }

    fun runFullOcr(originalBitmap: Bitmap): OcrOutput {
        val totalStartMs = SystemClock.elapsedRealtime()

        var detectionMs = 0L
        var normalRecognitionMs = 0L
        var rotatedRecognitionMs = 0L

        var cropMs = 0L
        var cropCheckMs = 0L
        var orientationPrepMs = 0L
        var resultFilterMs = 0L

        var normalRecognitionCount = 0
        var rotatedRecognitionCount = 0

        val detectionStartMs = SystemClock.elapsedRealtime()

        val detectionPreprocessStartMs = SystemClock.elapsedRealtime()
        val detectionInput = createDetectionInputBitmap(originalBitmap)
        val detectionBitmap = enhanceContrastForDetection(detectionInput)
        val detectionPreprocessMs = SystemClock.elapsedRealtime() - detectionPreprocessStartMs

        val detectionModelStartMs = SystemClock.elapsedRealtime()
        val polygons = detectTextPolygons(
            bitmap = detectionBitmap,
            outputWidth = originalBitmap.width,
            outputHeight = originalBitmap.height
        )
        val detectionModelAndPostprocessMs = SystemClock.elapsedRealtime() - detectionModelStartMs

        detectionMs += SystemClock.elapsedRealtime() - detectionStartMs

        val items = mutableListOf<OcrDetectionItem>()

        for ((index, polygon) in polygons.withIndex()) {
            val cropStartMs = SystemClock.elapsedRealtime()

            val expandedPolygon = expandPolygon(
                polygon = polygon,
                imageWidth = originalBitmap.width,
                imageHeight = originalBitmap.height,
                scale = 1.55f
            )

            val boundingRect = polygonToBoundingRect(expandedPolygon)
            val displayCrop = safePerspectiveCrop(originalBitmap, expandedPolygon)

            cropMs += SystemClock.elapsedRealtime() - cropStartMs

            val cropCheckStartMs = SystemClock.elapsedRealtime()
            val usefulCrop = isUsefulCropForOcr(displayCrop)
            cropCheckMs += SystemClock.elapsedRealtime() - cropCheckStartMs

            if (!usefulCrop) {
                continue
            }

            val orientationStartMs = SystemClock.elapsedRealtime()
            val recognitionBase = normalizeToHorizontal(displayCrop)
            orientationPrepMs += SystemClock.elapsedRealtime() - orientationStartMs

            val recognitionResult = recognizeBestOrientationWithTiming(recognitionBase)

            normalRecognitionMs += recognitionResult.normalRecognitionMs
            rotatedRecognitionMs += recognitionResult.rotatedRecognitionMs
            normalRecognitionCount += recognitionResult.normalRecognitionCount
            rotatedRecognitionCount += recognitionResult.rotatedRecognitionCount

            val recognitionImage = recognitionResult.bitmap
            val result = recognitionResult.result

            val resultFilterStartMs = SystemClock.elapsedRealtime()
            val usefulResult = isUsefulOcrResult(result)
            resultFilterMs += SystemClock.elapsedRealtime() - resultFilterStartMs

            if (usefulResult) {
                items.add(
                    OcrDetectionItem(
                        index = index + 1,
                        rect = boundingRect,
                        polygon = expandedPolygon,
                        displayBitmap = displayCrop,
                        recognitionBitmap = recognitionImage,
                        result = result
                    )
                )
            }
        }

        val totalMs = SystemClock.elapsedRealtime() - totalStartMs
        val otherMs = (
            totalMs -
                detectionMs -
                normalRecognitionMs -
                rotatedRecognitionMs -
                cropMs -
                cropCheckMs -
                orientationPrepMs -
                resultFilterMs
            ).coerceAtLeast(0L)

        return OcrOutput(
            originalBitmap = originalBitmap,
            items = items,
            timing = OcrTiming(
                totalMs = totalMs,
                detectionMs = detectionMs,
                detectionPreprocessMs = detectionPreprocessMs,
                detectionModelAndPostprocessMs = detectionModelAndPostprocessMs,
                normalRecognitionMs = normalRecognitionMs,
                rotatedRecognitionMs = rotatedRecognitionMs,
                cropMs = cropMs,
                cropCheckMs = cropCheckMs,
                orientationPrepMs = orientationPrepMs,
                resultFilterMs = resultFilterMs,
                otherMs = otherMs,
                normalRecognitionCount = normalRecognitionCount,
                rotatedRecognitionCount = rotatedRecognitionCount
            )
        )
    }

    private fun loadLabels() {
        labelList.clear()
        labelList.add("blank")

        context.assets.open("dict.txt")
            .bufferedReader(Charsets.UTF_8)
            .useLines { lines ->
                lines.forEach { line ->
                    if (line.isNotEmpty()) {
                        labelList.add(line)
                    }
                }
            }
    }

    private fun createDetectionInputBitmap(bitmap: Bitmap): Bitmap {
        return if (bitmap.width == DET_SIZE && bitmap.height == DET_SIZE) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, DET_SIZE, DET_SIZE, true)
        }
    }

    private fun enhanceContrastForDetection(bitmap: Bitmap): Bitmap {
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
        if (range < 12f) return bitmap

        val scale = (220f / range).coerceAtMost(2.2f)
        val bias = -minL * scale + 8f

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cm = ColorMatrix(
            floatArrayOf(
                scale, 0f, 0f, 0f, bias,
                0f, scale, 0f, 0f, bias,
                0f, 0f, scale, 0f, bias,
                0f, 0f, 0f, 1f, 0f
            )
        )

        Canvas(out).drawBitmap(
            bitmap,
            0f,
            0f,
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                colorFilter = ColorMatrixColorFilter(cm)
            }
        )

        return out
    }

    private fun detectTextPolygons(
        bitmap: Bitmap,
        outputWidth: Int,
        outputHeight: Int
    ): List<FloatArray> {
        val heatMap = runDetectionModel(bitmap) ?: return emptyList()
        val scaleX = outputWidth.toFloat() / DET_SIZE.toFloat()
        val scaleY = outputHeight.toFloat() / DET_SIZE.toFloat()

        return bfsComponents(heatMap, threshold = 0.26f, minPx = 24)
            .mapNotNull { comp ->
                val rr = pcaMinRect(comp) ?: return@mapNotNull null
                val corners = unclipRect(rr, ratio = 1.5f)
                FloatArray(8) { i ->
                    if (i % 2 == 0) corners[i] * scaleX else corners[i] * scaleY
                }
            }
            .filter { polygonArea(it) > 120f }
            .sortedByDescending { polygonArea(it) }
            .take(MAX_POLYGON_REGIONS)
    }

    private fun runDetectionModel(bitmap: Bitmap): Array<FloatArray>? {
        val session = detSession ?: return null

        val inputBitmap = if (bitmap.width == DET_SIZE && bitmap.height == DET_SIZE) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, DET_SIZE, DET_SIZE, true)
        }

        val pixels = IntArray(DET_SIZE * DET_SIZE)
        inputBitmap.getPixels(pixels, 0, DET_SIZE, 0, 0, DET_SIZE, DET_SIZE)

        val data = FloatBuffer.allocate(1 * 3 * DET_SIZE * DET_SIZE)

        for (c in 0 until 3) {
            val shift = when (c) {
                0 -> 16
                1 -> 8
                else -> 0
            }

            for (p in pixels) {
                val v = ((p shr shift) and 0xFF) / 255f
                data.put((v - 0.485f) / 0.229f)
            }
        }

        data.rewind()

        val inputName = session.inputNames.first()
        val tensor = OnnxTensor.createTensor(
            env,
            data,
            longArrayOf(1, 3, DET_SIZE.toLong(), DET_SIZE.toLong())
        )

        tensor.use { inputTensor ->
            session.run(Collections.singletonMap(inputName, inputTensor)).use { outputs ->
                return extract2DArray(outputs[0].value)
            }
        }
    }

    private fun bfsComponents(
        map: Array<FloatArray>,
        threshold: Float,
        minPx: Int
    ): List<List<Pair<Int, Int>>> {
        if (map.isEmpty() || map[0].isEmpty()) return emptyList()

        val h = map.size
        val w = map[0].size
        val visited = Array(h) { BooleanArray(w) }
        val result = mutableListOf<List<Pair<Int, Int>>>()

        val dx = intArrayOf(1, -1, 0, 0)
        val dy = intArrayOf(0, 0, 1, -1)

        for (y in 0 until h) {
            for (x in 0 until w) {
                if (visited[y][x]) continue
                if (map[y][x] <= threshold) continue

                val queue: ArrayDeque<Int> = ArrayDeque()
                val comp = mutableListOf<Pair<Int, Int>>()

                visited[y][x] = true
                queue.add(y * w + x)

                while (queue.isNotEmpty()) {
                    val code = queue.removeFirst()
                    val cy = code / w
                    val cx = code % w

                    comp.add(cx to cy)

                    for (i in 0..3) {
                        val nx = cx + dx[i]
                        val ny = cy + dy[i]

                        if (nx !in 0 until w || ny !in 0 until h) continue
                        if (visited[ny][nx]) continue
                        if (map[ny][nx] <= threshold) continue

                        visited[ny][nx] = true
                        queue.add(ny * w + nx)
                    }
                }

                if (comp.size >= minPx) {
                    result.add(comp)
                }
            }
        }

        return result
    }

    private fun pcaMinRect(points: List<Pair<Int, Int>>): RotatedRect? {
        if (points.size < 3) return null

        val cx = points.sumOf { it.first }.toDouble() / points.size
        val cy = points.sumOf { it.second }.toDouble() / points.size

        var cxx = 0.0
        var cxy = 0.0
        var cyy = 0.0

        for ((x, y) in points) {
            val dx = x - cx
            val dy = y - cy
            cxx += dx * dx
            cxy += dx * dy
            cyy += dy * dy
        }

        val n = points.size.toDouble()
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

        for ((x, y) in points) {
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
            w = (maxX - minX).toFloat().coerceAtLeast(1f),
            h = (maxY - minY).toFloat().coerceAtLeast(1f),
            angle = angle
        )
    }

    private fun unclipRect(rr: RotatedRect, ratio: Float): FloatArray {
        val cosA = cos(rr.angle.toDouble()).toFloat()
        val sinA = sin(rr.angle.toDouble()).toFloat()

        val hw = rr.w / 2f * sqrt(ratio)
        val hh = rr.h / 2f * sqrt(ratio)

        return floatArrayOf(
            rr.cx + (-hw) * cosA - (-hh) * sinA,
            rr.cy + (-hw) * sinA + (-hh) * cosA,
            rr.cx + (hw) * cosA - (-hh) * sinA,
            rr.cy + (hw) * sinA + (-hh) * cosA,
            rr.cx + (hw) * cosA - (hh) * sinA,
            rr.cy + (hw) * sinA + (hh) * cosA,
            rr.cx + (-hw) * cosA - (hh) * sinA,
            rr.cy + (-hw) * sinA + (hh) * cosA
        )
    }

    private fun expandPolygon(
        polygon: FloatArray,
        imageWidth: Int,
        imageHeight: Int,
        scale: Float
    ): FloatArray {
        if (polygon.size < 8) return polygon

        var cx = 0f
        var cy = 0f
        val count = polygon.size / 2

        for (i in 0 until count) {
            cx += polygon[i * 2]
            cy += polygon[i * 2 + 1]
        }

        cx /= count
        cy /= count

        val out = FloatArray(polygon.size)

        for (i in 0 until count) {
            val x = polygon[i * 2]
            val y = polygon[i * 2 + 1]

            out[i * 2] = (cx + (x - cx) * scale).coerceIn(0f, imageWidth.toFloat())
            out[i * 2 + 1] = (cy + (y - cy) * scale).coerceIn(0f, imageHeight.toFloat())
        }

        return out
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

        return Rect(
            floor(minX).toInt(),
            floor(minY).toInt(),
            ceil(maxX).toInt(),
            ceil(maxY).toInt()
        )
    }

    private fun polygonArea(points: FloatArray): Float {
        val n = points.size / 2
        if (n < 3) return 0f

        var area = 0f

        for (i in 0 until n) {
            val j = (i + 1) % n
            area += points[i * 2] * points[j * 2 + 1] - points[j * 2] * points[i * 2 + 1]
        }

        return abs(area) / 2f
    }

    private fun cropBitmap(bitmap: Bitmap, rect: Rect): Bitmap {
        val left = rect.left.coerceIn(0, bitmap.width - 1)
        val top = rect.top.coerceIn(0, bitmap.height - 1)
        val right = rect.right.coerceIn(left + 1, bitmap.width)
        val bottom = rect.bottom.coerceIn(top + 1, bitmap.height)

        return Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
    }

    private fun normalizeToHorizontal(bitmap: Bitmap): Bitmap {
        return if (bitmap.height > bitmap.width * 1.2f) {
            rotateBitmap(bitmap, 90f)
        } else {
            bitmap
        }
    }

    private fun isUsefulCropForOcr(bitmap: Bitmap): Boolean {
        if (bitmap.width < 8 || bitmap.height < 8) return false

        val maxSide = 128
        val longSide = max(bitmap.width, bitmap.height)

        val checkBitmap = if (longSide > maxSide) {
            val scale = maxSide.toFloat() / longSide.toFloat()
            val newW = (bitmap.width * scale).toInt().coerceAtLeast(1)
            val newH = (bitmap.height * scale).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(bitmap, newW, newH, true)
        } else {
            bitmap
        }

        val w = checkBitmap.width
        val h = checkBitmap.height
        val pixels = IntArray(w * h)
        checkBitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        var coloredCount = 0
        var darkCount = 0
        var brightCount = 0
        var edgeLikeCount = 0

        var minLum = 255f
        var maxLum = 0f

        for (p in pixels) {
            val r = Color.red(p)
            val g = Color.green(p)
            val b = Color.blue(p)

            val lum = r * 0.299f + g * 0.587f + b * 0.114f

            if (lum < minLum) minLum = lum
            if (lum > maxLum) maxLum = lum

            if (lum < 80f) darkCount++
            if (lum > 175f) brightCount++

            val maxRgb = max(r, max(g, b))
            val minRgb = min(r, min(g, b))
            val saturationApprox = if (maxRgb == 0) {
                0f
            } else {
                (maxRgb - minRgb).toFloat() / maxRgb.toFloat()
            }
            val valueApprox = maxRgb / 255f

            if (saturationApprox > 0.18f && valueApprox > 0.16f) {
                coloredCount++
            }

            if (lum < 110f) {
                edgeLikeCount++
            }
        }

        val total = pixels.size.coerceAtLeast(1)
        val coloredRatio = coloredCount.toFloat() / total.toFloat()
        val edgeRatio = edgeLikeCount.toFloat() / total.toFloat()
        val contrast = maxLum - minLum
        val brightRatio = brightCount.toFloat() / total.toFloat()
        val darkRatio = darkCount.toFloat() / total.toFloat()

        if (brightRatio > 0.96f && darkRatio < 0.002f && coloredRatio < 0.002f && contrast < 35f) {
            return false
        }

        if (coloredRatio < 0.002f && edgeRatio < 0.003f && contrast < 45f) {
            return false
        }

        if (coloredCount < 2 && edgeLikeCount < 3 && contrast < 35f) {
            return false
        }

        return true
    }

    private fun isUsefulOcrResult(result: OcrResult): Boolean {
        val text = result.text.trim()
        if (text.isEmpty()) return false

        val upper = text.uppercase()
        if (upper == "EMPTY") return false
        if (upper == "FORMATERR") return false
        if (upper == "ERROR") return false

        val usefulCount = text.count { it.isLetterOrDigit() || it == '-' || it == '/' }
        if (usefulCount == 0) return false

        val usefulRatio = usefulCount.toFloat() / text.length.coerceAtLeast(1).toFloat()
        if (usefulRatio < 0.45f) return false

        if (usefulCount <= 2 && result.confidence < 0.60f) return false

        return true
    }

    private data class RecognitionTimingResult(
        val bitmap: Bitmap,
        val result: OcrResult,
        val normalRecognitionMs: Long,
        val rotatedRecognitionMs: Long,
        val normalRecognitionCount: Int,
        val rotatedRecognitionCount: Int
    )

    private fun recognizeBestOrientationWithTiming(bitmap: Bitmap): RecognitionTimingResult {
        val normalStartMs = SystemClock.elapsedRealtime()
        val normalResult = recognize(bitmap)
        val normalMs = SystemClock.elapsedRealtime() - normalStartMs

        if (isConfidentNormalResult(normalResult)) {
            return RecognitionTimingResult(
                bitmap = bitmap,
                result = normalResult,
                normalRecognitionMs = normalMs,
                rotatedRecognitionMs = 0L,
                normalRecognitionCount = 1,
                rotatedRecognitionCount = 0
            )
        }

        val rotatedBitmap = rotateBitmap(bitmap, 180f)

        val rotatedStartMs = SystemClock.elapsedRealtime()
        val rotatedResult = recognize(rotatedBitmap)
        val rotatedMs = SystemClock.elapsedRealtime() - rotatedStartMs

        return if (recognitionScore(rotatedResult) > recognitionScore(normalResult)) {
            RecognitionTimingResult(
                bitmap = rotatedBitmap,
                result = rotatedResult,
                normalRecognitionMs = normalMs,
                rotatedRecognitionMs = rotatedMs,
                normalRecognitionCount = 1,
                rotatedRecognitionCount = 1
            )
        } else {
            RecognitionTimingResult(
                bitmap = bitmap,
                result = normalResult,
                normalRecognitionMs = normalMs,
                rotatedRecognitionMs = rotatedMs,
                normalRecognitionCount = 1,
                rotatedRecognitionCount = 1
            )
        }
    }

    private fun isConfidentNormalResult(result: OcrResult): Boolean {
        val text = result.text.trim()
        if (text.isEmpty()) return false

        val upper = text.uppercase()
        if (upper == "EMPTY" || upper == "ERROR" || upper == "FORMATERR") return false

        val usefulCount = text.count { it.isLetterOrDigit() || it == '-' || it == '/' }
        if (usefulCount < 4) return false

        val usefulRatio = usefulCount.toFloat() / text.length.coerceAtLeast(1).toFloat()

        return result.confidence >= 0.62f && usefulRatio >= 0.75f
    }

    private fun recognitionScore(result: OcrResult): Float {
        val text = result.text.trim()
        if (text.isEmpty()) return 0f

        val usefulCount = text.count { it.isLetterOrDigit() || it == '-' || it == '/' }
        val usefulRatio = usefulCount.toFloat() / text.length.coerceAtLeast(1).toFloat()

        return result.confidence * 0.80f +
            usefulRatio * 0.15f +
            text.length.coerceAtMost(24) * 0.002f
    }

    private fun recognize(bitmap: Bitmap): OcrResult {
        val session = recSession ?: return OcrResult("", 0f, 0f, 0f)

        val aspect = bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1).toFloat()
        val targetW = ((REC_HEIGHT * aspect).toInt())
            .coerceIn(32, MAX_REC_WIDTH)
            .let { w -> if (w % 32 == 0) w else (w / 32 + 1) * 32 }

        val resized = Bitmap.createScaledBitmap(bitmap, targetW, REC_HEIGHT, true)
        val pixels = IntArray(REC_HEIGHT * targetW)
        resized.getPixels(pixels, 0, targetW, 0, 0, targetW, REC_HEIGHT)

        val data = FloatBuffer.allocate(1 * 3 * REC_HEIGHT * targetW)

        for (c in 0 until 3) {
            val shift = when (c) {
                0 -> 16
                1 -> 8
                else -> 0
            }

            for (p in pixels) {
                val v = ((p shr shift) and 0xFF) / 127.5f - 1.0f
                data.put(v)
            }
        }

        data.rewind()

        val inputName = session.inputNames.first()
        val tensor = OnnxTensor.createTensor(
            env,
            data,
            longArrayOf(1, 3, REC_HEIGHT.toLong(), targetW.toLong())
        )

        tensor.use { inputTensor ->
            session.run(Collections.singletonMap(inputName, inputTensor)).use { outputs ->
                val out = extract2DArray(outputs[0].value) ?: return OcrResult("", 0f, 0f, 0f)
                return decode(out)
            }
        }
    }

    private fun decode(probabilities: Array<FloatArray>): OcrResult {
        val sb = StringBuilder()
        var lastIndex = -1
        var totalConfidence = 0f
        var count = 0
        var maxConfidence = 0f
        var minConfidence = 1f

        for (probs in probabilities) {
            if (probs.isEmpty()) continue

            val maxIndex = probs.indices.maxByOrNull { probs[it] } ?: 0
            val confidence = probs[maxIndex]

            if (maxIndex > 0 && maxIndex != lastIndex && maxIndex < labelList.size) {
                val text = labelList[maxIndex]
                sb.append(text)

                totalConfidence += confidence
                count++

                if (confidence > maxConfidence) maxConfidence = confidence
                if (confidence < minConfidence) minConfidence = confidence
            }

            lastIndex = maxIndex
        }

        val avg = if (count > 0) totalConfidence / count else 0f
        val minC = if (count > 0) minConfidence else 0f

        return OcrResult(
            text = sb.toString(),
            confidence = avg,
            maxConfidence = maxConfidence,
            minConfidence = minC
        )
    }

    private fun safePerspectiveCrop(bitmap: Bitmap, polygon: FloatArray): Bitmap {
        return try {
            perspectiveCrop(bitmap, polygon)
        } catch (_: Exception) {
            cropBitmap(bitmap, polygonToBoundingRect(polygon))
        }
    }

    private fun perspectiveCrop(bitmap: Bitmap, rawPolygon: FloatArray): Bitmap {
        val src = orderQuadPoints(rawPolygon)

        fun dist(i: Int, j: Int): Float {
            val dx = src[i * 2] - src[j * 2]
            val dy = src[i * 2 + 1] - src[j * 2 + 1]
            return sqrt(dx * dx + dy * dy)
        }

        val targetW = ((dist(0, 1) + dist(3, 2)) / 2f).toInt().coerceIn(1, 2000)
        val targetH = ((dist(0, 3) + dist(1, 2)) / 2f).toInt().coerceIn(1, 2000)

        val dst = floatArrayOf(
            0f, 0f,
            targetW.toFloat(), 0f,
            targetW.toFloat(), targetH.toFloat(),
            0f, targetH.toFloat()
        )

        val matrix = Matrix()
        matrix.setPolyToPoly(src, 0, dst, 0, 4)

        val out = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(
            bitmap,
            matrix,
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        )

        return out
    }

    private fun orderQuadPoints(src: FloatArray): FloatArray {
        if (src.size != 8) return src

        val pts = Array(4) { i ->
            PointF(src[i * 2], src[i * 2 + 1])
        }

        val sortedByY = pts.sortedBy { it.y }
        val top = sortedByY.take(2).sortedBy { it.x }
        val bottom = sortedByY.takeLast(2).sortedBy { it.x }

        val topLeft = top[0]
        val topRight = top[1]
        val bottomLeft = bottom[0]
        val bottomRight = bottom[1]

        return floatArrayOf(
            topLeft.x, topLeft.y,
            topRight.x, topRight.y,
            bottomRight.x, bottomRight.y,
            bottomLeft.x, bottomLeft.y
        )
    }

    private fun rotateBitmap(bitmap: Bitmap, degrees: Float): Bitmap {
        val normalized = ((degrees % 360f) + 360f) % 360f
        if (normalized < 0.01f || normalized > 359.99f) return bitmap

        val matrix = Matrix().apply {
            postRotate(normalized)
        }

        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    @Suppress("UNCHECKED_CAST")
    private fun extract2DArray(value: Any): Array<FloatArray>? {
        return try {
            var current: Any = value

            while (current is Array<*>) {
                if (current.isEmpty()) return null

                val first = current[0]

                if (first is FloatArray) {
                    return current as Array<FloatArray>
                }

                current = first ?: return null
            }

            null
        } catch (_: Exception) {
            null
        }
    }

    fun close() {
        try {
            detSession?.close()
        } catch (_: Exception) {
        }

        try {
            recSession?.close()
        } catch (_: Exception) {
        }

        detSession = null
        recSession = null
    }
}
