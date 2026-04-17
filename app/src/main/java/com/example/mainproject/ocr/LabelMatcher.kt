package com.example.mainproject.ocr

import android.content.Context
import android.util.Log

/**
 * 製品コードの正解ラベルリストを使って、OCR認識テキストを補正するクラス。
 *
 * 【使い方】
 *   val matcher = LabelMatcher(context)
 *
 *   // OCR結果に近い候補を最大3件取得
 *   val candidates: List<Pair<String, Int>> = matcher.findTopCandidates(ocrResult.text)
 *   // → [("B1Sb30N-7A", 0), ("B1Sb30N-7", 1), ...]
 *   //      ↑ラベル文字列    ↑編集距離（0=完全一致）
 *
 *   // 最も近い1件だけ取得
 *   val best: Pair<String, Int>? = matcher.findBest(ocrResult.text)
 *
 * 【必要な assets】
 *   product_labels.txt  正解製品コード一覧（1行1コード、ASCII のみ）
 *
 * 【処理フロー】
 *   1. assets から正解ラベル一覧を読み込む（起動時1回）
 *   2. OCR出力を正規化（大文字統一・スペース除去）
 *   3. OCR誤認識パターン（0↔O, 1↔I 等）を考慮した候補バリアントを生成
 *   4. 全ラベルとの Levenshtein 編集距離を計算し、上位 N 件を返す
 */
class LabelMatcher(context: Context) {

    /** 読み込んだ正解ラベルの一覧（元の表記を保持） */
    val labels: List<String>

    /** normalize() のキャッシュ（起動時に一括生成して毎回の呼び出しコストを削減） */
    private val normalizedLabels: List<String>

    init {
        labels = loadLabels(context)
        normalizedLabels = labels.map { normalize(it) }
        Log.d("LabelMatcher", "ラベル読み込み完了: ${labels.size}件")
    }

    // ──────────────────────────────────────────────────────────────────────
    // ラベル読み込み
    // ──────────────────────────────────────────────────────────────────────

    private fun loadLabels(context: Context): List<String> {
        return try {
            // ファイルは Shift-JIS の可能性があるが製品コード自体は ASCII のため
            // ISO-8859-1（バイトをそのまま読む）で取得し、ASCII 行のみ抽出する
            context.assets.open(LABELS_ASSET_PATH)
                .bufferedReader(Charsets.ISO_8859_1)
                .readLines()
                .map { it.trim() }
                .filter { line ->
                    line.isNotEmpty()
                        && line != "xxxxxxxxxx"           // 区切り行を除外
                        && line.all { it.code < 128 }     // ASCII のみ（日本語ヘッダーを除外）
                }
                .distinct()
        } catch (e: Exception) {
            Log.e("LabelMatcher", "ラベルファイル読み込み失敗: $LABELS_ASSET_PATH", e)
            emptyList()
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // 正規化（大文字統一・余白除去）
    // ──────────────────────────────────────────────────────────────────────

    private fun normalize(text: String): String =
        text.uppercase().trim().replace(" ", "").replace("\u3000", "")

    // ──────────────────────────────────────────────────────────────────────
    // OCR 誤認識を考慮したバリアント生成
    //
    // PaddleOCR でよく発生する文字混同:
    //   0 ↔ O  / 1 ↔ I,L  / 5 ↔ S  / 8 ↔ B  / 2 ↔ Z
    //   ハイフン - の見落とし → 編集距離で吸収
    // ──────────────────────────────────────────────────────────────────────

    private val substitutions: Map<Char, List<Char>> = mapOf(
        'O' to listOf('0'),
        '0' to listOf('O'),
        'I' to listOf('1', 'L'),
        'L' to listOf('1', 'I'),
        '1' to listOf('I', 'L'),
        'S' to listOf('5'),
        '5' to listOf('S'),
        'B' to listOf('8'),
        '8' to listOf('B'),
        'Z' to listOf('2'),
        '2' to listOf('Z'),
    )

    /**
     * 正規化済みテキストに対して、1文字ずつ OCR 誤認識置換を適用したバリアント集合を返す。
     * 組み合わせ爆発を防ぐため、1回の呼び出しで生成する置換は最大1文字分。
     */
    private fun generateVariants(text: String): Set<String> {
        val normalized = normalize(text)
        val variants = mutableSetOf(normalized)
        for (i in normalized.indices) {
            substitutions[normalized[i]]?.forEach { rep ->
                variants.add(normalized.substring(0, i) + rep + normalized.substring(i + 1))
            }
        }
        return variants
    }

    // ──────────────────────────────────────────────────────────────────────
    // Levenshtein 編集距離（早期打ち切り付き）
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Levenshtein 編集距離。[cap] を超えた時点でそれ以上の計算を打ち切る。
     * ラベル数が多い場合でも閾値超えのラベルを高速に棄却できる。
     */
    private fun editDistance(a: String, b: String, cap: Int = Int.MAX_VALUE): Int {
        val m = a.length; val n = b.length
        if (kotlin.math.abs(m - n) > cap) return cap + 1
        val dp = Array(m + 1) { IntArray(n + 1) }
        for (i in 0..m) dp[i][0] = i
        for (j in 0..n) dp[0][j] = j
        for (i in 1..m) {
            var rowMin = Int.MAX_VALUE
            for (j in 1..n) {
                dp[i][j] = if (a[i - 1] == b[j - 1]) dp[i - 1][j - 1]
                            else minOf(dp[i - 1][j] + 1, dp[i][j - 1] + 1, dp[i - 1][j - 1] + 1)
                if (dp[i][j] < rowMin) rowMin = dp[i][j]
            }
            if (rowMin > cap) return cap + 1
        }
        return dp[m][n]
    }

    // ──────────────────────────────────────────────────────────────────────
    // 公開 API
    // ──────────────────────────────────────────────────────────────────────

    /**
     * OCR 認識テキストに最も近い正解ラベルを上位 [topN] 件返す。
     *
     * 除外条件:
     *   - 正規化後の OCR テキスト長が [MIN_OCR_LEN] 未満 → 空リストを返す
     *   - 編集距離が [MAX_EDIT_DISTANCE] を超える候補 → 除外
     *   - 編集距離が OCR テキスト長以上（ほぼ全文字が違う）→ 除外
     *
     * @param ocrText         OcrEngine から返された認識テキスト（生テキストで渡してよい）
     * @param topN            返す候補数（デフォルト 3 件）
     * @param maxEditDistance 許容する最大編集距離（デフォルト 3）
     * @return (ラベル文字列, 編集距離) のリスト（距離昇順）。該当なしは空リスト。
     */
    fun findTopCandidates(
        ocrText: String,
        topN: Int = 3,
        maxEditDistance: Int = MAX_EDIT_DISTANCE
    ): List<Pair<String, Int>> {
        if (labels.isEmpty() || ocrText.isBlank()) return emptyList()

        val normOcr = normalize(ocrText)
        if (normOcr.length < MIN_OCR_LEN) return emptyList()

        // 上限 = min(maxEditDistance, テキスト長 - 1)
        // テキスト長以上の距離はほぼ全文字違いで意味がないため排除
        val distCap = minOf(maxEditDistance, normOcr.length - 1)
        val variants = generateVariants(ocrText)

        return labels.indices
            // 長さ差が distCap を超えるラベルはDP計算前に除外（計算量を最大70%削減）
            .filter { i -> kotlin.math.abs(normalizedLabels[i].length - normOcr.length) <= distCap }
            .map { i ->
                val minDist = variants.minOf { editDistance(it, normalizedLabels[i], distCap) }
                labels[i] to minDist
            }
            .filter { (_, dist) -> dist <= distCap }
            .sortedWith(compareBy({ it.second }, { it.first }))
            .take(topN)
    }

    /**
     * 最も距離の近い 1 件だけ返す簡易版。
     * 完全一致の場合は `second == 0` で判定できる。
     */
    fun findBest(ocrText: String): Pair<String, Int>? =
        findTopCandidates(ocrText, topN = 1).firstOrNull()

    companion object {
        /** これ未満の文字数の OCR 出力はラベル照合をスキップ */
        const val MIN_OCR_LEN = 4

        /** この値を超える編集距離の候補は除外（「近い」とは言えない） */
        const val MAX_EDIT_DISTANCE = 3

        /** assets 内の正解ラベルファイルパス */
        const val LABELS_ASSET_PATH = "product_labels.txt"
    }
}
