package com.crossvision.f.ocr

import android.content.Context
import android.util.Log
import com.crossvision.f.data.local.AppDatabase

/**
 * OCR認識結果を正解製品コードリストと照合するクラス。
 *
 * Levenshtein編集距離（≤3）を用いたファジーマッチングで、
 * OCRの読み誤りに強い製品コード特定を実現する。
 *
 * 【ラベルの読み込み優先順位】
 *   1. Room DB（product_labels テーブル）
 *      → SyncWorker によってサーバーから取得・更新された最新データ
 *   2. assets/product_labels.txt（フォールバック）
 *      → DB が空（初回起動・オフライン）の場合に APK 同梱の初期データを使用
 *
 * 【使い方（suspend 版・推奨）】
 *   val matcher = LabelMatcher.create(context)   // DB から非同期読み込み
 *   val match = matcher.findBest("B15b3ON-7A")
 *
 * 【使い方（同期版・後方互換）】
 *   val matcher = LabelMatcher(context)           // assets から同期読み込み
 */
class LabelMatcher private constructor(rawLabels: List<String>, source: String) {

    val labels: List<String> = rawLabels
    private val normalizedLabels: List<String> = labels.map { normalize(it) }

    init {
        Log.d(TAG, "ラベル読み込み完了: ${labels.size}件 [$source]")
    }

    // ──────────────────────────────────────────────────────────────────────
    // 照合処理
    // ──────────────────────────────────────────────────────────────────────

    /**
     * 上位候補を返す（編集距離が小さい順、タイブレークあり）。
     */
    fun findTopCandidates(ocrText: String, maxResults: Int = 3): List<MatchResult> {
        if (ocrText.length < MIN_OCR_LEN) return emptyList()

        val normOcr = normalize(ocrText)
        val variants = generateVariants(normOcr)

        // 全ラベルに対してスコアを計算
        val scoredCandidates = normalizedLabels.mapIndexed { idx, normLabel ->
            // オリジナルのOCR文字と各種バリアント（0/O変換など）の中で最小の距離を採用
            val minDist = variants.minOf { editDistance(it, normLabel) }
            
            // スコア要素: 1.編集距離, 2.文字数差, 3.元の位置(インデックス)
            Candidate(
                index = idx,
                distance = minDist,
                lengthDiff = Math.abs(normOcr.length - normLabel.length)
            )
        }

        // フィルタとソート
        val filtered = scoredCandidates
            .filter { it.distance <= MAX_EDIT_DISTANCE }
            .sortedWith(
                compareBy<Candidate> { it.distance }       // 第1優先: 編集距離（小）
                    .thenBy { it.lengthDiff }              // 第2優先: 文字数差（小）
                    .thenBy { it.index }                   // 第3優先: 元のリスト順（現状維持用）
            )
            .take(maxResults + 1) // 曖昧さ判定のために1件多めに取る

        if (filtered.isEmpty()) return emptyList()

        val results = filtered.take(maxResults).map { cand ->
            MatchResult(
                label = labels[cand.index],
                distance = cand.distance,
                isExactMatch = cand.distance == 0
            )
        }

        // 曖昧さの判定: 1位と2位の距離が同じ、かつ文字数差も同じなら「曖昧」とする
        if (filtered.size >= 2) {
            val first = filtered[0]
            val second = filtered[1]
            if (first.distance == second.distance && first.lengthDiff == second.lengthDiff) {
                // 1位の候補に曖昧フラグを立てる
                return listOf(results[0].copy(isAmbiguous = true)) + results.drop(1)
            }
        }

        return results
    }

    private data class Candidate(
        val index: Int,
        val distance: Int,
        val lengthDiff: Int
    )

    /**
     * 最もスコアの高い1件を返す。マッチなしは null。
     */
    fun findBest(ocrText: String): MatchResult? =
        findTopCandidates(ocrText, maxResults = 1).firstOrNull()

    // ──────────────────────────────────────────────────────────────────────
    // 文字列処理
    // ──────────────────────────────────────────────────────────────────────

    private fun normalize(text: String): String =
        text.uppercase().trim()
            .replace(" ", "")
            .replace("-", "") // ハイフンを除去して照合の耐性を上げる
            .replace("\u3000", "")

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
            '2' to 'Z', 'Z' to '2',
            '7' to 'T', 'T' to '7',
            '4' to 'A', 'A' to '4'
        )
        for (i in text.indices) {
            val sub = substitutions[text[i]]
            if (sub != null) {
                variants.add(text.substring(0, i) + sub + text.substring(i + 1))
            }
        }
        return variants
    }

    private fun editDistance(a: String, b: String): Int {
        val m = a.length; val n = b.length
        val dp = Array(m + 1) { IntArray(n + 1) }
        for (i in 0..m) dp[i][0] = i
        for (j in 0..n) dp[0][j] = j
        for (i in 1..m) {
            for (j in 1..n) {
                dp[i][j] = if (a[i - 1] == b[j - 1]) dp[i - 1][j - 1]
                            else 1 + minOf(dp[i - 1][j], dp[i][j - 1], dp[i - 1][j - 1])
            }
        }
        return dp[m][n]
    }

    // ──────────────────────────────────────────────────────────────────────
    // ファクトリ・定数
    // ──────────────────────────────────────────────────────────────────────

    companion object {
        private const val TAG = "LabelMatcher"
        const val MIN_OCR_LEN = 4
        const val MAX_EDIT_DISTANCE = 3
        const val LABELS_ASSET_PATH = "product_labels.txt"

        /**
         * 推奨コンストラクタ（suspend 関数）。
         * Room DB を優先し、空の場合は assets にフォールバックする。
         *
         *   val matcher = LabelMatcher.create(context)
         */
        suspend fun create(context: Context): LabelMatcher {
            val dbLabels = loadFromDb(context)
            return if (dbLabels.isNotEmpty()) {
                LabelMatcher(dbLabels, "Room DB (${dbLabels.size}件)")
            } else {
                Log.d(TAG, "DB が空のため assets にフォールバック")
                val assetLabels = loadFromAssets(context)
                LabelMatcher(assetLabels, "assets (${assetLabels.size}件)")
            }
        }

        /**
         * 後方互換コンストラクタ（同期版）。
         * コルーチンを使えない箇所での利用を想定。assets から読み込む。
         */
        operator fun invoke(context: Context): LabelMatcher {
            val labels = loadFromAssets(context)
            return LabelMatcher(labels, "assets fallback (${labels.size}件)")
        }

        /** Room DB から製品コードを全件取得 */
        private suspend fun loadFromDb(context: Context): List<String> {
            return try {
                AppDatabase.getDatabase(context)
                    .productLabelDao()
                    .getAll()
                    .map { it.code }
            } catch (e: Exception) {
                Log.e(TAG, "DB からのラベル読み込み失敗", e)
                emptyList()
            }
        }

        /** assets/product_labels.txt から読み込み（フォールバック用） */
        private fun loadFromAssets(context: Context): List<String> {
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
                Log.e(TAG, "assets からのラベル読み込み失敗: $LABELS_ASSET_PATH", e)
                emptyList()
            }
        }
    }
}

/**
 * ラベル照合結果
 */
data class MatchResult(
    val label: String,
    val distance: Int,
    val isExactMatch: Boolean,
    val isAmbiguous: Boolean = false // スコアが同点の候補が複数ある場合に true
)
