package com.example.mainproject.ocr

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * OcrEngine + LabelMatcher を束ねるオーケストレータ。
 *
 * [processWithOverlay] を呼ぶと：
 *  - 認識領域を色付き枠で描画した overlay Bitmap
 *  - 編集距離 ≤ 3 の登録候補リスト [matched]
 *  - 編集距離 > 3 の参考情報リスト [unmatched]
 * をまとめて返す。
 */
class OcrProcessor(context: Context) {

    private val labelMatcher = LabelMatcher(context)
    private val engine       = OcrEngine(context, labelMatcher)
    private val preprocessor = ImagePreprocessor()

    /** processWithOverlay の戻り値 */
    data class Result(
        val overlayBitmap : Bitmap,
        val matched       : List<MatchedItem>,
        val unmatched     : List<UnmatchedItem>
    )

    /** 編集距離 ≤ 3 のマッチ結果 */
    data class MatchedItem(
        val label      : String,   // 正解ラベル
        val distance   : Int,      // 編集距離（0=完全一致）
        val rawOcrText : String,   // OCR 生テキスト
        val confidence : Float
    )

    /** 編集距離 > 3 または未マッチの参考情報 */
    data class UnmatchedItem(
        val rawOcrText : String,
        val confidence : Float
    )

    /**
     * 画像を受け取り、多角形検出 OCR + LabelMatcher 照合を行い結果を返す。
     * 重い処理なので IO ディスパッチャで呼ぶこと。
     */
    suspend fun processWithOverlay(bitmap: Bitmap): Result = withContext(Dispatchers.Default) {
        val processed = preprocessor.preprocess(bitmap)
        val (overlayBitmap, ocrResults) = engine.runOcrPolygonWithOverlay(processed)

        val matched   = mutableListOf<MatchedItem>()
        val unmatched = mutableListOf<UnmatchedItem>()

        for (r in ocrResults) {
            val text = r.text.trim()
            if (text.isEmpty()) continue
            val best = labelMatcher.findBest(text)   // Pair<String, Int>?
            if (best != null && best.second <= LabelMatcher.MAX_EDIT_DISTANCE) {
                matched.add(MatchedItem(best.first, best.second, text, r.confidence))
            } else {
                unmatched.add(UnmatchedItem(text, r.confidence))
            }
        }

        Result(overlayBitmap, matched, unmatched)
    }

    fun close() = engine.close()
}
