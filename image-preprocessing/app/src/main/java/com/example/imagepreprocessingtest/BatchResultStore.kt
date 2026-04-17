/**
 * BatchResultStore.kt
 *
 * 一括処理の結果を Activity 間で受け渡すためのシングルトン。
 * Intent には Bitmap を直接乗せられないため、メモリ上に保持する。
 * 一覧画面が閉じたあと clear() で解放する。
 */
package com.example.imagepreprocessingtest

import android.graphics.Bitmap

data class BatchItem(
    val filename: String,
    val processedBitmap: Bitmap,
    val elapsedMs: Long
)

object BatchResultStore {
    val items = mutableListOf<BatchItem>()

    fun set(map: Map<String, Bitmap>, timings: Map<String, Long>) {
        items.clear()
        for ((name, bmp) in map) {
            items.add(BatchItem(name, bmp, timings[name] ?: -1L))
        }
        items.sortBy { it.filename }
    }

    fun clear() {
        items.clear()
    }
}
