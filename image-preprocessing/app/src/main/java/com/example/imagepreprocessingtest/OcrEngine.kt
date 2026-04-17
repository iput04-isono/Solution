/**
 * OcrEngine.kt
 *
 * MLKit Text Recognition をラップした OCR エンジン。
 * 前処理済み Bitmap を受け取り、認識テキストと信頼度を返す。
 *
 * 特徴:
 *   - オフライン動作（モデルは初回 Play Store インストール時に端末に保存）
 *   - 無料・追加モデルファイル不要
 *   - Latin 文字（英数字・記号）対応 → 1C-X3Y1 などのラベルに最適
 *   - suspend 関数でコルーチンから呼び出し可能
 */
package com.example.imagepreprocessingtest

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/** OCR 結果を保持するデータクラス */
data class OcrResult(
    val text: String,           // 認識されたテキスト全体
    val confidence: Float,      // 信頼度（0.0〜1.0）。MLKit が返す各要素の平均値
    val rawBlocks: String       // デバッグ用: ブロック単位の詳細テキスト
)

object OcrEngine {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    /**
     * Bitmap に対して MLKit OCR を実行し、結果を返す。
     *
     * @param bitmap 前処理済み画像（ARGB_8888）
     * @return OcrResult（テキスト・信頼度）
     * @throws Exception 認識失敗時
     */
    suspend fun recognize(bitmap: Bitmap): OcrResult = suspendCancellableCoroutine { cont ->
        val image = InputImage.fromBitmap(bitmap, 0)

        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                val fullText = visionText.text.trim()

                // 各 Element の confidence を収集して平均を信頼度とする
                val confidences = mutableListOf<Float>()
                val blockDetails = StringBuilder()

                for (block in visionText.textBlocks) {
                    for (line in block.lines) {
                        blockDetails.appendLine("[LINE] ${line.text}")
                        for (element in line.elements) {
                            val conf = element.confidence ?: 0.8f  // null の場合は 0.8 とみなす
                            confidences.add(conf)
                            blockDetails.appendLine("  [ELEM] ${element.text}  conf=${"%.2f".format(conf)}")
                        }
                    }
                }

                val avgConf = if (confidences.isEmpty()) 0f else confidences.average().toFloat()

                cont.resume(
                    OcrResult(
                        text       = fullText.ifEmpty { "（テキスト未検出）" },
                        confidence = avgConf,
                        rawBlocks  = blockDetails.toString().trim()
                    )
                )
            }
            .addOnFailureListener { e ->
                cont.resumeWithException(e)
            }
    }

    /**
     * 信頼度スコアを文字列ラベルに変換する。
     * UI 表示用のシンプルなヘルパー。
     */
    fun confidenceLabel(confidence: Float): String = when {
        confidence >= 0.85f -> "✅ 高 (${"%.0f".format(confidence * 100)}%)"
        confidence >= 0.60f -> "⚠️ 中 (${"%.0f".format(confidence * 100)}%)"
        confidence >  0f    -> "❌ 低 (${"%.0f".format(confidence * 100)}%)"
        else                -> "－ 未検出"
    }
}
