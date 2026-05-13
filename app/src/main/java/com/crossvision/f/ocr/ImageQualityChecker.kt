package com.crossvision.f.ocr

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.sqrt

/**
 * 画像の品質（ぼけ、傾きなど）をチェックするユーティリティクラス。
 */
object ImageQualityChecker {

    /**
     * Laplacian分散を用いて画像の「鮮明さ」を計算する。
     * 数値が高いほど鮮明（エッジがはっきりしている）であることを示す。
     * 閾値100未満を「ぼけ」と判定する指標として一般的に使われる。
     */
    fun calculateBlurScore(bitmap: Bitmap): Double {
        // 処理高速化のため、リサイズ（小サイズでもぼけ判定は可能）
        val scaledWidth = 320
        val scaledHeight = (bitmap.height * (scaledWidth.toFloat() / bitmap.width)).toInt()
        val scaled = Bitmap.createScaledBitmap(bitmap, scaledWidth, scaledHeight, false)

        val width = scaled.width
        val height = scaled.height
        val pixels = IntArray(width * height)
        scaled.getPixels(pixels, 0, width, 0, 0, width, height)

        // グレースケール化
        val gray = FloatArray(width * height)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = Color.red(p)
            val g = Color.green(p)
            val b = Color.blue(p)
            gray[i] = (0.299f * r + 0.587f * g + 0.114f * b)
        }

        // Laplacianフィルタ適用 (3x3 kernel)
        // [ 0,  1,  0]
        // [ 1, -4,  1]
        // [ 0,  1,  0]
        val laplacian = FloatArray(width * height)
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val idx = y * width + x
                laplacian[idx] = (
                    gray[idx - width] +
                    gray[idx - 1] - 4 * gray[idx] + gray[idx + 1] +
                    gray[idx + width]
                )
            }
        }

        // 分散の計算
        var sum = 0.0
        for (v in laplacian) sum += v
        val mean = sum / laplacian.size

        var sumSq = 0.0
        for (v in laplacian) {
            val diff = v - mean
            sumSq += diff * diff
        }

        return sumSq / laplacian.size
    }

    /**
     * 検出された文字列が画面に対して極端に傾いているか判定する。
     * 画面の水平方向に対して ±15度 以内であれば正常とみなす。
     */
    fun isTextTilted(polygons: List<FloatArray>): Boolean {
        if (polygons.isEmpty()) return false
        for (poly in polygons) {
            if (poly.size < 4) continue
            // 点0(x0,y0) と 点1(x1,y1) から角度を計算
            val dx = (poly[2] - poly[0]).toDouble()
            val dy = (poly[3] - poly[1]).toDouble()
            val angleRad = Math.atan2(dy, dx)
            val angleDeg = Math.abs(Math.toDegrees(angleRad))

            // 0度(右向き), 180度(左向き) からのズレをチェック
            // 縦書き（90度）は今回の要件（製品番号）では対象外とする
            val diffFromHorizontal = if (angleDeg > 90) Math.abs(180 - angleDeg) else angleDeg
            if (diffFromHorizontal > 15.0) return true
        }
        return false
    }

    /**
     * 検出された文字列が画面の端にかかっている（見切れている）か判定する。
     */
    fun isTextCutOff(polygons: List<FloatArray>, width: Int, height: Int): Boolean {
        if (polygons.isEmpty()) return false
        val marginRatio = 0.03f // 画面端 3% を見切れ境界とする
        val marginX = width * marginRatio
        val marginY = height * marginRatio

        for (poly in polygons) {
            for (i in 0 until poly.size / 2) {
                val x = poly[i * 2]
                val y = poly[i * 2 + 1]
                if (x < marginX || x > width - marginX || y < marginY || y > height - marginY) {
                    return true
                }
            }
        }
        return false
    }
}
