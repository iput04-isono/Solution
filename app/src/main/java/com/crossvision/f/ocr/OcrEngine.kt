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

/**
 * 鉄骨刻印文字 OCR エンジン（斎藤案 ver1.1）
 *
 * PaddleOCR（PP-OCRv4）の検出モデル DBNet と認識モデル SVTR を ONNX Runtime で実行する。
 * 処理フロー:
 *   1. 入力画像を 640×640 にリサイズ（createDetectionInputBitmap）
 *   2. コントラスト強調（enhanceContrastForDetection）
 *   3. DBNet でテキスト領域の確率マップを生成（runDetectionModel）
 *   4. BFS で連結成分を抽出し、PCA で傾いた矩形を推定（bfsComponents / pcaMinRect）
 *   5. UNCLIP で矩形を少し広げてポリゴン座標に変換（unclipRect）
 *   6. ポリゴンを元画像座標にスケール変換し、面積フィルタで絞り込む（detectTextPolygons）
 *   7. 各ポリゴンに対してパースペクティブクロップ（safePerspectiveCrop）
 *   8. クロップ画像の画質フィルタ（isUsefulCropForOcr）
 *   9. 縦長なら 90° 回転して横向きに正規化（normalizeToHorizontal）
 *  10. 0° 推論 → 信頼度不足なら 180° も試して最良を採用（recognizeBestOrientationWithTiming）
 *  11. 認識結果テキストのフィルタ（isUsefulOcrResult）
 *  12. 結果をまとめて OcrOutput として返す
 *
 * 各ステップの処理時間を OcrTiming に記録する（処理速度の分析・改善に活用）。
 */
class OcrEngine(private val context: Context) {

    companion object {
        /**
         * DBNet への入力画像サイズ（正方形）。
         * ver1.2 で 640 → 512 に変更。ピクセル数が 36% 削減され推論速度が向上する。
         * この値に合わせて det.onnx も 512×512 入力用モデルに差し替え済み。
         */
        private const val DET_SIZE = 512

        /**
         * 1 枚の画像から処理するポリゴン領域の最大数。
         * ver1.2 で 24 → 12 に変更。処理時間の上限を絞り誤検出を抑制する。
         */
        private const val MAX_POLYGON_REGIONS = 12

        /**
         * SVTR 認識モデルへの入力幅の最大値。
         * ver1.2 で 640 → 512 に変更。DET_SIZE の縮小に合わせてメモリ使用量を削減する。
         */
        private const val MAX_REC_WIDTH = 512

        /** SVTR 認識モデルへの入力高さ（固定値）。モデルが 48px を前提に訓練されている */
        private const val REC_HEIGHT = 48
    }

    // -----------------------------------------------------------------------
    // データクラス
    // -----------------------------------------------------------------------

    /**
     * runFullOcr の戻り値。
     * @property originalBitmap 入力画像（未加工）
     * @property items          有効と判定された各テキスト領域の検出・認識結果リスト
     * @property timing         各処理ステップの所要時間（デバッグ・性能分析用）
     */
    data class OcrOutput(
        val originalBitmap: Bitmap,
        val items: List<OcrDetectionItem>,
        val timing: OcrTiming
    )

    /**
     * 各処理ステップの所要時間をまとめたデータクラス。
     * @property totalMs                       全体の処理時間 [ms]
     * @property detectionMs                   検出フェーズ全体 [ms]
     * @property detectionPreprocessMs         検出前処理（リサイズ＋コントラスト） [ms]
     * @property detectionModelAndPostprocessMs DBNet 推論＋後処理（BFS/PCA/UNCLIP） [ms]
     * @property normalRecognitionMs           通常向き（0°）の認識合計時間 [ms]
     * @property rotatedRecognitionMs          180° 回転後の認識合計時間 [ms]
     * @property cropMs                        クロップ処理の合計時間 [ms]
     * @property cropCheckMs                   画質フィルタ処理の合計時間 [ms]
     * @property orientationPrepMs             向き正規化（横向き変換）の合計時間 [ms]
     * @property resultFilterMs                認識後フィルタの合計時間 [ms]
     * @property otherMs                       上記以外の処理時間（totalMs との差分） [ms]
     * @property normalRecognitionCount        0° 認識を実行した領域数
     * @property rotatedRecognitionCount       180° 認識を実行した領域数（早期終了しなかった数）
     */
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

    /**
     * 1 つのテキスト領域の検出・認識結果。
     * @property index            領域の通し番号（1始まり）
     * @property rect             ポリゴンの軸平行バウンディングボックス（UI 表示用）
     * @property polygon          射影クロップに使う拡張後のポリゴン座標 [x0,y0, x1,y1, ...]
     * @property displayBitmap    射影変換で切り出した元画像のクロップ（縦横比そのまま）
     * @property recognitionBitmap 認識に使用した画像（向き補正済み）
     * @property result           SVTR による文字認識結果
     */
    data class OcrDetectionItem(
        val index: Int,
        val rect: Rect?,
        val polygon: FloatArray,
        val displayBitmap: Bitmap,
        val recognitionBitmap: Bitmap,
        val result: OcrResult
    )

    /**
     * DBNet の後処理で使う傾いた矩形（PCA で推定）。
     * @property cx    矩形の中心 x 座標
     * @property cy    矩形の中心 y 座標
     * @property w     矩形の長辺（主軸方向の長さ）
     * @property h     矩形の短辺（副軸方向の長さ）
     * @property angle 主軸の傾き角（ラジアン）
     */
    private data class RotatedRect(
        val cx: Float,
        val cy: Float,
        val w: Float,
        val h: Float,
        val angle: Float
    )

    // -----------------------------------------------------------------------
    // ONNX Runtime セッション・ラベルリスト
    // -----------------------------------------------------------------------

    /** ONNX Runtime の環境（テンソル生成・セッション管理に使う） */
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()

    /** DBNet 検出モデルのセッション（det.onnx） */
    private var detSession: OrtSession? = null

    /** SVTR 認識モデルのセッション（ppocr_rec.onnx） */
    private var recSession: OrtSession? = null

    /** dict.txt から読み込んだ認識文字リスト。インデックス 0 は CTC の blank トークン */
    private val labelList = mutableListOf<String>()

    init {
        // CPU コア数に応じてスレッド数を調整（最小1、最大4）
        val cores = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)

        fun sessionOptions(threads: Int): OrtSession.SessionOptions {
            return OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(threads)  // 単一オペレーター内の並列数
                setInterOpNumThreads(1)        // オペレーター間は直列（オーバーヘッド削減）
                try {
                    addNnapi() // Android の NNAPI アクセラレーターを有効化（非対応端末はフォールバック）
                } catch (_: Exception) {
                }
            }
        }

        // 検出モデル：CPUコア数をフル活用（推論が重いため）
        detSession = env.createSession(
            context.assets.open("det.onnx").use { it.readBytes() },
            sessionOptions(cores)
        )

        // 認識モデル：コア数の半分（複数領域を逐次処理するため少なめに設定）
        recSession = env.createSession(
            context.assets.open("ppocr_rec.onnx").use { it.readBytes() },
            sessionOptions((cores / 2).coerceAtLeast(1))
        )

        loadLabels()
    }

    // -----------------------------------------------------------------------
    // メインエントリポイント
    // -----------------------------------------------------------------------

    /**
     * 入力画像に対してテキスト検出→認識を実行し、結果と各ステップの処理時間を返す。
     *
     * @param originalBitmap カメラまたはギャラリーから取得した入力画像
     * @return [OcrOutput]（有効な認識結果リストと処理時間）
     */
    fun runFullOcr(originalBitmap: Bitmap): OcrOutput {
        val totalStartMs = SystemClock.elapsedRealtime()

        // 各フェーズの累積時間カウンタ
        var detectionMs = 0L
        var normalRecognitionMs = 0L
        var rotatedRecognitionMs = 0L
        var cropMs = 0L
        var cropCheckMs = 0L
        var orientationPrepMs = 0L
        var resultFilterMs = 0L

        // 実行した認識回数カウンタ（性能分析用）
        var normalRecognitionCount = 0
        var rotatedRecognitionCount = 0

        // ── 検出フェーズ ───────────────────────────────────────────────────
        val detectionStartMs = SystemClock.elapsedRealtime()

        val detectionPreprocessStartMs = SystemClock.elapsedRealtime()
        // Step 1: 640×640 にリサイズ
        val detectionInput = createDetectionInputBitmap(originalBitmap)
        // Step 2: コントラスト強調（DBNet が文字境界を検出しやすくする）
        val detectionBitmap = enhanceContrastForDetection(detectionInput)
        val detectionPreprocessMs = SystemClock.elapsedRealtime() - detectionPreprocessStartMs

        val detectionModelStartMs = SystemClock.elapsedRealtime()
        // Step 3〜6: DBNet 推論 → BFS/PCA → ポリゴン群を取得
        val polygons = detectTextPolygons(
            bitmap = detectionBitmap,
            outputWidth = originalBitmap.width,
            outputHeight = originalBitmap.height
        )
        val detectionModelAndPostprocessMs = SystemClock.elapsedRealtime() - detectionModelStartMs

        detectionMs += SystemClock.elapsedRealtime() - detectionStartMs

        // ── 認識フェーズ（ポリゴンごとにループ） ─────────────────────────
        val items = mutableListOf<OcrDetectionItem>()

        for ((index, polygon) in polygons.withIndex()) {
            // Step 7a: ポリゴンを 1.55 倍に拡大（文字の端が切れないようにするため）
            val cropStartMs = SystemClock.elapsedRealtime()
            val expandedPolygon = expandPolygon(
                polygon = polygon,
                imageWidth = originalBitmap.width,
                imageHeight = originalBitmap.height,
                scale = 1.55f
            )

            val boundingRect = polygonToBoundingRect(expandedPolygon)
            // Step 7b: 射影変換（Perspective Crop）で傾き補正済みのクロップ画像を取得
            val displayCrop = safePerspectiveCrop(originalBitmap, expandedPolygon)
            cropMs += SystemClock.elapsedRealtime() - cropStartMs

            // Step 8: 画質フィルタ — 真っ白・情報量ゼロの領域は認識をスキップ
            val cropCheckStartMs = SystemClock.elapsedRealtime()
            val usefulCrop = isUsefulCropForOcr(displayCrop)
            cropCheckMs += SystemClock.elapsedRealtime() - cropCheckStartMs
            if (!usefulCrop) continue

            // Step 9: 縦長クロップを 90° 回転して横向きに正規化
            //         SVTR は横長画像を想定しているため、縦長のまま渡すと精度が低下する
            val orientationStartMs = SystemClock.elapsedRealtime()
            val recognitionBase = normalizeToHorizontal(displayCrop)
            orientationPrepMs += SystemClock.elapsedRealtime() - orientationStartMs

            // Step 10: 0°/180° の向き比較で最良結果を選択
            val recognitionResult = recognizeBestOrientationWithTiming(recognitionBase)
            normalRecognitionMs += recognitionResult.normalRecognitionMs
            rotatedRecognitionMs += recognitionResult.rotatedRecognitionMs
            normalRecognitionCount += recognitionResult.normalRecognitionCount
            rotatedRecognitionCount += recognitionResult.rotatedRecognitionCount

            val recognitionImage = recognitionResult.bitmap
            val result = recognitionResult.result

            // Step 11: 認識後フィルタ — ゴミ文字・空文字は除外
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

        // otherMs = 計測対象外の処理時間（ループオーバーヘッドなど）
        val totalMs = SystemClock.elapsedRealtime() - totalStartMs
        val otherMs = (
            totalMs - detectionMs - normalRecognitionMs - rotatedRecognitionMs -
                cropMs - cropCheckMs - orientationPrepMs - resultFilterMs
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

    // -----------------------------------------------------------------------
    // 初期化ユーティリティ
    // -----------------------------------------------------------------------

    /**
     * assets/dict.txt から認識文字辞書を読み込む。
     * インデックス 0 を CTC の blank トークンとして予約し、以降に各文字を追加する。
     * blank はデコード時に「文字なし」を表す特殊トークンで、CTC デコードでは除去される。
     */
    private fun loadLabels() {
        labelList.clear()
        labelList.add("blank") // index=0 は CTC blank トークン

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

    // -----------------------------------------------------------------------
    // Step 1: 検出用画像リサイズ
    // -----------------------------------------------------------------------

    /**
     * DBNet への入力用に画像を 640×640 にリサイズする。
     * すでに 640×640 なら複製せず同じオブジェクトを返す（メモリ節約）。
     *
     * 注意: このリサイズは縦横比を無視した単純スケールであるため、
     * 元画像のアスペクト比が大きく崩れると検出精度が低下する可能性がある。
     * ポリゴン座標は後段で元画像サイズにスケールバックするため、
     * 最終的な切り出し位置に影響は出ない。
     */
    private fun createDetectionInputBitmap(bitmap: Bitmap): Bitmap {
        return if (bitmap.width == DET_SIZE && bitmap.height == DET_SIZE) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, DET_SIZE, DET_SIZE, true)
        }
    }

    // -----------------------------------------------------------------------
    // Step 2: コントラスト強調
    // -----------------------------------------------------------------------

    /**
     * DBNet の検出精度を高めるために入力画像のコントラストを線形ストレッチする。
     *
     * アルゴリズム:
     *   1. 全ピクセルの輝度 L = 0.299R + 0.587G + 0.114B の最小値・最大値を求める
     *   2. range = maxL - minL が 12 未満なら変化がほぼないと判断してスキップ
     *   3. scale = min(220 / range, 2.2) で引き伸ばし倍率を計算（上限 2.2 倍で白飛び防止）
     *   4. bias = -minL * scale + 8 で黒つぶれを底上げ（+8 は暗部を微量持ち上げる定数）
     *   5. ColorMatrix で R/G/B チャネルに同一の scale/bias を適用
     *
     * @param bitmap 640×640 にリサイズ済みの画像
     * @return コントラスト強調済みの画像（range が小さい場合は入力をそのまま返す）
     */
    private fun enhanceContrastForDetection(bitmap: Bitmap): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        var minL = 255f
        var maxL = 0f

        // 全ピクセルを走査して輝度の最小・最大を取得
        for (p in pixels) {
            val lum = Color.red(p) * 0.299f + Color.green(p) * 0.587f + Color.blue(p) * 0.114f
            if (lum < minL) minL = lum
            if (lum > maxL) maxL = lum
        }

        val range = maxL - minL
        // 輝度の幅が 12 未満なら変化量が小さすぎるためスキップ（錆びた鉄骨でも 12 は超えることが多い）
        if (range < 12f) return bitmap

        // 引き伸ばし倍率。2.2 を超えると白飛びが激しくなるため上限を設定
        val scale = (220f / range).coerceAtMost(2.2f)
        // 黒レベルのオフセット。+8f で暗い画像全体を微量に持ち上げる
        val bias = -minL * scale + 8f

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        // ColorMatrix の構造: [R出力] = scale*R + bias（G/B も同様）
        val cm = ColorMatrix(
            floatArrayOf(
                scale, 0f, 0f, 0f, bias,
                0f, scale, 0f, 0f, bias,
                0f, 0f, scale, 0f, bias,
                0f, 0f, 0f, 1f, 0f   // アルファは変更しない
            )
        )

        Canvas(out).drawBitmap(
            bitmap, 0f, 0f,
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                colorFilter = ColorMatrixColorFilter(cm)
            }
        )

        return out
    }

    // -----------------------------------------------------------------------
    // Step 3〜6: テキスト領域検出（DBNet 推論 → BFS/PCA/UNCLIP）
    // -----------------------------------------------------------------------

    /**
     * DBNet で取得した確率マップから、元画像座標系のポリゴン群を生成する。
     *
     * 処理の流れ:
     *   1. [runDetectionModel] で確率マップ（640×640）を生成
     *   2. [bfsComponents] で閾値 0.26 以上のピクセルを連結成分に分割
     *   3. [pcaMinRect] で各成分の主軸方向を PCA で推定し、傾き付き最小外接矩形を算出
     *   4. [unclipRect] で矩形を sqrt(1.5) 倍に拡張（文字端の切れを防ぐ）
     *   5. 座標を元画像サイズにスケール変換（DET_SIZE → 実画像サイズ）
     *   6. 面積 120px² 未満のゴミ領域を除外
     *   7. 面積が大きい順にソートして最大 24 件を返す
     *
     * @param bitmap       コントラスト強調済みの 640×640 画像
     * @param outputWidth  元画像の幅（スケール変換用）
     * @param outputHeight 元画像の高さ（スケール変換用）
     * @return 元画像座標系のポリゴン配列リスト（各要素: [x0,y0, x1,y1, x2,y2, x3,y3]）
     */
    private fun detectTextPolygons(
        bitmap: Bitmap,
        outputWidth: Int,
        outputHeight: Int
    ): List<FloatArray> {
        val heatMap = runDetectionModel(bitmap) ?: return emptyList()

        // DBNet の出力は 640×640 座標系 → 元画像座標系への変換倍率
        val scaleX = outputWidth.toFloat() / DET_SIZE.toFloat()
        val scaleY = outputHeight.toFloat() / DET_SIZE.toFloat()

        return bfsComponents(heatMap, threshold = 0.26f, minPx = 24)
            .mapNotNull { comp ->
                val rr = pcaMinRect(comp) ?: return@mapNotNull null
                val corners = unclipRect(rr, ratio = 1.5f)
                // スケール変換: 偶数インデックスが x 座標、奇数インデックスが y 座標
                FloatArray(8) { i ->
                    if (i % 2 == 0) corners[i] * scaleX else corners[i] * scaleY
                }
            }
            .filter { polygonArea(it) > 120f }          // 微小領域（ノイズ）を除外
            .sortedByDescending { polygonArea(it) }      // 大きい領域を優先
            .take(MAX_POLYGON_REGIONS)                   // 最大 24 件
    }

    /**
     * 640×640 画像を DBNet に通し、各ピクセルが文字領域である確率マップを返す。
     *
     * 入力テンソルの形状: [1, 3, 640, 640]（NCHW 形式）
     * 正規化: pixel_value / 255.0 → (v - mean) / std
     *   mean = [0.485, 0.456, 0.406]（ImageNet 標準）
     *   std  = [0.229, 0.224, 0.225]（ImageNet 標準）
     *   ※ このコードでは全チャネルに mean=0.485, std=0.229 を使用（簡略化）
     *
     * @return [640][640] の確率マップ（0.0〜1.0）、推論失敗時は null
     */
    private fun runDetectionModel(bitmap: Bitmap): Array<FloatArray>? {
        val session = detSession ?: return null

        val inputBitmap = if (bitmap.width == DET_SIZE && bitmap.height == DET_SIZE) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, DET_SIZE, DET_SIZE, true)
        }

        val pixels = IntArray(DET_SIZE * DET_SIZE)
        inputBitmap.getPixels(pixels, 0, DET_SIZE, 0, 0, DET_SIZE, DET_SIZE)

        // NCHW 形式（チャネル優先）でデータを格納
        // チャネル順: R(shift=16), G(shift=8), B(shift=0)
        val data = FloatBuffer.allocate(1 * 3 * DET_SIZE * DET_SIZE)
        for (c in 0 until 3) {
            val shift = when (c) {
                0 -> 16  // R チャネル
                1 -> 8   // G チャネル
                else -> 0 // B チャネル
            }
            for (p in pixels) {
                val v = ((p shr shift) and 0xFF) / 255f
                // ImageNet 正規化: (v - 0.485) / 0.229
                data.put((v - 0.485f) / 0.229f)
            }
        }
        data.rewind()

        val inputName = session.inputNames.first()
        val tensor = OnnxTensor.createTensor(
            env, data,
            longArrayOf(1, 3, DET_SIZE.toLong(), DET_SIZE.toLong())
        )

        tensor.use { inputTensor ->
            session.run(Collections.singletonMap(inputName, inputTensor)).use { outputs ->
                return extract2DArray(outputs[0].value)
            }
        }
    }

    /**
     * 確率マップに BFS（幅優先探索）を適用し、閾値以上のピクセルの連結成分リストを返す。
     *
     * 各成分は「文字かもしれない塊」を表す。成分のピクセル座標群を PCA に渡すことで
     * 傾いた矩形（RotatedRect）を算出できる。
     *
     * @param map       確率マップ [h][w]
     * @param threshold この値より大きいピクセルを文字として扱う（0.26）
     * @param minPx     成分として認める最小ピクセル数（24）。小さすぎるノイズを除去
     * @return 各連結成分のピクセル座標リスト（Pair<x, y>）
     */
    private fun bfsComponents(
        map: Array<FloatArray>,
        threshold: Float,
        minPx: Int
    ): List<List<Pair<Int, Int>>> {
        if (map.isEmpty() || map[0].isEmpty()) return emptyList()

        val h = map.size
        val w = map[0].size
        val visited = Array(h) { BooleanArray(w) } // 訪問済みフラグ（二重訪問を防ぐ）
        val result = mutableListOf<List<Pair<Int, Int>>>()

        // 4 近傍（上下左右）の方向ベクトル
        val dx = intArrayOf(1, -1, 0, 0)
        val dy = intArrayOf(0, 0, 1, -1)

        for (y in 0 until h) {
            for (x in 0 until w) {
                if (visited[y][x]) continue
                if (map[y][x] <= threshold) continue // 閾値以下はスキップ

                // 未訪問かつ閾値超えのピクセルを起点に BFS を開始
                val queue: ArrayDeque<Int> = ArrayDeque()
                val comp = mutableListOf<Pair<Int, Int>>()

                visited[y][x] = true
                // ピクセル座標を 1 つの Int に圧縮（y*w + x）してキューに追加
                queue.add(y * w + x)

                while (queue.isNotEmpty()) {
                    val code = queue.removeFirst()
                    val cy = code / w
                    val cx = code % w
                    comp.add(cx to cy)

                    // 4 近傍を探索
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

                // 最小ピクセル数未満の小さすぎる成分はノイズとして除外
                if (comp.size >= minPx) result.add(comp)
            }
        }

        return result
    }

    /**
     * 連結成分のピクセル座標群に PCA（主成分分析）を適用し、
     * 最小面積外接矩形（RotatedRect）を算出する。
     *
     * PCA の手順:
     *   1. 重心（cx, cy）を計算
     *   2. 共分散行列 [[cxx, cxy], [cxy, cyy]] を計算
     *   3. 固有値・固有ベクトルを解析的に求め、主軸の傾き angle を得る
     *   4. 主軸に沿ってピクセルを射影し、最小・最大値から w, h を決定
     *
     * @return 傾き付き最小外接矩形。点数が 3 未満なら null
     */
    private fun pcaMinRect(points: List<Pair<Int, Int>>): RotatedRect? {
        if (points.size < 3) return null

        // 重心を計算
        val cx = points.sumOf { it.first }.toDouble() / points.size
        val cy = points.sumOf { it.second }.toDouble() / points.size

        // 共分散行列の要素を計算
        var cxx = 0.0; var cxy = 0.0; var cyy = 0.0
        for ((x, y) in points) {
            val dx = x - cx; val dy = y - cy
            cxx += dx * dx; cxy += dx * dy; cyy += dy * dy
        }
        val n = points.size.toDouble()
        cxx /= n; cxy /= n; cyy /= n

        // 固有値の差から主軸の傾き angle を求める（2×2 行列の解析解）
        val trace = cxx + cyy
        val disc = sqrt(max(0.0, trace * trace / 4.0 - (cxx * cyy - cxy * cxy)))
        val angle = if (abs(cxy) > 1e-10) {
            atan2(cxy, trace / 2.0 + disc - cyy).toFloat()
        } else {
            if (cxx >= cyy) 0f else (PI / 2).toFloat()
        }

        // 主軸方向への射影で矩形の幅・高さを算出
        val cosA = cos(angle.toDouble()); val sinA = sin(angle.toDouble())
        var minX = Double.MAX_VALUE; var maxX = -Double.MAX_VALUE
        var minY = Double.MAX_VALUE; var maxY = -Double.MAX_VALUE
        for ((x, y) in points) {
            val dx = x - cx; val dy = y - cy
            val rx = dx * cosA + dy * sinA   // 主軸方向の成分
            val ry = -dx * sinA + dy * cosA  // 副軸方向の成分
            if (rx < minX) minX = rx; if (rx > maxX) maxX = rx
            if (ry < minY) minY = ry; if (ry > maxY) maxY = ry
        }

        return RotatedRect(
            cx = cx.toFloat(), cy = cy.toFloat(),
            w = (maxX - minX).toFloat().coerceAtLeast(1f),
            h = (maxY - minY).toFloat().coerceAtLeast(1f),
            angle = angle
        )
    }

    /**
     * DBNet の検出矩形を UNCLIP（面積拡大）して4頂点のポリゴンを返す。
     *
     * DBNet はテキスト本体に近い矩形を検出するため、そのままでは文字の端が切れる。
     * 各辺を外側に sqrt(ratio) 倍スケールすることで領域を広げる。
     * ratio = 1.5 の場合、各辺が約 22% 広がる（sqrt(1.5) ≒ 1.22）。
     *
     * 戻り値の頂点順: [左上, 右上, 右下, 左下]（x, y を交互に格納）
     *
     * @param rr    PCA で求めた傾き付き矩形
     * @param ratio 面積拡大比（1.5 なら面積が 1.5 倍になるよう各辺を広げる）
     * @return 8 要素の FloatArray [x0,y0, x1,y1, x2,y2, x3,y3]
     */
    private fun unclipRect(rr: RotatedRect, ratio: Float): FloatArray {
        val cosA = cos(rr.angle.toDouble()).toFloat()
        val sinA = sin(rr.angle.toDouble()).toFloat()

        // sqrt(ratio) 倍した半幅・半高（面積が ratio 倍になるよう両辺を伸ばすため）
        val hw = rr.w / 2f * sqrt(ratio)
        val hh = rr.h / 2f * sqrt(ratio)

        // 傾き angle の回転行列で 4 頂点を計算
        return floatArrayOf(
            rr.cx + (-hw) * cosA - (-hh) * sinA,  // 左上 x
            rr.cy + (-hw) * sinA + (-hh) * cosA,  // 左上 y
            rr.cx + ( hw) * cosA - (-hh) * sinA,  // 右上 x
            rr.cy + ( hw) * sinA + (-hh) * cosA,  // 右上 y
            rr.cx + ( hw) * cosA - ( hh) * sinA,  // 右下 x
            rr.cy + ( hw) * sinA + ( hh) * cosA,  // 右下 y
            rr.cx + (-hw) * cosA - ( hh) * sinA,  // 左下 x
            rr.cy + (-hw) * sinA + ( hh) * cosA   // 左下 y
        )
    }

    // -----------------------------------------------------------------------
    // Step 7a: ポリゴン拡張
    // -----------------------------------------------------------------------

    /**
     * ポリゴン（4頂点）を重心基準で scale 倍に拡大する。
     *
     * unclipRect の UNCLIP 後にさらに 1.55 倍することで、文字の周囲に余白を設けて
     * 認識精度を向上させる（特に筆記体や装飾文字で効果的）。
     * 画像境界でクリップするため、はみ出た頂点は端に収まる。
     *
     * @param polygon     元のポリゴン座標 [x0,y0, x1,y1, ...]
     * @param imageWidth  クリップ上限（元画像の幅）
     * @param imageHeight クリップ上限（元画像の高さ）
     * @param scale       拡大倍率（1.55 ≒ 55% 拡大）
     * @return 拡大後のポリゴン座標
     */
    private fun expandPolygon(
        polygon: FloatArray,
        imageWidth: Int,
        imageHeight: Int,
        scale: Float
    ): FloatArray {
        if (polygon.size < 8) return polygon

        // 重心を計算
        var cx = 0f; var cy = 0f
        val count = polygon.size / 2
        for (i in 0 until count) { cx += polygon[i * 2]; cy += polygon[i * 2 + 1] }
        cx /= count; cy /= count

        val out = FloatArray(polygon.size)
        for (i in 0 until count) {
            val x = polygon[i * 2]; val y = polygon[i * 2 + 1]
            // 重心から各頂点へのベクトルを scale 倍して新しい頂点座標を算出
            out[i * 2]     = (cx + (x - cx) * scale).coerceIn(0f, imageWidth.toFloat())
            out[i * 2 + 1] = (cy + (y - cy) * scale).coerceIn(0f, imageHeight.toFloat())
        }
        return out
    }

    // -----------------------------------------------------------------------
    // ユーティリティ（バウンディングボックス・面積）
    // -----------------------------------------------------------------------

    /**
     * ポリゴンの頂点座標から軸平行バウンディングボックス（AABB）を算出する。
     * UI での表示やデバッグに使用。
     */
    private fun polygonToBoundingRect(polygon: FloatArray): Rect {
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (i in polygon.indices step 2) {
            val x = polygon[i]; val y = polygon[i + 1]
            if (x < minX) minX = x; if (y < minY) minY = y
            if (x > maxX) maxX = x; if (y > maxY) maxY = y
        }
        return Rect(floor(minX).toInt(), floor(minY).toInt(), ceil(maxX).toInt(), ceil(maxY).toInt())
    }

    /**
     * ポリゴンの面積をシューレースの公式（Shoelace formula）で計算する。
     * 面積が 120px² 未満のゴミ領域を除外するフィルタで使用。
     */
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

    /**
     * 矩形領域を安全にクロップする。
     * safePerspectiveCrop が例外を投げた場合のフォールバックとして使用。
     */
    private fun cropBitmap(bitmap: Bitmap, rect: Rect): Bitmap {
        val left = rect.left.coerceIn(0, bitmap.width - 1)
        val top = rect.top.coerceIn(0, bitmap.height - 1)
        val right = rect.right.coerceIn(left + 1, bitmap.width)
        val bottom = rect.bottom.coerceIn(top + 1, bitmap.height)
        return Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
    }

    // -----------------------------------------------------------------------
    // Step 9: 縦長正規化
    // -----------------------------------------------------------------------

    /**
     * SVTR 認識モデルは横長画像を前提としているため、縦長クロップを 90° 回転して横向きにする。
     * height > width * 1.2 を「縦長」と判定している（アスペクト比 < 約 0.83）。
     */
    private fun normalizeToHorizontal(bitmap: Bitmap): Bitmap {
        return if (bitmap.height > bitmap.width * 1.2f) {
            rotateBitmap(bitmap, 90f)
        } else {
            bitmap
        }
    }

    // -----------------------------------------------------------------------
    // Step 8: 認識前画質フィルタ
    // -----------------------------------------------------------------------

    /**
     * クロップ画像が OCR に値するか（文字が含まれそうか）を簡易チェックする。
     *
     * 処理の高速化のため、長辺を最大 128px に縮小してから統計を計算する。
     * 以下の条件をすべて通過しなかった場合は false を返し、認識をスキップする:
     *
     *   除外条件①: brightRatio > 0.96 && darkRatio < 0.002 && coloredRatio < 0.002 && contrast < 35
     *       → ほぼ真っ白で色も輪郭もない領域（背景や白壁）
     *
     *   除外条件②: coloredRatio < 0.002 && edgeRatio < 0.003 && contrast < 45
     *       → 色付き文字も輪郭状ピクセルもなく、コントラストも低い領域（錆の一様な面など）
     *
     *   除外条件③: coloredCount < 2 && edgeLikeCount < 3 && contrast < 35
     *       → 絶対数でも情報量が極端に少ない領域
     *
     * @param bitmap クロップ後の画像
     * @return true = 認識する価値あり、false = スキップして良い
     */
    private fun isUsefulCropForOcr(bitmap: Bitmap): Boolean {
        if (bitmap.width < 8 || bitmap.height < 8) return false

        // 128px 以上なら縮小してから解析（処理速度優先）
        val maxSide = 128
        val longSide = max(bitmap.width, bitmap.height)
        val checkBitmap = if (longSide > maxSide) {
            val scale = maxSide.toFloat() / longSide.toFloat()
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).toInt().coerceAtLeast(1),
                (bitmap.height * scale).toInt().coerceAtLeast(1),
                true
            )
        } else { bitmap }

        val w = checkBitmap.width; val h = checkBitmap.height
        val pixels = IntArray(w * h)
        checkBitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        var coloredCount = 0   // 彩度が高い（カラー）ピクセル数（黄色マーキングなどを検出）
        var darkCount = 0      // 輝度 < 80 のピクセル数
        var brightCount = 0    // 輝度 > 175 のピクセル数
        var edgeLikeCount = 0  // 輝度 < 110 のピクセル数（エッジ・暗い文字の近似）
        var sumLum = 0f        // 全ピクセル輝度の合計（将来の平均輝度チェック用に計測）
        var minLum = 255f; var maxLum = 0f

        for (p in pixels) {
            val r = Color.red(p); val g = Color.green(p); val b = Color.blue(p)
            val lum = r * 0.299f + g * 0.587f + b * 0.114f
            if (lum < minLum) minLum = lum; if (lum > maxLum) maxLum = lum
            sumLum += lum  // 平均輝度算出用に積算（現バージョンでは判定に未使用）
            if (lum < 80f) darkCount++
            if (lum > 175f) brightCount++

            // 近似彩度: (max_rgb - min_rgb) / max_rgb（HSV の S に相当）
            val maxRgb = max(r, max(g, b)); val minRgb = min(r, min(g, b))
            val satApprox = if (maxRgb == 0) 0f else (maxRgb - minRgb).toFloat() / maxRgb.toFloat()
            val valApprox = maxRgb / 255f
            // 彩度 > 0.18 かつ明度 > 0.16: 黄・緑・赤などのマーキング文字を検出
            if (satApprox > 0.18f && valApprox > 0.16f) coloredCount++
            if (lum < 110f) edgeLikeCount++
        }

        val total = pixels.size.coerceAtLeast(1)
        val coloredRatio = coloredCount.toFloat() / total
        val edgeRatio    = edgeLikeCount.toFloat() / total
        val contrast     = maxLum - minLum
        val brightRatio  = brightCount.toFloat() / total
        val darkRatio    = darkCount.toFloat() / total

        // 除外条件① ほぼ真っ白
        if (brightRatio > 0.96f && darkRatio < 0.002f && coloredRatio < 0.002f && contrast < 35f) return false
        // 除外条件② 色も輪郭もない
        if (coloredRatio < 0.002f && edgeRatio < 0.003f && contrast < 45f) return false
        // 除外条件③ 絶対数が少なすぎる
        if (coloredCount < 2 && edgeLikeCount < 3 && contrast < 35f) return false

        return true
    }

    // -----------------------------------------------------------------------
    // Step 11: 認識後テキストフィルタ
    // -----------------------------------------------------------------------

    /**
     * SVTR の認識結果テキストが有効かどうかを判定する。
     *
     * 除外する条件:
     *   - テキストが空
     *   - "EMPTY" / "FORMATERR" / "ERROR"（モデルのエラー出力）
     *   - 有効文字（英数字・`-`・`/`）が 0 個
     *   - 有効文字率 < 45%（記号やゴミ文字が多い）
     *   - 有効文字数 ≤ 2 かつ信頼度 < 60%（短い認識結果の低信頼度）
     *
     * @param result SVTR による認識結果
     * @return true = 有効なテキストと判断、false = 除外
     */
    private fun isUsefulOcrResult(result: OcrResult): Boolean {
        val text = result.text.trim()
        if (text.isEmpty()) return false

        val upper = text.uppercase()
        if (upper == "EMPTY" || upper == "FORMATERR" || upper == "ERROR") return false

        val usefulCount = text.count { it.isLetterOrDigit() || it == '-' || it == '/' }
        if (usefulCount == 0) return false

        // 有効文字率（英数字・ハイフン・スラッシュの割合）が低すぎるものは除外
        val usefulRatio = usefulCount.toFloat() / text.length.coerceAtLeast(1).toFloat()
        if (usefulRatio < 0.45f) return false

        // 2 文字以下かつ信頼度が低い場合はノイズとして除外
        if (usefulCount <= 2 && result.confidence < 0.60f) return false

        return true
    }

    // -----------------------------------------------------------------------
    // Step 10: 向き比較認識（0° vs 180°）
    // -----------------------------------------------------------------------

    /**
     * 認識方向の比較結果と処理時間を保持する内部データクラス。
     * @property bitmap                認識に使用した画像（向き補正済み）
     * @property result                採用した認識結果
     * @property normalRecognitionMs   0° 認識の処理時間 [ms]
     * @property rotatedRecognitionMs  180° 認識の処理時間 [ms]（早期終了時は 0）
     * @property normalRecognitionCount  0° 認識を実行した場合は 1
     * @property rotatedRecognitionCount 180° 認識を実行した場合は 1、早期終了なら 0
     */
    private data class RecognitionTimingResult(
        val bitmap: Bitmap,
        val result: OcrResult,
        val normalRecognitionMs: Long,
        val rotatedRecognitionMs: Long,
        val normalRecognitionCount: Int,
        val rotatedRecognitionCount: Int
    )

    /**
     * 0° で認識し、信頼度が十分なら即返却（早期終了）。
     * 不十分な場合のみ 180° 回転した画像でも認識し、スコアが高い方を採用する。
     *
     * 早期終了の基準（[isConfidentNormalResult]):
     *   - 信頼度 ≥ 0.62
     *   - 有効文字率 ≥ 0.75
     *   - 有効文字数 ≥ 4
     * この条件を満たす場合、180° 推論を省略して処理時間を短縮する。
     *
     * 向き比較スコア（[recognitionScore]):
     *   信頼度 × 0.80 + 有効文字率 × 0.15 + 文字数ボーナス（上限 24 文字）× 0.002
     *
     * @param bitmap 正規化済みクロップ（横向き）
     * @return 最良の向きで認識した結果と処理時間
     */
    private fun recognizeBestOrientationWithTiming(bitmap: Bitmap): RecognitionTimingResult {
        // 0° で認識
        val normalStartMs = SystemClock.elapsedRealtime()
        val normalResult = recognize(bitmap)
        val normalMs = SystemClock.elapsedRealtime() - normalStartMs

        // 早期終了: 0° が十分な品質なら 180° は試さない
        if (isConfidentNormalResult(normalResult)) {
            return RecognitionTimingResult(
                bitmap = bitmap, result = normalResult,
                normalRecognitionMs = normalMs, rotatedRecognitionMs = 0L,
                normalRecognitionCount = 1, rotatedRecognitionCount = 0
            )
        }

        // 180° 回転して再認識
        val rotatedBitmap = rotateBitmap(bitmap, 180f)
        val rotatedStartMs = SystemClock.elapsedRealtime()
        val rotatedResult = recognize(rotatedBitmap)
        val rotatedMs = SystemClock.elapsedRealtime() - rotatedStartMs

        // スコアが高い方を採用
        return if (recognitionScore(rotatedResult) > recognitionScore(normalResult)) {
            RecognitionTimingResult(
                bitmap = rotatedBitmap, result = rotatedResult,
                normalRecognitionMs = normalMs, rotatedRecognitionMs = rotatedMs,
                normalRecognitionCount = 1, rotatedRecognitionCount = 1
            )
        } else {
            RecognitionTimingResult(
                bitmap = bitmap, result = normalResult,
                normalRecognitionMs = normalMs, rotatedRecognitionMs = rotatedMs,
                normalRecognitionCount = 1, rotatedRecognitionCount = 1
            )
        }
    }

    /**
     * 0° の認識結果が「十分信頼できる」かどうかを判定する（早期終了の条件）。
     *
     * 鉄骨の製品コードは通常 4 文字以上あるため、usefulCount >= 4 を条件に加える。
     * confidence >= 0.62 かつ usefulRatio >= 0.75 を同時に満たす場合のみ早期終了する。
     */
    private fun isConfidentNormalResult(result: OcrResult): Boolean {
        val text = result.text.trim()
        if (text.isEmpty()) return false
        val upper = text.uppercase()
        if (upper == "EMPTY" || upper == "ERROR" || upper == "FORMATERR") return false

        val usefulCount = text.count { it.isLetterOrDigit() || it == '-' || it == '/' }
        if (usefulCount < 4) return false // 製品コードは最低 4 文字

        val usefulRatio = usefulCount.toFloat() / text.length.coerceAtLeast(1).toFloat()
        return result.confidence >= 0.62f && usefulRatio >= 0.75f
    }

    /**
     * 向き比較に使うスコアを計算する。
     * 信頼度（80%）・有効文字率（15%）・文字数ボーナス（5%）の加重和。
     *
     * 信頼度だけで判定すると、短い誤認識が高スコアになりやすいため、
     * 有効文字率と文字数のボーナスで補正している。
     */
    private fun recognitionScore(result: OcrResult): Float {
        val text = result.text.trim()
        if (text.isEmpty()) return 0f
        val usefulCount = text.count { it.isLetterOrDigit() || it == '-' || it == '/' }
        val usefulRatio = usefulCount.toFloat() / text.length.coerceAtLeast(1).toFloat()

        return result.confidence * 0.80f +
            usefulRatio * 0.15f +
            text.length.coerceAtMost(24) * 0.002f
    }

    // -----------------------------------------------------------------------
    // SVTR 認識モデル推論
    // -----------------------------------------------------------------------

    /**
     * クロップ画像を SVTR 認識モデルに通し、認識結果（テキスト + 信頼度）を返す。
     *
     * 前処理:
     *   - アスペクト比を維持しつつ高さを 48px に正規化
     *   - 幅は 32 の倍数に切り上げ（モデルの制約）、最大 640px
     *   - 画素値を [-1, 1] に正規化: (pixel / 127.5) - 1.0
     *
     * 入力テンソル形状: [1, 3, 48, targetW]（NCHW 形式）
     * 出力テンソル形状: [T, num_classes]（T = 時間ステップ数、CTC 出力）
     *
     * @param bitmap 向き補正済みクロップ画像（横向き）
     * @return 認識結果（テキストが空なら confidence=0）
     */
    private fun recognize(bitmap: Bitmap): OcrResult {
        val session = recSession ?: return OcrResult("", 0f, 0f, 0f)

        // アスペクト比を維持した幅を計算し、32 の倍数に切り上げる
        val aspect = bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1).toFloat()
        val targetW = ((REC_HEIGHT * aspect).toInt())
            .coerceIn(32, MAX_REC_WIDTH)
            .let { w -> if (w % 32 == 0) w else (w / 32 + 1) * 32 }

        val resized = Bitmap.createScaledBitmap(bitmap, targetW, REC_HEIGHT, true)
        val pixels = IntArray(REC_HEIGHT * targetW)
        resized.getPixels(pixels, 0, targetW, 0, 0, targetW, REC_HEIGHT)

        // NCHW 形式で FloatBuffer に格納（チャネル R→G→B の順）
        val data = FloatBuffer.allocate(1 * 3 * REC_HEIGHT * targetW)
        for (c in 0 until 3) {
            val shift = when (c) { 0 -> 16; 1 -> 8; else -> 0 }
            for (p in pixels) {
                // 正規化: [-1, 1] へ変換（検出モデルの [0,1] 正規化とは異なる）
                val v = ((p shr shift) and 0xFF) / 127.5f - 1.0f
                data.put(v)
            }
        }
        data.rewind()

        val inputName = session.inputNames.first()
        val tensor = OnnxTensor.createTensor(
            env, data,
            longArrayOf(1, 3, REC_HEIGHT.toLong(), targetW.toLong())
        )

        tensor.use { inputTensor ->
            session.run(Collections.singletonMap(inputName, inputTensor)).use { outputs ->
                val out = extract2DArray(outputs[0].value) ?: return OcrResult("", 0f, 0f, 0f)
                return decode(out)
            }
        }
    }

    /**
     * SVTR モデルの出力確率テンソル（CTC 出力）をテキストにデコードする。
     *
     * CTC デコードの手順（greedy 法）:
     *   1. 各タイムステップで最大確率のインデックス（文字）を選ぶ
     *   2. インデックス 0（blank）は無視する
     *   3. 連続する同じインデックスは 1 文字に圧縮する（"AAAB" → "AB"）
     *   4. 選択された文字の信頼度から平均・最大・最小を計算する
     *
     * @param probabilities [T, num_classes] の確率行列（T = タイムステップ数）
     * @return デコードされたテキストと信頼度統計
     */
    private fun decode(probabilities: Array<FloatArray>): OcrResult {
        val sb = StringBuilder()
        var lastIndex = -1
        var totalConfidence = 0f; var count = 0
        var maxConfidence = 0f; var minConfidence = 1f

        for (probs in probabilities) {
            if (probs.isEmpty()) continue
            val maxIndex = probs.indices.maxByOrNull { probs[it] } ?: 0
            val confidence = probs[maxIndex]

            // blank（0）を除外し、前と同じ文字の連続を圧縮する
            if (maxIndex > 0 && maxIndex != lastIndex && maxIndex < labelList.size) {
                sb.append(labelList[maxIndex])
                totalConfidence += confidence; count++
                if (confidence > maxConfidence) maxConfidence = confidence
                if (confidence < minConfidence) minConfidence = confidence
            }
            lastIndex = maxIndex
        }

        val avg  = if (count > 0) totalConfidence / count else 0f
        val minC = if (count > 0) minConfidence else 0f

        return OcrResult(text = sb.toString(), confidence = avg, maxConfidence = maxConfidence, minConfidence = minC)
    }

    // -----------------------------------------------------------------------
    // Step 7b: 射影変換（Perspective Crop）
    // -----------------------------------------------------------------------

    /**
     * 射影変換（Perspective Transform）で傾いたポリゴン領域を正面から見た長方形に変換する。
     * 例外が発生した場合はバウンディングボックスで単純クロップにフォールバックする。
     */
    private fun safePerspectiveCrop(bitmap: Bitmap, polygon: FloatArray): Bitmap {
        return try {
            perspectiveCrop(bitmap, polygon)
        } catch (_: Exception) {
            cropBitmap(bitmap, polygonToBoundingRect(polygon))
        }
    }

    /**
     * 4 頂点のポリゴンを「正面から見た長方形」に射影変換する。
     *
     * Android の [Matrix.setPolyToPoly] を使い、4 点→4 点の射影行列を算出して
     * 傾いた文字領域をまっすぐに変換する。
     *
     * 変換先の幅・高さは元ポリゴンの辺長の平均値を使用（縦横比を可能な限り維持）。
     */
    private fun perspectiveCrop(bitmap: Bitmap, rawPolygon: FloatArray): Bitmap {
        val src = orderQuadPoints(rawPolygon) // 頂点を [左上, 右上, 右下, 左下] 順に整列

        fun dist(i: Int, j: Int): Float {
            val dx = src[i * 2] - src[j * 2]; val dy = src[i * 2 + 1] - src[j * 2 + 1]
            return sqrt(dx * dx + dy * dy)
        }

        // 上辺・下辺の平均を幅、左辺・右辺の平均を高さとして変換先サイズを決定
        val targetW = ((dist(0, 1) + dist(3, 2)) / 2f).toInt().coerceIn(1, 2000)
        val targetH = ((dist(0, 3) + dist(1, 2)) / 2f).toInt().coerceIn(1, 2000)

        val dst = floatArrayOf(0f, 0f, targetW.toFloat(), 0f, targetW.toFloat(), targetH.toFloat(), 0f, targetH.toFloat())
        val matrix = Matrix()
        matrix.setPolyToPoly(src, 0, dst, 0, 4)

        val out = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.WHITE) // 変換で生じる隙間を白で埋める
        canvas.drawBitmap(bitmap, matrix, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        return out
    }

    /**
     * 4 頂点のポリゴンを [左上, 右上, 右下, 左下] 順に整列する。
     *
     * Y 座標でソートして上 2 点・下 2 点に分け、それぞれを X でソートして
     * 左右を決定する。射影変換の変換元として正しい順序が必要。
     */
    private fun orderQuadPoints(src: FloatArray): FloatArray {
        if (src.size != 8) return src
        val pts = Array(4) { i -> PointF(src[i * 2], src[i * 2 + 1]) }
        val sortedByY = pts.sortedBy { it.y }
        val top    = sortedByY.take(2).sortedBy { it.x }
        val bottom = sortedByY.takeLast(2).sortedBy { it.x }
        return floatArrayOf(
            top[0].x, top[0].y,       // 左上
            top[1].x, top[1].y,       // 右上
            bottom[1].x, bottom[1].y, // 右下
            bottom[0].x, bottom[0].y  // 左下
        )
    }

    // -----------------------------------------------------------------------
    // ユーティリティ（回転・テンソル変換）
    // -----------------------------------------------------------------------

    /**
     * ビットマップを指定した角度だけ回転する。
     * 0° または 360° の場合は回転処理をスキップして同オブジェクトを返す（無駄なコピー防止）。
     *
     * @param degrees 時計回りの回転角度（0〜360）
     */
    private fun rotateBitmap(bitmap: Bitmap, degrees: Float): Bitmap {
        val normalized = ((degrees % 360f) + 360f) % 360f
        if (normalized < 0.01f || normalized > 359.99f) return bitmap
        val matrix = Matrix().apply { postRotate(normalized) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    /**
     * ONNX Runtime の出力テンソルから [T][num_classes] の 2D FloatArray を取り出す。
     *
     * ONNX Runtime の出力値は Any 型で返されるため、
     * Array<*> を再帰的に剥がして FloatArray の層に到達するまでアンラップする。
     *
     * @param value ONNX Runtime の outputs[0].value
     * @return [T][num_classes] の 2D 配列、失敗時は null
     */
    @Suppress("UNCHECKED_CAST")
    private fun extract2DArray(value: Any): Array<FloatArray>? {
        return try {
            var current: Any = value
            while (current is Array<*>) {
                if (current.isEmpty()) return null
                val first = current[0]
                if (first is FloatArray) return current as Array<FloatArray>
                current = first ?: return null
            }
            null
        } catch (_: Exception) { null }
    }

    // -----------------------------------------------------------------------
    // リソース解放
    // -----------------------------------------------------------------------

    /**
     * ONNX Runtime セッションを解放する。
     * Activity.onDestroy() などで必ず呼び出すこと。
     */
    fun close() {
        try { detSession?.close() } catch (_: Exception) {}
        try { recSession?.close() } catch (_: Exception) {}
        detSession = null
        recSession = null
    }
}
