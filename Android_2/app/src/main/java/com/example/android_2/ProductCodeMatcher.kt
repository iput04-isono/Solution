package com.example.android_2

import android.content.Context
import android.util.Log
import kotlin.math.min

class ProductCodeMatcher(context: Context) {

    data class MatchedCode(
        val originalCode: String,
        val normalizedCode: String,
        val similarity: Double,
        val distance: Int
    )

    private data class ProductCodeEntry(
        val originalCode: String,
        val normalizedCode: String
    )

    private val productCodes: List<ProductCodeEntry>
    private val similarCharMap: Map<Char, Char>

    init {
        similarCharMap = loadSimilarCharMap(context)

        productCodes = try {
            context.assets.open("ProductCodes.txt")
                .bufferedReader(Charsets.UTF_8)
                .useLines { lines ->
                    lines.map { it.trim() }
                        .filter { it.isNotBlank() }
                        .distinct()
                        .map {
                            ProductCodeEntry(it, normalizeCode(it))
                        }
                        .toList()
                }
        } catch (e: Exception) {
            Log.e("ProductCodeMatcher", "製品コード一覧読み込み失敗", e)
            emptyList()
        }

        Log.d("ProductCodeMatcher", "productCodes size = ${productCodes.size}")
        Log.d("ProductCodeMatcher", "similarCharMap size = ${similarCharMap.size}")
    }

    fun getProductCodeCount(): Int = productCodes.size

    fun findTopCandidates(input: String, topK: Int = 3): List<MatchedCode> {
        val normalizedInput = normalizeCode(input)
        if (normalizedInput.isBlank()) return emptyList()
        if (productCodes.isEmpty()) return emptyList()

        return productCodes
            .map { entry ->
                val distance = levenshtein(normalizedInput, entry.normalizedCode)
                val maxLen = maxOf(normalizedInput.length, entry.normalizedCode.length).coerceAtLeast(1)
                val similarity = 1.0 - (distance.toDouble() / maxLen.toDouble())

                MatchedCode(
                    originalCode = entry.originalCode,
                    normalizedCode = entry.normalizedCode,
                    similarity = similarity,
                    distance = distance
                )
            }
            .sortedWith(
                compareByDescending<MatchedCode> { it.similarity }
                    .thenBy { it.distance }
            )
            .take(topK)
    }

    fun normalizeCode(text: String): String {
        val cleaned = text.trim()
            .replace('—', '-')
            .replace('―', '-')
            .replace('‐', '-')
            .replace('−', '-')
            .replace('ー', '-')
            .replace('／', '/')
            .replace('　', ' ')
            .replace(" ", "")

        val mapped = buildString {
            for (ch in cleaned) {
                append(similarCharMap[ch] ?: ch)
            }
        }

        return mapped.filter { it.isLetterOrDigit() || it == '-' || it == '/' }
    }

    private fun loadSimilarCharMap(context: Context): Map<Char, Char> {
        return try {
            context.assets.open("SimilarChars.txt")
                .bufferedReader(Charsets.UTF_8)
                .useLines { lines ->
                    val map = mutableMapOf<Char, Char>()

                    lines.map { it.trim() }
                        .filter { it.isNotBlank() }
                        .filter { !it.startsWith("#") }
                        .forEach { group ->
                            val representative = group.first()
                            for (ch in group) {
                                map[ch] = representative
                            }
                        }

                    map
                }
        } catch (e: Exception) {
            Log.e("ProductCodeMatcher", "類似文字リスト読み込み失敗", e)
            emptyMap()
        }
    }

    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length

        val prev = IntArray(b.length + 1) { it }
        val curr = IntArray(b.length + 1)

        for (i in a.indices) {
            curr[0] = i + 1
            for (j in b.indices) {
                val cost = if (a[i] == b[j]) 0 else 1
                curr[j + 1] = min(
                    min(curr[j] + 1, prev[j + 1] + 1),
                    prev[j] + cost
                )
            }
            for (k in prev.indices) prev[k] = curr[k]
        }
        return prev[b.length]
    }
}