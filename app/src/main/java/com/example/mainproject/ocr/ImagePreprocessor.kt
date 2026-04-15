package com.example.mainproject.ocr

import android.graphics.*

/**
 * 画像前処理モジュール（簡易版 / 差し替え可能スタブ）
 *
 * 現在は Android 標準 API のみを使用した簡易実装です。
 * 画像処理班から本格版（OpenCV実装）が提供された際は、
 * このファイルを上書きするだけで差し替えが完了します。
 * 呼び出し側のコードは変更不要です。
 *
 * 【本格版への差し替え時】
 *   app/build.gradle.kts に以下を追加:
 *     implementation("org.opencv:opencv:4.9.0")
 *
 * 【使い方】
 *   val preprocessor = ImagePreprocessor()
 *   val processed: Bitmap = preprocessor.preprocess(originalBitmap)
 *   // → OcrEngine.runFullOcr(processed) に渡す
 */
class ImagePreprocessor {

    /**
     * 画像前処理を実行する。
     *
     * @param  bitmap 入力画像（撮影した鉄骨画像など）
     * @return 前処理済み Bitmap（OcrEngine に渡す用）
     */
    fun preprocess(bitmap: Bitmap): Bitmap {
        var result = resize(bitmap, MAX_SIZE)
        result = toGrayscale(result)
        result = enhanceContrast(result)
        return result
    }

    /** 長辺が maxSize を超えていればアスペクト比を保ってリサイズ */
    private fun resize(bitmap: Bitmap, maxSize: Int): Bitmap {
        val w = bitmap.width; val h = bitmap.height
        if (w <= maxSize && h <= maxSize) return bitmap
        val scale = maxSize.toFloat() / maxOf(w, h)
        return Bitmap.createScaledBitmap(bitmap, (w * scale).toInt(), (h * scale).toInt(), true)
    }

    /** カラー → グレースケール変換 */
    private fun toGrayscale(bitmap: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val cm = ColorMatrix().apply { setSaturation(0f) }
        Canvas(out).drawBitmap(bitmap, 0f, 0f, Paint().apply {
            colorFilter = ColorMatrixColorFilter(cm)
        })
        return out
    }

    /**
     * ヒストグラムストレッチによるコントラスト強化。
     * 本格版では CLAHE（局所コントラスト強化）に置き換わります。
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

    companion object {
        const val MAX_SIZE = 960
    }
}
