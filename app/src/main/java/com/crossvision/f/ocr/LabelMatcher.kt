package com.crossvision.f.ocr

import android.content.Context
import android.util.Log

/**
 * OCR認識結果を正解製品コードリストと照合するクラス。
 *
 * Levenshtein編集距離（≤3）を用いたファジーマッチングで、
 * OCRの読み誤りに強い製品コード特定を実現する。
 *
 * 【使い方】
 *   val matcher = LabelMatcher(context)
 *   val match = matcher.findBest("B15b3ON-7A")
 *   // match?.label  → "B15b30N-7A"
 *   // match?.distance → 1
 *
 * 【必要なassets】
 *   product_labels.txt  製品コード一覧（1行1コード、ASCII文字のみ）
 */
class LabelMatcher(context: Context) {

    val labels: List<String>
    private val normalizedLabels: List<String>

    init {
        labels = loadLabels(context)
        normalizedLabels = labels.map { normalize(it) }
        Log.d(TAG, "ラベル読み込み完了: ${labels.size}件")
    }

    private fun loadLabels(context: Context): List<String> {
        return try {
            context.assets.open(LABELS_ASSET_PATH)
                .bufferedReader(Charsets.UTF_8)
                .readLines()
                .map { it.trim() }
                .filter { line ->
                    line.isNotEmpty()
                        && line != "xxxxxxxxxx"
                        && line.all { it.code < 128 }
                }
                .distinct()
        } catch (e: Exception) {
            Log.e(TAG, "ラベルファイル読み込み失敗: $LABELS_ASSET_PATH", e)
            emptyList()
        }
    }

    /**
     * OCR文字列を正規化（大文字化・空白除去）
     */
    private fun normalize(text: String): String =
        text.uppercase().trim().replace(" ", "").replace("\u3000", "")

    /**
     * OCRの読み誤りパターンを考慮したバリアント生成。
     * 例: 0↔O, 1↔I, 5↔S など
     */
    private fun generateVariants(text: String): Set<String> {
        val variants = mutableSetOf(text)
        val substitutions = mapOf(
            '0' to 'O', 'O' to '0',
            '1' to 'I', 'I' to '1',
            '5' to 'S', 'S' to '5',
            '8' to 'B', 'B' to '8',
            '6' to 'G', 'G' to '6',
            '2' to 'Z', 'Z' to '2'
        )
        for (i in text.indices) {
            val sub = substitutions[text[i]]
            if (sub != null) {
                variants.add(text.substring(0, i) + sub + text.substring(i + 1))
            }
        }
        return variants
    }

    /**
     * Levenshtein編集距離を計算する。
     */
    private fun editDistance(a: String, b: String): Int {
        val m = a.length
        val n = b.length
        val dp = Array(m + 1) { IntArray(n + 1) }
        for (i in 0..m) dp[i][0] = i
        for (j in 0..n) dp[0][j] = j
        for (i in 1..m) {
            for (j in 1..n) {
                dp[i][j] = if (a[i - 1] == b[j - 1]) {
                    dp[i - 1][j - 1]
                } else {
                    1 + minOf(dp[i - 1][j], dp[i][j - 1], dp[i - 1][j - 1])
                }
            }
        }
        return dp[m][n]
    }

    /**
     * 上位候補を返す（編集距離が小さい順、最大[maxResults]件）。
     */
    fun findTopCandidates(ocrText: String, maxResults: Int = 3): List<MatchResult> {
        if (ocrText.length < MIN_OCR_LEN) return emptyList()

        val normOcr = normalize(ocrText)
        val variants = generateVariants(normOcr)

        val candidates = normalizedLabels.mapIndexed { idx, normLabel ->
            val minDist = variants.minOf { editDistance(it, normLabel) }
            minDist to idx
        }.filter { (dist, _) -> dist <= MAX_EDIT_DISTANCE }
            .sortedBy { (dist, _) -> dist }
            .take(maxResults)

        return candidates.map { (dist, idx) ->
            MatchResult(
                label = labels[idx],
                distance = dist,
                isExactMatch = dist == 0
            )
        }
    }

    /**
     * 最もスコアの高い1件を返す。マッチなしは null。
     */
    fun findBest(ocrText: String): MatchResult? =
        findTopCandidates(ocrText, maxResults = 1).firstOrNull()

    companion object {
        private const val TAG = "LabelMatcher"
        const val MIN_OCR_LEN = 4
        const val MAX_EDIT_DISTANCE = 3
        const val LABELS_ASSET_PATH = "product_labels.txt"
    }
}

/**
 * ラベル照合結果
 */
data class MatchResult(
    val label: String,       // 照合された正解ラベル
    val distance: Int,       // 編集距離（0=完全一致）
    val isExactMatch: Boolean
)
