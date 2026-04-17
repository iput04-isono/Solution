/**
 * ImagePreprocessor.kt
 *
 * 鉄骨文字認識アプリ — 画像前処理検証デモ
 * 担当: 坂井壱謙（画像処理班・前処理担当）
 *
 * 通常モード（4種）と比較検証用条件（20種）の2系統を持つ。
 * 比較モードでは全条件を一括実行し、OCR結果と処理時間を返す。
 */
package com.example.imagepreprocessingtest

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.sqrt
import kotlin.system.measureTimeMillis

class ImagePreprocessor {

    // ================================================================
    // ① 通常モード定義（既存機能）
    // ================================================================

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
        const val DEFAULT_TARGET_SIZE      = 960
        const val DEFAULT_CLAHE_CLIP_LIMIT = 2.0
        const val DEFAULT_CLAHE_TILE_SIZE  = 8
        const val DEFAULT_GAUSSIAN_KERNEL  = 5
        const val DEFAULT_ADAPTIVE_BLOCK   = 11
        const val DEFAULT_ADAPTIVE_C       = 2.0

        private const val BG_KERNEL_DIVISOR       = 8
        private const val BILATERAL_D             = 9
        private const val BILATERAL_SIGMA_COLOR   = 75.0
        private const val BILATERAL_SIGMA_SPACE   = 75.0
        private const val PERSPECTIVE_MIN_AREA    = 0.08
        private const val PERSPECTIVE_MAX_AREA    = 0.97
    }

    var mode: Mode = Mode.STANDARD

    // ================================================================
    // ② 比較検証用 条件定義（20種）
    // ================================================================

    /**
     * 1条件分の定義。
     * binarized: true = 最終ステップで二値化あり、false = グレースケール出力
     */
    data class PreprocessCondition(
        val id: String,
        val name: String,
        val category: String,
        val binarized: Boolean = true
    )

    /** 比較検証で使う全28条件のリスト */
    val compareConditions: List<PreprocessCondition> = listOf(

        // ── Group A: 元画像・グレースケール（二値化なし） ─────────────
        PreprocessCondition("RAW",         "何もしない（元画像）",             "A: 基準",           binarized = false),
        PreprocessCondition("GRAY_ONLY",   "グレースケールのみ",               "A: 基準",           binarized = false),

        // ── Group B: 二値化手法の比較（白黒あり） ───────────────────
        PreprocessCondition("ADAPTIVE",    "適応二値化（標準）",               "B: 二値化手法",     binarized = true),
        PreprocessCondition("OTSU",        "Otsu二値化",                      "B: 二値化手法",     binarized = true),
        PreprocessCondition("GLOBAL_128",  "グローバル閾値 128",               "B: 二値化手法",     binarized = true),
        PreprocessCondition("NO_BLUR_BIN", "ブラーなし + 適応二値化",          "B: 二値化手法",     binarized = true),

        // ── Group C: ブラー ×（白黒あり / なし）ペア比較 ─────────────
        PreprocessCondition("GAUSSIAN",      "ガウシアン ＋ 二値化",            "C: ブラー×二値化",  binarized = true),
        PreprocessCondition("GAUSSIAN_GRAY", "ガウシアン（二値化なし）",        "C: ブラー×二値化",  binarized = false),
        PreprocessCondition("BILATERAL",      "バイラテラル ＋ 二値化",         "C: ブラー×二値化",  binarized = true),
        PreprocessCondition("BILATERAL_GRAY", "バイラテラル（二値化なし）",     "C: ブラー×二値化",  binarized = false),
        PreprocessCondition("MEDIAN",         "メジアン ＋ 二値化",             "C: ブラー×二値化",  binarized = true),
        PreprocessCondition("MEDIAN_GRAY",    "メジアン（二値化なし）",         "C: ブラー×二値化",  binarized = false),
        PreprocessCondition("UNSHARP",        "アンシャープ ＋ 二値化",         "C: ブラー×二値化",  binarized = true),
        PreprocessCondition("UNSHARP_GRAY",   "アンシャープ（二値化なし）",     "C: ブラー×二値化",  binarized = false),

        // ── Group D: コントラスト ×（白黒あり / なし）ペア比較 ────────
        PreprocessCondition("CLAHE",           "CLAHE ＋ 二値化",               "D: コントラスト×二値化", binarized = true),
        PreprocessCondition("CLAHE_GRAY",      "CLAHE（二値化なし）",           "D: コントラスト×二値化", binarized = false),
        PreprocessCondition("GAMMA_BRIGHT",    "γ=0.5 明るく ＋ 二値化",        "D: コントラスト×二値化", binarized = true),
        PreprocessCondition("GAMMA_BRIGHT_GRAY","γ=0.5 明るく（二値化なし）",   "D: コントラスト×二値化", binarized = false),
        PreprocessCondition("GAMMA_DARK",      "γ=2.0 暗く ＋ 二値化",          "D: コントラスト×二値化", binarized = true),
        PreprocessCondition("GAMMA_DARK_GRAY", "γ=2.0 暗く（二値化なし）",      "D: コントラスト×二値化", binarized = false),

        // ── Group E: 背景・影 ×（白黒あり / なし）ペア比較 ──────────
        PreprocessCondition("BG_NORM",      "背景正規化 ＋ 二値化",             "E: 背景×二値化",    binarized = true),
        PreprocessCondition("BG_NORM_GRAY", "背景正規化（二値化なし）",         "E: 背景×二値化",    binarized = false),
        PreprocessCondition("SHADOW",       "影除去 ＋ 二値化",                 "E: 背景×二値化",    binarized = true),
        PreprocessCondition("SHADOW_GRAY",  "影除去（二値化なし）",             "E: 背景×二値化",    binarized = false),

        // ── Group F: カラーチャンネル ×（白黒あり / なし）ペア比較 ──
        PreprocessCondition("CHANNEL_R",      "Rチャンネル ＋ 二値化",          "F: カラー×二値化",  binarized = true),
        PreprocessCondition("CHANNEL_R_GRAY", "Rチャンネル（二値化なし）",      "F: カラー×二値化",  binarized = false),
        PreprocessCondition("HSV_V",          "HSV-V ＋ 二値化",                "F: カラー×二値化",  binarized = true),
        PreprocessCondition("HSV_V_GRAY",     "HSV-V（二値化なし）",            "F: カラー×二値化",  binarized = false),
        PreprocessCondition("LAB_L",          "LAB-L ＋ 二値化",                "F: カラー×二値化",  binarized = true),
        PreprocessCondition("LAB_L_GRAY",     "LAB-L（二値化なし）",            "F: カラー×二値化",  binarized = false),

        // ── Group G: 全改善 ──────────────────────────────────────────
        PreprocessCondition("FULL",           "全改善（二値化あり）",            "G: 全改善",         binarized = true),
        PreprocessCondition("FULL_GRAY",      "全改善（二値化なし）",            "G: 全改善",         binarized = false)
    )

    /**
     * 指定した条件ID で前処理を実行し、結果Bitmap と処理時間(ms) を返す。
     * @param bitmap 元画像
     * @param conditionId PreprocessCondition.id
     */
    fun applyCondition(bitmap: Bitmap, conditionId: String): Pair<Bitmap, Long> {
        var result: Bitmap? = null
        val ms = measureTimeMillis {
            result = applyConditionInternal(bitmap, conditionId)
        }
        return Pair(result!!, ms)
    }

    // ================================================================
    // ③ 公開 API（通常モード）
    // ================================================================

    fun preprocess(bitmap: Bitmap): Bitmap = preprocessWithMode(bitmap, mode)

    fun preprocessWithTiming(bitmap: Bitmap): Pair<Bitmap, Long> {
        var result: Bitmap? = null
        val ms = measureTimeMillis { result = preprocess(bitmap) }
        return Pair(result!!, ms)
    }

    fun preprocessWithDetailedTiming(bitmap: Bitmap): PreprocessResult {
        val srcMat = Mat()
        Utils.bitmapToMat(bitmap, srcMat)
        var mat = srcMat
        val timings = linkedMapOf<String, Long>()

        timings["①リサイズ"] = measureTimeMillis {
            mat = resize(mat, DEFAULT_TARGET_SIZE, DEFAULT_TARGET_SIZE)
        }
        timings["②グレースケール"] = measureTimeMillis {
            mat = toGrayscale(mat)
        }
        if (mode == Mode.PERSPECTIVE || mode == Mode.FULL) {
            timings["③歪み補正"] = measureTimeMillis {
                mat = correctPerspective(mat) ?: mat
            }
        }
        if (mode == Mode.BACKGROUND_NORM || mode == Mode.FULL) {
            timings["④背景正規化"] = measureTimeMillis {
                mat = normalizeBackground(mat)
            }
        }
        val clipLimit = if (mode == Mode.BACKGROUND_NORM) 3.0 else DEFAULT_CLAHE_CLIP_LIMIT
        timings["⑤CLAHE"] = measureTimeMillis {
            mat = applyCLAHE(mat, clipLimit, DEFAULT_CLAHE_TILE_SIZE)
        }
        if (mode == Mode.FULL) {
            timings["⑥バイラテラル"] = measureTimeMillis { mat = bilateralFilter(mat) }
        } else {
            timings["⑥ガウシアンブラー"] = measureTimeMillis {
                mat = gaussianBlur(mat, DEFAULT_GAUSSIAN_KERNEL)
            }
        }
        timings["⑦適応二値化"] = measureTimeMillis {
            mat = adaptiveThreshold(mat, DEFAULT_ADAPTIVE_BLOCK, DEFAULT_ADAPTIVE_C)
        }
        timings["⑧極性正規化"] = measureTimeMillis {
            mat = normalizePolarityAndClean(mat)
        }

        val displayMat = Mat()
        Imgproc.cvtColor(mat, displayMat, Imgproc.COLOR_GRAY2RGBA)
        val resultBitmap = Bitmap.createBitmap(
            displayMat.cols(), displayMat.rows(), Bitmap.Config.ARGB_8888
        )
        Utils.matToBitmap(displayMat, resultBitmap)

        val total = timings.values.sum()
        srcMat.release(); mat.release(); displayMat.release()
        return PreprocessResult(resultBitmap, total, timings)
    }

    // ================================================================
    // ④ 比較条件ごとの前処理ロジック
    // ================================================================

    private fun applyConditionInternal(bitmap: Bitmap, conditionId: String): Bitmap {
        val srcMat = Mat()
        Utils.bitmapToMat(bitmap, srcMat)

        val mat = when (conditionId) {

            // ── Group A: 二値化なし ─────────────────────────────────
            "RAW" -> {
                // リサイズのみ
                resize(srcMat, DEFAULT_TARGET_SIZE, DEFAULT_TARGET_SIZE)
            }
            "GRAY_ONLY" -> {
                var m = resize(srcMat, DEFAULT_TARGET_SIZE, DEFAULT_TARGET_SIZE)
                m = toGrayscale(m)
                m
            }

            // ── Group B: 二値化手法の比較 ───────────────────────────
            "ADAPTIVE" -> {
                var m = resize(srcMat, DEFAULT_TARGET_SIZE, DEFAULT_TARGET_SIZE)
                m = toGrayscale(m)
                m = applyCLAHE(m, DEFAULT_CLAHE_CLIP_LIMIT, DEFAULT_CLAHE_TILE_SIZE)
                m = gaussianBlur(m, DEFAULT_GAUSSIAN_KERNEL)
                m = adaptiveThreshold(m, DEFAULT_ADAPTIVE_BLOCK, DEFAULT_ADAPTIVE_C)
                normalizePolarityAndClean(m)
            }
            "OTSU" -> {
                var m = resize(srcMat, DEFAULT_TARGET_SIZE, DEFAULT_TARGET_SIZE)
                m = toGrayscale(m)
                m = applyCLAHE(m, DEFAULT_CLAHE_CLIP_LIMIT, DEFAULT_CLAHE_TILE_SIZE)
                m = gaussianBlur(m, DEFAULT_GAUSSIAN_KERNEL)
                m = otsuThreshold(m)
                normalizePolarityAndClean(m)
            }
            "GLOBAL_128" -> {
                var m = resize(srcMat, DEFAULT_TARGET_SIZE, DEFAULT_TARGET_SIZE)
                m = toGrayscale(m)
                m = applyCLAHE(m, DEFAULT_CLAHE_CLIP_LIMIT, DEFAULT_CLAHE_TILE_SIZE)
                m = gaussianBlur(m, DEFAULT_GAUSSIAN_KERNEL)
                m = globalThreshold(m, 128.0)
                normalizePolarityAndClean(m)
            }
            "NO_BLUR_BIN" -> {
                var m = resize(srcMat, DEFAULT_TARGET_SIZE, DEFAULT_TARGET_SIZE)
                m = toGrayscale(m)
                m = applyCLAHE(m, DEFAULT_CLAHE_CLIP_LIMIT, DEFAULT_CLAHE_TILE_SIZE)
                m = adaptiveThreshold(m, DEFAULT_ADAPTIVE_BLOCK, DEFAULT_ADAPTIVE_C)
                normalizePolarityAndClean(m)
            }

            // ── Group C: ブラー手法の比較 ───────────────────────────
            "GAUSSIAN" -> {
                var m = resize(srcMat, DEFAULT_TARGET_SIZE, DEFAULT_TARGET_SIZE)
                m = toGrayscale(m)
                m = applyCLAHE(m, DEFAULT_CLAHE_CLIP_LIMIT, DEFAULT_CLAHE_TILE_SIZE)
                m = gaussianBlur(m, DEFAULT_GAUSSIAN_KERNEL)
                m = adaptiveThreshold(m, DEFAULT_ADAPTIVE_BLOCK, DEFAULT_ADAPTIVE_C)
                normalizePolarityAndClean(m)
            }
            "BILATERAL" -> {
                var m = resize(srcMat, DEFAULT_TARGET_SIZE, DEFAULT_TARGET_SIZE)
                m = toGrayscale(m)
                m = applyCLAHE(m, DEFAULT_CLAHE_CLIP_LIMIT, DEFAULT_CLAHE_TILE_SIZE)
                m = bilateralFilter(m)
                m = adaptiveThreshold(m, DEFAULT_ADAPTIVE_BLOCK, DEFAULT_ADAPTIVE_C)
                normalizePolarityAndClean(m)
            }
            "MEDIAN" -> {
                var m = resize(srcMat, DEFAULT_TARGET_SIZE, DEFAULT_TARGET_SIZE)
                m = toGrayscale(m)
                m = applyCLAHE(m, DEFAULT_CLAHE_CLIP_LIMIT, DEFAULT_CLAHE_TILE_SIZE)
                m = medianBlur(m, 5)
                m = adaptiveThreshold(m, DEFAULT_ADAPTIVE_BLOCK, DEFAULT_ADAPTIVE_C)
                normalizePolarityAndClean(m)
            }
            "UNSHARP" -> {
                var m = resize(srcMat, DEFAULT_TARGET_SIZE, DEFAULT_TARGET_SIZE)
                m = toGrayscale(m)
                m = applyCLAHE(m, DEFAULT_CLAHE_CLIP_LIMIT, DEFAULT_CLAHE_TILE_SIZE)
                m = unsharpMask(m)
                m = adaptiveThreshold(m, DEFAULT_ADAPTIVE_BLOCK, DEFAULT_ADAPTIVE_C)
                normalizePolarityAndClean(m)
            }

            // ── Group D: コントラスト強調の比較 ─────────────────────
            "CLAHE" -> {
                var m = resize(srcMat, DEFAULT_TARGET_SIZE, DEFAULT_TARGET_SIZE)
                m = toGrayscale(m)
                m = applyCLAHE(m, DEFAULT_CLAHE_CLIP_LIMIT, DEFAULT_CLAHE_TILE_SIZE)
                m = gaussianBlur(m, DEFAULT_GAUSSIAN_KERNEL)
                m = adaptiveThreshold(m, DEFAULT_ADAPTIVE_BLOCK, DEFAULT_ADAPTIVE_C)
                normalizePolarityAndClean(m)
            }
            "HIST_EQ" -> {
                var m = resize(srcMat, DEFAULT_TARGET_SIZE, DEFAULT_TARGET_SIZE)
                m = toGrayscale(m)
                m = histogramEqualization(m)
                m = gaussianBlur(m, DEFAULT_GAUSSIAN_KERNEL)
                m = adaptiveThreshold(m, DEFAULT_ADAPTIVE_BLOCK, DEFAULT_ADAPTIVE_C)
                normalizePolarityAndClean(m)
            }
            "GAMMA_BRIGHT" -> {
                var m = resize(srcMat, DEFAULT_TARGET_SIZE, DEFAULT_TARGET_SIZE)
                m = toGrayscale(m)
                m = gammaCorrection(m, 0.5)   // γ < 1 → 暗部を明るく（日陰・反射対策）
                m = gaussianBlur(m, DEFAULT_GAUSSIAN_KERNEL)
                m = adaptiveThreshold(m, DEFAULT_ADAPTIVE_BLOCK, DEFAULT_ADAPTIVE_C)
                normalizePolarityAndClean(m)
            }
            "GAMMA_DARK" -> {
                var m = resize(srcMat, DEFAULT_TARGET_SIZE, DEFAULT_TARGET_SIZE)
                m = toGrayscale(m)
                m = gammaCorrection(m, 2.0)   // γ > 1 → 明部を暗く（白飛び対策）
                m = gaussianBlur(m, DEFAULT_GAUSSIAN_KERNEL)
                m = adaptiveThreshold(m, DEFAULT_ADAPTIVE_BLOCK, DEFAULT_ADAPTIVE_C)
                normalizePolarityAndClean(m)
            }

            // ── Group E: 背景・影への対応 ────────────────────────────
            "BG_NORM" -> {
                var m = resize(srcMat, DEFAULT_TARGET_SIZE, DEFAULT_TARGET_SIZE)
                m = toGrayscale(m)
                m = normalizeBackground(m)
                m = applyCLAHE(m, 3.0, DEFAULT_CLAHE_TILE_SIZE)
                m = gaussianBlur(m, DEFAULT_GAUSSIAN_KERNEL)
                m = adaptiveThreshold(m, DEFAULT_ADAPTIVE_BLOCK, DEFAULT_ADAPTIVE_C)
                normalizePolarityAndClean(m)
            }
            "SHADOW" -> {
                var m = resize(srcMat, DEFAULT_TARGET_SIZE, DEFAULT_TARGET_SIZE)
                m = toGrayscale(m)
                m = shadowRemoval(m)           // BG_NORM より積極的な影除去
                m = applyCLAHE(m, 3.0, DEFAULT_CLAHE_TILE_SIZE)
                m = gaussianBlur(m, DEFAULT_GAUSSIAN_KERNEL)
                m = adaptiveThreshold(m, DEFAULT_ADAPTIVE_BLOCK, DEFAULT_ADAPTIVE_C)
                normalizePolarityAndClean(m)
            }

            // ── Group F: カラーチャンネル抽出 ────────────────────────
            "CHANNEL_R" -> {
                // カラー画像からRチャンネルのみ取り出す
                // → 黒/白マーカーや黄色塗料の場合にコントラストが出やすい
                var m = resize(srcMat, DEFAULT_TARGET_SIZE, DEFAULT_TARGET_SIZE)
                m = extractRChannel(m)
                m = applyCLAHE(m, DEFAULT_CLAHE_CLIP_LIMIT, DEFAULT_CLAHE_TILE_SIZE)
                m = gaussianBlur(m, DEFAULT_GAUSSIAN_KERNEL)
                m = adaptiveThreshold(m, DEFAULT_ADAPTIVE_BLOCK, DEFAULT_ADAPTIVE_C)
                normalizePolarityAndClean(m)
            }
            "HSV_V" -> {
                // HSV の V（明度）チャンネル
                // → 色に関係なく、明るさのみで文字を抽出
                var m = resize(srcMat, DEFAULT_TARGET_SIZE, DEFAULT_TARGET_SIZE)
                m = extractHsvV(m)
                m = applyCLAHE(m, DEFAULT_CLAHE_CLIP_LIMIT, DEFAULT_CLAHE_TILE_SIZE)
                m = gaussianBlur(m, DEFAULT_GAUSSIAN_KERNEL)
                m = adaptiveThreshold(m, DEFAULT_ADAPTIVE_BLOCK, DEFAULT_ADAPTIVE_C)
                normalizePolarityAndClean(m)
            }
            "LAB_L" -> {
                // LAB の L（輝度）チャンネル
                // → 照明変化に最も強い成分。影・反射に強い
                var m = resize(srcMat, DEFAULT_TARGET_SIZE, DEFAULT_TARGET_SIZE)
                m = extractLabL(m)
                m = applyCLAHE(m, DEFAULT_CLAHE_CLIP_LIMIT, DEFAULT_CLAHE_TILE_SIZE)
                m = gaussianBlur(m, DEFAULT_GAUSSIAN_KERNEL)
                m = adaptiveThreshold(m, DEFAULT_ADAPTIVE_BLOCK, DEFAULT_ADAPTIVE_C)
                normalizePolarityAndClean(m)
            }

            // ── Group G: 全改善 ──────────────────────────────────────
            "FULL" -> {
                var m = resize(srcMat, DEFAULT_TARGET_SIZE, DEFAULT_TARGET_SIZE)
                m = toGrayscale(m)
                m = correctPerspective(m) ?: m
                m = normalizeBackground(m)
                m = applyCLAHE(m, DEFAULT_CLAHE_CLIP_LIMIT, DEFAULT_CLAHE_TILE_SIZE)
                m = bilateralFilter(m)
                m = adaptiveThreshold(m, DEFAULT_ADAPTIVE_BLOCK, DEFAULT_ADAPTIVE_C)
                normalizePolarityAndClean(m)
            }
            "FULL_GRAY" -> {
                var m = resize(srcMat, DEFAULT_TARGET_SIZE, DEFAULT_TARGET_SIZE)
                m = toGrayscale(m)
                m = correctPerspective(m) ?: m
                m = normalizeBackground(m)
                m = applyCLAHE(m, DEFAULT_CLAHE_CLIP_LIMIT, DEFAULT_CLAHE_TILE_SIZE)
                bilateralFilter(m)
            }

            // ── 白黒なし版（_GRAY サフィックス）────────────────────────
            "GAUSSIAN_GRAY" -> {
                var m = resize(srcMat, DEFAULT_TARGET_SIZE, DEFAULT_TARGET_SIZE)
                m = toGrayscale(m)
                m = applyCLAHE(m, DEFAULT_CLAHE_CLIP_LIMIT, DEFAULT_CLAHE_TILE_SIZE)
                gaussianBlur(m, DEFAULT_GAUSSIAN_KERNEL)
            }
            "BILATERAL_GRAY" -> {
                var m = resize(srcMat, DEFAULT_TARGET_SIZE, DEFAULT_TARGET_SIZE)
                m = toGrayscale(m)
                m = applyCLAHE(m, DEFAULT_CLAHE_CLIP_LIMIT, DEFAULT_CLAHE_TILE_SIZE)
                bilateralFilter(m)
            }
            "MEDIAN_GRAY" -> {
                var m = resize(srcMat, DEFAULT_TARGET_SIZE, DEFAULT_TARGET_SIZE)
                m = toGrayscale(m)
                m = applyCLAHE(m, DEFAULT_CLAHE_CLIP_LIMIT, DEFAULT_CLAHE_TILE_SIZE)
                medianBlur(m, 5)
            }
            "UNSHARP_GRAY" -> {
                var m = resize(srcMat, DEFAULT_TARGET_SIZE, DEFAULT_TARGET_SIZE)
                m = toGrayscale(m)
                m = applyCLAHE(m, DEFAULT_CLAHE_CLIP_LIMIT, DEFAULT_CLAHE_TILE_SIZE)
                unsharpMask(m)
            }
            "CLAHE_GRAY" -> {
                var m = resize(srcMat, DEFAULT_TARGET_SIZE, DEFAULT_TARGET_SIZE)
                m = toGrayscale(m)
                applyCLAHE(m, DEFAULT_CLAHE_CLIP_LIMIT, DEFAULT_CLAHE_TILE_SIZE)
            }
            "GAMMA_BRIGHT_GRAY" -> {
                var m = resize(srcMat, DEFAULT_TARGET_SIZE, DEFAULT_TARGET_SIZE)
                m = toGrayscale(m)
                m = gammaCorrection(m, 0.5)
                gaussianBlur(m, DEFAULT_GAUSSIAN_KERNEL)
            }
            "GAMMA_DARK_GRAY" -> {
                var m = resize(srcMat, DEFAULT_TARGET_SIZE, DEFAULT_TARGET_SIZE)
                m = toGrayscale(m)
                m = gammaCorrection(m, 2.0)
                gaussianBlur(m, DEFAULT_GAUSSIAN_KERNEL)
            }
            "BG_NORM_GRAY" -> {
                var m = resize(srcMat, DEFAULT_TARGET_SIZE, DEFAULT_TARGET_SIZE)
                m = toGrayscale(m)
                m = normalizeBackground(m)
                applyCLAHE(m, 3.0, DEFAULT_CLAHE_TILE_SIZE)
            }
            "SHADOW_GRAY" -> {
                var m = resize(srcMat, DEFAULT_TARGET_SIZE, DEFAULT_TARGET_SIZE)
                m = toGrayscale(m)
                m = shadowRemoval(m)
                applyCLAHE(m, 3.0, DEFAULT_CLAHE_TILE_SIZE)
            }
            "CHANNEL_R_GRAY" -> {
                var m = resize(srcMat, DEFAULT_TARGET_SIZE, DEFAULT_TARGET_SIZE)
                m = extractRChannel(m)
                applyCLAHE(m, DEFAULT_CLAHE_CLIP_LIMIT, DEFAULT_CLAHE_TILE_SIZE)
            }
            "HSV_V_GRAY" -> {
                var m = resize(srcMat, DEFAULT_TARGET_SIZE, DEFAULT_TARGET_SIZE)
                m = extractHsvV(m)
                applyCLAHE(m, DEFAULT_CLAHE_CLIP_LIMIT, DEFAULT_CLAHE_TILE_SIZE)
            }
            "LAB_L_GRAY" -> {
                var m = resize(srcMat, DEFAULT_TARGET_SIZE, DEFAULT_TARGET_SIZE)
                m = extractLabL(m)
                applyCLAHE(m, DEFAULT_CLAHE_CLIP_LIMIT, DEFAULT_CLAHE_TILE_SIZE)
            }

            else -> resize(srcMat, DEFAULT_TARGET_SIZE, DEFAULT_TARGET_SIZE)
        }

        // グレースケール Mat を ARGB_8888 Bitmap に変換
        val displayMat = Mat()
        when (mat.channels()) {
            1 -> Imgproc.cvtColor(mat, displayMat, Imgproc.COLOR_GRAY2RGBA)
            3 -> Imgproc.cvtColor(mat, displayMat, Imgproc.COLOR_BGR2RGBA)
            4 -> mat.copyTo(displayMat)
            else -> mat.copyTo(displayMat)
        }
        val result = Bitmap.createBitmap(
            displayMat.cols(), displayMat.rows(), Bitmap.Config.ARGB_8888
        )
        Utils.matToBitmap(displayMat, result)

        srcMat.release()
        if (mat != srcMat) mat.release()
        displayMat.release()
        return result
    }

    // ================================================================
    // ⑤ メインパイプライン（通常モード）
    // ================================================================

    private fun preprocessWithMode(bitmap: Bitmap, m: Mode): Bitmap {
        val srcMat = Mat()
        Utils.bitmapToMat(bitmap, srcMat)
        var mat = srcMat

        mat = resize(mat, DEFAULT_TARGET_SIZE, DEFAULT_TARGET_SIZE)
        mat = toGrayscale(mat)
        if (m == Mode.PERSPECTIVE || m == Mode.FULL) mat = correctPerspective(mat) ?: mat
        if (m == Mode.BACKGROUND_NORM || m == Mode.FULL) mat = normalizeBackground(mat)
        val clipLimit = if (m == Mode.BACKGROUND_NORM) 3.0 else DEFAULT_CLAHE_CLIP_LIMIT
        mat = applyCLAHE(mat, clipLimit, DEFAULT_CLAHE_TILE_SIZE)
        mat = if (m == Mode.FULL) bilateralFilter(mat)
              else gaussianBlur(mat, DEFAULT_GAUSSIAN_KERNEL)
        mat = adaptiveThreshold(mat, DEFAULT_ADAPTIVE_BLOCK, DEFAULT_ADAPTIVE_C)
        mat = normalizePolarityAndClean(mat)

        val displayMat = Mat()
        Imgproc.cvtColor(mat, displayMat, Imgproc.COLOR_GRAY2RGBA)
        val result = Bitmap.createBitmap(
            displayMat.cols(), displayMat.rows(), Bitmap.Config.ARGB_8888
        )
        Utils.matToBitmap(displayMat, result)
        srcMat.release(); mat.release(); displayMat.release()
        return result
    }

    // ================================================================
    // ⑥ 既存の前処理ステップ
    // ================================================================

    /**
     * Canny + findContours + approxPolyDP で最大四角形を検出し
     * warpPerspective で正面視に変換する。
     * 四角形を検出できない場合は null を返す。
     */
    private fun correctPerspective(mat: Mat): Mat? {
        val blurred = Mat()
        Imgproc.GaussianBlur(mat, blurred, Size(5.0, 5.0), 0.0)
        val edges = Mat()
        Imgproc.Canny(blurred, edges, 50.0, 150.0)
        val dilated = Mat()
        val k3 = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
        Imgproc.dilate(edges, dilated, k3)

        val contours = mutableListOf<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(
            dilated, contours, hierarchy,
            Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE
        )
        blurred.release(); edges.release(); dilated.release()
        k3.release(); hierarchy.release()

        val imageArea = mat.cols().toDouble() * mat.rows()
        for (contour in contours.sortedByDescending { Imgproc.contourArea(it) }.take(8)) {
            val area = Imgproc.contourArea(contour)
            if (area < imageArea * PERSPECTIVE_MIN_AREA) break
            if (area > imageArea * PERSPECTIVE_MAX_AREA) continue

            val mat2f  = MatOfPoint2f(*contour.toArray())
            val peri   = Imgproc.arcLength(mat2f, true)
            val approx = MatOfPoint2f()
            Imgproc.approxPolyDP(mat2f, approx, 0.02 * peri, true)
            if (approx.rows() != 4) continue

            val sorted = sortCorners(approx.toArray())
            val outW = maxOf(dist(sorted[0], sorted[1]), dist(sorted[3], sorted[2])).toInt()
            val outH = maxOf(dist(sorted[0], sorted[3]), dist(sorted[1], sorted[2])).toInt()
            if (outW < 60 || outH < 60) continue

            val src = MatOfPoint2f(*sorted)
            val dst = MatOfPoint2f(
                Point(0.0, 0.0),
                Point(outW.toDouble(), 0.0),
                Point(outW.toDouble(), outH.toDouble()),
                Point(0.0, outH.toDouble())
            )
            val M = Imgproc.getPerspectiveTransform(src, dst)
            val warped = Mat()
            Imgproc.warpPerspective(
                mat, warped, M,
                Size(outW.toDouble(), outH.toDouble()), Imgproc.INTER_LINEAR
            )
            M.release()
            return warped
        }
        return null
    }

    private fun sortCorners(pts: Array<Point>): Array<Point> {
        val sums  = pts.map { it.x + it.y }
        val diffs = pts.map { it.x - it.y }
        return arrayOf(
            pts[sums.indexOf(sums.min())],
            pts[diffs.indexOf(diffs.min())],
            pts[sums.indexOf(sums.max())],
            pts[diffs.indexOf(diffs.max())]
        )
    }

    private fun dist(a: Point, b: Point): Double {
        val dx = b.x - a.x; val dy = b.y - a.y
        return sqrt(dx * dx + dy * dy)
    }

    /**
     * MORPH_CLOSE で背景を推定し、元画像を除算することで
     * 錆・照明ムラ（低周波成分）を除去する。
     */
    private fun normalizeBackground(mat: Mat): Mat {
        var ks = minOf(mat.cols(), mat.rows()) / BG_KERNEL_DIVISOR
        if (ks < 5) ks = 5
        if (ks % 2 == 0) ks++
        val kernel     = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(ks.toDouble(), ks.toDouble()))
        val background = Mat()
        Imgproc.morphologyEx(mat, background, Imgproc.MORPH_CLOSE, kernel)

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

    private fun bilateralFilter(mat: Mat): Mat {
        val result = Mat()
        Imgproc.bilateralFilter(
            mat, result,
            BILATERAL_D, BILATERAL_SIGMA_COLOR, BILATERAL_SIGMA_SPACE
        )
        mat.release()
        return result
    }

    /**
     * 極性正規化（暗背景を反転）+ MORPH_OPEN（ノイズ除去）+ MORPH_CLOSE（穴埋め）
     */
    private fun normalizePolarityAndClean(mat: Mat): Mat {
        var result = mat
        val whitePixels = Core.countNonZero(result)
        val totalPixels = result.rows() * result.cols()
        if (whitePixels < totalPixels * 0.5) {
            val inverted = Mat()
            Core.bitwise_not(result, inverted)
            result.release(); result = inverted
        }
        val kernelOpen = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
        val opened = Mat()
        Imgproc.morphologyEx(result, opened, Imgproc.MORPH_OPEN, kernelOpen)
        result.release(); result = opened; kernelOpen.release()

        val kernelClose = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
        val closed = Mat()
        Imgproc.morphologyEx(result, closed, Imgproc.MORPH_CLOSE, kernelClose)
        result.release(); result = closed; kernelClose.release()
        return result
    }

    // ================================================================
    // ⑦ 新規追加ステップ（比較条件用）
    // ================================================================

    /**
     * メジアンフィルタ。
     * ゴマ塩ノイズ（錆による小孔・白点）に対してガウシアンより効果的。
     * kernelSize は奇数で指定（3, 5, 7）。
     */
    private fun medianBlur(mat: Mat, kernelSize: Int): Mat {
        val result = Mat()
        Imgproc.medianBlur(mat, result, kernelSize)
        mat.release()
        return result
    }

    /**
     * Otsu二値化。
     * ヒストグラムが双峰性（背景と文字が明確に分離）のとき最適な閾値を自動計算。
     * 均一照明で文字がはっきりしている場合に有効。
     */
    private fun otsuThreshold(mat: Mat): Mat {
        val result = Mat()
        Imgproc.threshold(
            mat, result, 0.0, 255.0,
            Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU
        )
        mat.release()
        return result
    }

    /**
     * グローバル固定閾値二値化。
     * 照明が均一で事前に適切な閾値がわかっている場合に使用。
     */
    private fun globalThreshold(mat: Mat, threshold: Double): Mat {
        val result = Mat()
        Imgproc.threshold(mat, result, threshold, 255.0, Imgproc.THRESH_BINARY)
        mat.release()
        return result
    }

    /**
     * ヒストグラム均等化。
     * 画像全体のコントラストを一様に強調する。
     * CLAHE と異なり局所ではなく全体に適用されるため、
     * 照明が均一な画像に向く。
     */
    private fun histogramEqualization(mat: Mat): Mat {
        val result = Mat()
        Imgproc.equalizeHist(mat, result)
        mat.release()
        return result
    }

    /**
     * ガンマ補正。
     * γ < 1.0 → 暗部を明るく（日陰・反射で暗くなった文字に有効）
     * γ > 1.0 → 明部を暗く（白飛び・過露光対策）
     * LUT（ルックアップテーブル）で高速に実行。
     */
    private fun gammaCorrection(mat: Mat, gamma: Double): Mat {
        val lut = Mat(1, 256, CvType.CV_8U)
        val lutData = ByteArray(256)
        for (i in 0..255) {
            lutData[i] = (Math.pow(i / 255.0, gamma) * 255.0).toInt().coerceIn(0, 255).toByte()
        }
        lut.put(0, 0, lutData)
        val result = Mat()
        Core.LUT(mat, lut, result)
        lut.release(); mat.release()
        return result
    }

    /**
     * アンシャープマスク（シャープ化）。
     * blurred = ガウシアンブラー済み画像
     * result  = original × (1+amount) - blurred × amount
     * 文字エッジを強調し、ぼけた打刻文字を鮮明にする。
     */
    private fun unsharpMask(mat: Mat, amount: Double = 1.5): Mat {
        val blurred = Mat()
        Imgproc.GaussianBlur(mat, blurred, Size(5.0, 5.0), 0.0)
        val result = Mat()
        Core.addWeighted(mat, 1.0 + amount, blurred, -amount, 0.0, result)
        blurred.release(); mat.release()
        return result
    }

    /**
     * 影除去（大カーネルモルフォロジー）。
     * normalizeBackground より大きいカーネルを使い、
     * 広い影・強い照明ムラを除去する。
     */
    private fun shadowRemoval(mat: Mat): Mat {
        var ks = minOf(mat.cols(), mat.rows()) / 4  // BG_NORMより大きいカーネル
        if (ks < 15) ks = 15
        if (ks % 2 == 0) ks++

        val kernel     = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(ks.toDouble(), ks.toDouble()))
        val background = Mat()
        Imgproc.morphologyEx(mat, background, Imgproc.MORPH_DILATE, kernel)

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

    /**
     * Rチャンネル抽出（RGBA → R成分のみ）。
     * 黄色・白色ペンマーカーはRチャンネルにコントラストが出やすい。
     * 赤錆背景との差異が大きい場合に有効。
     */
    private fun extractRChannel(mat: Mat): Mat {
        if (mat.channels() == 1) return mat
        val channels = mutableListOf<Mat>()
        Core.split(mat, channels)
        val result = channels[2].clone()   // BGRA or BGR: idx 2 = R
        channels.forEach { it.release() }
        mat.release()
        return result
    }

    /**
     * HSV の V チャンネル（明度）抽出。
     * 色相・彩度を無視し、明るさだけで文字を抽出する。
     * 色の異なる複数種類のマーカーに対して安定した結果を得やすい。
     */
    private fun extractHsvV(mat: Mat): Mat {
        val bgr = Mat()
        when (mat.channels()) {
            4    -> Imgproc.cvtColor(mat, bgr, Imgproc.COLOR_BGRA2BGR)
            1    -> {
                mat.copyTo(bgr)
                return mat
            }
            else -> mat.copyTo(bgr)
        }
        val hsv = Mat()
        Imgproc.cvtColor(bgr, hsv, Imgproc.COLOR_BGR2HSV)
        val channels = mutableListOf<Mat>()
        Core.split(hsv, channels)
        val result = channels[2].clone()   // HSV: idx 2 = V
        bgr.release(); hsv.release()
        channels.forEach { it.release() }
        mat.release()
        return result
    }

    /**
     * LAB の L チャンネル（輝度）抽出。
     * 人間の視覚特性に基づく輝度成分で、照明変化に最も強い。
     * 影・白飛び・反射が混在する鉄骨現場に特に有効。
     */
    private fun extractLabL(mat: Mat): Mat {
        val bgr = Mat()
        when (mat.channels()) {
            4    -> Imgproc.cvtColor(mat, bgr, Imgproc.COLOR_BGRA2BGR)
            1    -> {
                mat.copyTo(bgr)
                return mat
            }
            else -> mat.copyTo(bgr)
        }
        val lab = Mat()
        Imgproc.cvtColor(bgr, lab, Imgproc.COLOR_BGR2Lab)
        val channels = mutableListOf<Mat>()
        Core.split(lab, channels)
        val result = channels[0].clone()   // LAB: idx 0 = L
        bgr.release(); lab.release()
        channels.forEach { it.release() }
        mat.release()
        return result
    }

    // ================================================================
    // ⑧ 共通ユーティリティ
    // ================================================================

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
        Imgproc.GaussianBlur(
            mat, result,
            Size(kernelSize.toDouble(), kernelSize.toDouble()), 0.0
        )
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

    // ================================================================
    // ⑨ データクラス
    // ================================================================

    data class PreprocessResult(
        val bitmap: Bitmap,
        val totalTimeMs: Long,
        val stepTimings: Map<String, Long>
    )
}
