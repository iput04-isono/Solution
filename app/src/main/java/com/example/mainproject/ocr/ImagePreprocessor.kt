package com.example.mainproject.ocr

import android.graphics.Bitmap

/**
 * 画像前処理モジュール
 *
 * 【方針】
 * 実機テストの結果、二値化・グレースケール変換・コントラスト強化などの
 * 外部前処理はPaddleOCRの認識精度を低下させることが確認されました。
 * そのため、PaddleOCRの内部正規化に任せる方針とし、
 * このクラスではリサイズのみを行います。
 *
 * PaddleOCRのdet.onnx（検出モデル）はカラー画像（3チャンネル）を
 * 前提としているため、グレースケール変換も行いません。
 *
 * 【使い方】
 *   val preprocessor = ImagePreprocessor()
 *   val processed: Bitmap = preprocessor.preprocess(originalBitmap)
 *   // → OcrEngine.runOcr(processed) に渡す
 */
class ImagePreprocessor {

    /**
     * 画像の前処理を実行する。
     * 現在はリサイズのみ。カラー情報はそのまま保持する。
     *
     * @param  bitmap 入力画像（撮影した鉄骨画像など）
     * @return リサイズ済み Bitmap（OcrEngine に渡す用）
     */
    fun preprocess(bitmap: Bitmap): Bitmap = resize(bitmap, MAX_SIZE)

    /** 長辺が maxSize を超えていればアスペクト比を保ってリサイズ */
    private fun resize(bitmap: Bitmap, maxSize: Int): Bitmap {
        val w = bitmap.width; val h = bitmap.height
        if (w <= maxSize && h <= maxSize) return bitmap
        val scale = maxSize.toFloat() / maxOf(w, h)
        return Bitmap.createScaledBitmap(bitmap, (w * scale).toInt(), (h * scale).toInt(), true)
    }

    companion object {
        /** 処理する画像の最大長辺サイズ（px） */
        const val MAX_SIZE = 1280
    }
}
