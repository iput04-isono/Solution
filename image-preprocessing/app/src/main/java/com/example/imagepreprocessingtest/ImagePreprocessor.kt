/**
 * ImagePreprocessor.kt
 *
 * 鉄骨文字認識アプリ — 画像前処理検証デモ
 *
 * 担当: 坂井壱謙（画像処理班・前処理担当）
 *
 * 概要:
 *   鉄骨表面に刻印された文字を正確に OCR するための前処理パイプライン。
 *   OpenCV for Android を使用し、錆・照明ムラ・斜め撮影といった
 *   現場特有のノイズに対応する。
 *
 * 前処理パイプライン（全改善モード）:
 *   リサイズ → グレースケール → 歪み補正（透視変換）→ 背景正規化
 *     → CLAHE（コントラスト強調）→ バイラテラルフィルタ（エッジ保持ブラー）
 *     → 適応二値化 → 極性正規化 + モルフォロジークリーニング
 *
 * モード一覧:
 *   STANDARD           標準（CLAHE + ガウシアンブラー + 適応二値化）
 *   BACKGROUND_NORM    背景正規化あり（錆・不均一照明向け）
 *   PERSPECTIVE        歪み補正あり（斜め撮影向け）
 *   FULL               歪み補正 + 背景正規化 + バイラテラルフィルタ（全改善）
 *
 * 使用ライブラリ:
 *   OpenCV 4.11.0 for Android (sdk/java + sdk/native/libs)
 */
package com.example.imagepreprocessingtest

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.sqrt
import kotlin.system.measureTimeMillis

/**
 * 画像前処理クラス（OpenCV版）
 * 鉄骨の文字認識向け前処理。4種類のモードを切り替えて比較できる。
 *
 * モード一覧:
 *   STANDARD           標準（CLAHE + ガウシアンブラー + 適応二値化）
 *   BACKGROUND_NORM    背景正規化あり（錆・不均一照明向け）
 *   PERSPECTIVE        歪み補正あり（斜め撮影向け）
 *   FULL               歪み補正 + 背景正規化 + バイラテラルフィルタ（全改善）
 */
class ImagePreprocessor {

    // ----------------------------------------------------------------
    // モード定義
    // ----------------------------------------------------------------

    enum class Mode(val label: String, val description: String) {
        STANDARD(
            "標準",
            "CLAHE → ガウシアンブラー → 適応二値化"
        ),
        BACKGROUND_NORM(
            "背景正規化",
            "背景除去（錆・不均一照明対策）→ CLAHE → 適応二値化"
        ),
        PERSPECTIVE(
            "歪み補正",
            "透視変換（斜め撮影補正）→ CLAHE → 適応二値化"
        ),
        FULL(
            "全改善",
            "歪み補正 → 背景正規化 → CLAHE → バイラテラルフィルタ → 適応二値化"
        )
    }

    companion object {
        const val DEFAULT_TARGET_SIZE       = 960
        const val DEFAULT_CLAHE_CLIP_LIMIT  = 2.0
        const val DEFAULT_CLAHE_TILE_SIZE   = 8
        const val DEFAULT_GAUSSIAN_KERNEL   = 5
        const val DEFAULT_ADAPTIVE_BLOCK    = 11
        const val DEFAULT_ADAPTIVE_C        = 2.0

        // 背景正規化：モルフォロジーカーネルサイズ（画像短辺の分母）
        private const val BG_KERNEL_DIVISOR = 8

        // バイラテラルフィルタパラメータ
        private const val BILATERAL_D           = 9
        private const val BILATERAL_SIGMA_COLOR = 75.0
        private const val BILATERAL_SIGMA_SPACE = 75.0

        // 透視変換：検出する輪郭面積の最小・最大比率
        private const val PERSPECTIVE_MIN_AREA = 0.08
        private const val PERSPECTIVE_MAX_AREA = 0.97
    }

    // 現在のモード（外部から変更可能）
    var mode: Mode = Mode.STANDARD

    // ----------------------------------------------------------------
    // 公開 API
    // ----------------------------------------------------------------

    /** 現在のモードで前処理を実行する */
    fun preprocess(bitmap: Bitmap): Bitmap = preprocessWithMode(bitmap, mode)

    /** 指定モードで前処理し、処理時間も返す */
    fun preprocessWithTiming(bitmap: Bitmap): Pair<Bitmap, Long> {
        var result: Bitmap? = null
        val ms = measureTimeMillis { result = preprocess(bitmap) }
        return Pair(result!!, ms)
    }

    /** 各ステップの処理時間を詳細計測する */
    fun preprocessWithDetailedTiming(bitmap: Bitmap): PreprocessResult {
        val srcMat = Mat()
        Utils.bitmapToMat(bitmap, srcMat)
        var mat = srcMat
        val timings = linkedMapOf<String, Long>()

        // Step 1: リサイズ
        timings["①リサイズ"] = measureTimeMillis {
            mat = resize(mat, DEFAULT_TARGET_SIZE, DEFAULT_TARGET_SIZE)
        }

        // Step 2: グレースケール
        timings["②グレースケール"] = measureTimeMillis {
            mat = toGrayscale(mat)
        }

        // Step 3: 歪み補正（PERSPECTIVE / FULL のみ）
        if (mode == Mode.PERSPECTIVE || mode == Mode.FULL) {
            timings["③歪み補正"] = measureTimeMillis {
                mat = correctPerspective(mat) ?: mat
            }
        }

        // Step 4: 背景正規化（BACKGROUND_NORM / FULL のみ）
        if (mode == Mode.BACKGROUND_NORM || mode == Mode.FULL) {
            timings["④背景正規化"] = measureTimeMillis {
                mat = normalizeBackground(mat)
            }
        }

        // Step 5: CLAHE
        val clipLimit = if (mode == Mode.BACKGROUND_NORM) 3.0 else DEFAULT_CLAHE_CLIP_LIMIT
        timings["⑤CLAHE"] = measureTimeMillis {
            mat = applyCLAHE(mat, clipLimit, DEFAULT_CLAHE_TILE_SIZE)
        }

        // Step 6: ブラー（FULL のみバイラテラル、それ以外ガウシアン）
        if (mode == Mode.FULL) {
            timings["⑥バイラテラル"] = measureTimeMillis {
                mat = bilateralFilter(mat)
            }
        } else {
            timings["⑥ガウシアンブラー"] = measureTimeMillis {
                mat = gaussianBlur(mat, DEFAULT_GAUSSIAN_KERNEL)
            }
        }

        // Step 7: 適応二値化
        timings["⑦適応二値化"] = measureTimeMillis {
            mat = adaptiveThreshold(mat, DEFAULT_ADAPTIVE_BLOCK, DEFAULT_ADAPTIVE_C)
        }

        // Step 8: 極性正規化 + モルフォロジークリーニング
        timings["⑧極性正規化"] = measureTimeMillis {
            mat = normalizePolarityAndClean(mat)
        }

        val displayMat = Mat()
        Imgproc.cvtColor(mat, displayMat, Imgproc.COLOR_GRAY2RGBA)
        val resultBitmap = Bitmap.createBitmap(displayMat.cols(), displayMat.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(displayMat, resultBitmap)

        val total = timings.values.sum()
        srcMat.release(); mat.release(); displayMat.release()
        return PreprocessResult(resultBitmap, total, timings)
    }

    // ----------------------------------------------------------------
    // メインパイプライン
    // ----------------------------------------------------------------

    private fun preprocessWithMode(bitmap: Bitmap, m: Mode): Bitmap {
        val srcMat = Mat()
        Utils.bitmapToMat(bitmap, srcMat)
        var mat = srcMat

        mat = resize(mat, DEFAULT_TARGET_SIZE, DEFAULT_TARGET_SIZE)
        mat = toGrayscale(mat)

        if (m == Mode.PERSPECTIVE || m == Mode.FULL) {
            mat = correctPerspective(mat) ?: mat
        }

        if (m == Mode.BACKGROUND_NORM || m == Mode.FULL) {
            mat = normalizeBackground(mat)
        }

        val clipLimit = if (m == Mode.BACKGROUND_NORM) 3.0 else DEFAULT_CLAHE_CLIP_LIMIT
        mat = applyCLAHE(mat, clipLimit, DEFAULT_CLAHE_TILE_SIZE)

        mat = if (m == Mode.FULL) bilateralFilter(mat)
              else gaussianBlur(mat, DEFAULT_GAUSSIAN_KERNEL)

        mat = adaptiveThreshold(mat, DEFAULT_ADAPTIVE_BLOCK, DEFAULT_ADAPTIVE_C)
        mat = normalizePolarityAndClean(mat)

        val displayMat = Mat()
        Imgproc.cvtColor(mat, displayMat, Imgproc.COLOR_GRAY2RGBA)
        val result = Bitmap.createBitmap(displayMat.cols(), displayMat.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(displayMat, result)

        srcMat.release(); mat.release(); displayMat.release()
        return result
    }

    // ----------------------------------------------------------------
    // ① 歪み補正（透視変換）
    //
    // アルゴリズム:
    //   1. Canny エッジ検出
    //   2. 輪郭を面積降順でソート
    //   3. 最大の四角形輪郭を approxPolyDP で検出
    //   4. 4頂点を TL/TR/BR/BL に並べ替え
    //   5. warpPerspective で正面視に変換
    //
    // 四角形が検出できない場合は null を返す（呼び出し元で元の Mat を使用）
    // ----------------------------------------------------------------

    private fun correctPerspective(mat: Mat): Mat? {
        val blurred = Mat()
        Imgproc.GaussianBlur(mat, blurred, Size(5.0, 5.0), 0.0)

        val edges = Mat()
        Imgproc.Canny(blurred, edges, 50.0, 150.0)

        // エッジを膨張させてギャップを埋める
        val dilated = Mat()
        val k3 = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
        Imgproc.dilate(edges, dilated, k3)

        val contours = mutableListOf<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(dilated, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)

        blurred.release(); edges.release(); dilated.release(); k3.release(); hierarchy.release()

        val imageArea = mat.cols().toDouble() * mat.rows()
        val sorted = contours.sortedByDescending { Imgproc.contourArea(it) }

        for (contour in sorted.take(8)) {
            val area = Imgproc.contourArea(contour)
            if (area < imageArea * PERSPECTIVE_MIN_AREA) break
            if (area > imageArea * PERSPECTIVE_MAX_AREA) continue

            val mat2f = MatOfPoint2f(*contour.toArray())
            val peri   = Imgproc.arcLength(mat2f, true)
            val approx = MatOfPoint2f()
            Imgproc.approxPolyDP(mat2f, approx, 0.02 * peri, true)

            if (approx.rows() != 4) continue

            val pts    = approx.toArray()
            val sorted4 = sortCorners(pts)

            val outW = maxOf(dist(sorted4[0], sorted4[1]), dist(sorted4[3], sorted4[2])).toInt()
            val outH = maxOf(dist(sorted4[0], sorted4[3]), dist(sorted4[1], sorted4[2])).toInt()
            if (outW < 60 || outH < 60) continue

            val src = MatOfPoint2f(*sorted4)
            val dst = MatOfPoint2f(
                Point(0.0, 0.0),
                Point(outW.toDouble(), 0.0),
                Point(outW.toDouble(), outH.toDouble()),
                Point(0.0, outH.toDouble())
            )

            val M      = Imgproc.getPerspectiveTransform(src, dst)
            val warped = Mat()
            Imgproc.warpPerspective(mat, warped, M, Size(outW.toDouble(), outH.toDouble()), Imgproc.INTER_LINEAR)
            M.release()
            return warped
        }
        return null
    }

    /**
     * 4点を [TL, TR, BR, BL] 順に並べ替える。
     * 各点の x+y の値で TL（最小）、BR（最大）、
     * x-y の値で TR（最小）、BL（最大）を判定する。
     */
    private fun sortCorners(pts: Array<Point>): Array<Point> {
        val sums  = pts.map { it.x + it.y }
        val diffs = pts.map { it.x - it.y }
        return arrayOf(
            pts[sums.indexOf(sums.min())],   // TL
            pts[diffs.indexOf(diffs.min())],  // TR
            pts[sums.indexOf(sums.max())],    // BR
            pts[diffs.indexOf(diffs.max())]   // BL
        )
    }

    private fun dist(a: Point, b: Point): Double {
        val dx = b.x - a.x; val dy = b.y - a.y
        return sqrt(dx * dx + dy * dy)
    }

    // ----------------------------------------------------------------
    // ② 背景正規化
    //
    // アルゴリズム:
    //   1. モルフォロジー closing（大きいカーネル）で背景を推定
    //      → 文字（小さな暗領域）は閉じられて消え、背景だけが残る
    //   2. 元画像 ÷ 背景 → 照明ムラを除去したフラットな画像
    //   3. 0–255 に正規化
    //
    // 錆・汚れ・シミなど低周波な背景変動を除去し、
    // 高周波な文字エッジのみを強調する。
    // ----------------------------------------------------------------

    private fun normalizeBackground(mat: Mat): Mat {
        var ks = minOf(mat.cols(), mat.rows()) / BG_KERNEL_DIVISOR
        if (ks < 5)  ks = 5
        if (ks % 2 == 0) ks++

        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(ks.toDouble(), ks.toDouble()))
        val background = Mat()
        Imgproc.morphologyEx(mat, background, Imgproc.MORPH_CLOSE, kernel)

        // float 変換して除算（ゼロ除算防止に eps=1 を加算）
        val matF = Mat(); val bgF = Mat(); val divF = Mat()
        mat.convertTo(matF, CvType.CV_32F)
        background.convertTo(bgF, CvType.CV_32F)
        Core.add(bgF, Scalar(1.0), bgF)
        Core.divide(matF, bgF, divF)

        val result = Mat()
        Core.normalize(divF, result, 0.0, 255.0, Core.NORM_MINMAX, CvType.CV_8U)

        kernel.release(); background.release()
        matF.release(); bgF.release(); divF.release()
        return result
    }

    // ----------------------------------------------------------------
    // ③ バイラテラルフィルタ（エッジ保持ブラー）
    //
    // ガウシアンブラーと異なり、輝度差が大きい境界（＝文字エッジ）を
    // ぼかさずにノイズだけを除去する。
    // ※ ガウシアンより処理時間が長い（目安 ×3–5 倍）
    // ----------------------------------------------------------------

    private fun bilateralFilter(mat: Mat): Mat {
        val result = Mat()
        Imgproc.bilateralFilter(mat, result, BILATERAL_D, BILATERAL_SIGMA_COLOR, BILATERAL_SIGMA_SPACE)
        mat.release()
        return result
    }

    // ----------------------------------------------------------------
    // ④ 極性正規化 + モルフォロジークリーニング
    //
    // 処理内容:
    //   1. 平均輝度 < 127 なら bitwise_not（白黒反転）
    //      → 暗背景（錆・濃い鉄骨面）でも「背景=白・文字=黒」に統一
    //   2. MORPH_OPEN (erode → dilate) で孤立した小ドット（錆ノイズ）を除去
    //   3. MORPH_CLOSE (dilate → erode) で文字内の小さな穴を埋めてつぶれを補正
    // ----------------------------------------------------------------

    private fun normalizePolarityAndClean(mat: Mat): Mat {
        var result = mat

        // ① 極性チェック：白ピクセルが多い = 背景が白 = 正常
        //   白ピクセル数が50%未満なら反転（文字が白・背景が黒の状態）
        val whitePixels = Core.countNonZero(result)
        val totalPixels = result.rows() * result.cols()
        if (whitePixels < totalPixels * 0.5) {
            val inverted = Mat()
            Core.bitwise_not(result, inverted)
            result.release()
            result = inverted
        }

        // ② MORPH_OPEN：孤立ノイズ除去（錆ノイズは3〜5px程度）
        val kernelOpen = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
        val opened = Mat()
        Imgproc.morphologyEx(result, opened, Imgproc.MORPH_OPEN, kernelOpen)
        result.release()
        result = opened
        kernelOpen.release()

        // ③ MORPH_CLOSE：文字の穴埋め（輪郭を繋げてソリッドにする）
        val kernelClose = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
        val closed = Mat()
        Imgproc.morphologyEx(result, closed, Imgproc.MORPH_CLOSE, kernelClose)
        result.release()
        result = closed
        kernelClose.release()

        return result
    }

    // ----------------------------------------------------------------
    // 共通ステップ（標準でも使用）
    // ----------------------------------------------------------------

    private fun resize(mat: Mat, maxW: Int, maxH: Int): Mat {
        val scale = minOf(maxW.toDouble() / mat.cols(), maxH.toDouble() / mat.rows())
        if (scale >= 1.0) return mat
        val resized = Mat()
        Imgproc.resize(mat, resized, Size(mat.cols() * scale, mat.rows() * scale))
        mat.release()
        return resized
    }

    private fun toGrayscale(mat: Mat): Mat {
        val gray = Mat()
        when (mat.channels()) {
            1    -> return mat
            3    -> Imgproc.cvtColor(mat, gray, Imgproc.COLOR_BGR2GRAY)
            4    -> Imgproc.cvtColor(mat, gray, Imgproc.COLOR_BGRA2GRAY)
            else -> return mat
        }
        mat.release()
        return gray
    }

    private fun applyCLAHE(mat: Mat, clipLimit: Double, tileSize: Int): Mat {
        val clahe  = Imgproc.createCLAHE(clipLimit, Size(tileSize.toDouble(), tileSize.toDouble()))
        val result = Mat()
        clahe.apply(mat, result)
        mat.release()
        return result
    }

    private fun gaussianBlur(mat: Mat, kernelSize: Int): Mat {
        val result = Mat()
        Imgproc.GaussianBlur(mat, result, Size(kernelSize.toDouble(), kernelSize.toDouble()), 0.0)
        mat.release()
        return result
    }

    private fun adaptiveThreshold(mat: Mat, blockSize: Int, c: Double): Mat {
        val binary = Mat()
        Imgproc.adaptiveThreshold(
            mat, binary, 255.0,
            Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
            Imgproc.THRESH_BINARY,
            blockSize, c
        )
        mat.release()
        return binary
    }

    // ----------------------------------------------------------------
    // データクラス
    // ----------------------------------------------------------------

    data class PreprocessResult(
        val bitmap: Bitmap,
        val totalTimeMs: Long,
        val stepTimings: Map<String, Long>
    )
}
