/**
 * BatchResultsActivity.kt
 *
 * 一括処理済み画像をグリッド（2列）で一覧表示する画面。
 * BatchResultStore から結果を読み込み、RecyclerView に表示する。
 * 「全保存」ボタンでギャラリーへの一括保存も可能。
 */
package com.example.imagepreprocessingtest

import android.content.ContentValues
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BatchResultsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_batch_results)

        val items   = BatchResultStore.items
        val summary = findViewById<TextView>(R.id.textBatchSummary)
        val recycler = findViewById<RecyclerView>(R.id.recyclerViewBatch)
        val btnSave  = findViewById<Button>(R.id.buttonBatchSaveAll)

        val avgMs = if (items.isNotEmpty())
            items.filter { it.elapsedMs > 0 }.map { it.elapsedMs }.average().toLong()
        else 0L
        summary.text = "${items.size} 枚  平均 ${avgMs}ms / 枚"

        // 2列グリッドで表示
        recycler.layoutManager = GridLayoutManager(this, 2)
        recycler.adapter = BatchGridAdapter(items)

        btnSave.setOnClickListener { saveAll(items) }
    }

    override fun onDestroy() {
        super.onDestroy()
        BatchResultStore.clear()
    }

    private fun saveAll(items: List<BatchItem>) {
        CoroutineScope(Dispatchers.Main).launch {
            var saved = 0
            withContext(Dispatchers.IO) {
                for (item in items) {
                    try {
                        saveToGallery(item.filename, item.processedBitmap)
                        saved++
                    } catch (e: Exception) {
                        Log.e("BatchResults", "save failed: ${item.filename}", e)
                    }
                }
            }
            Toast.makeText(
                this@BatchResultsActivity,
                "保存完了: $saved / ${items.size} 枚",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun saveToGallery(sourceFilename: String, bitmap: Bitmap) {
        val outName = "preprocess_${sourceFilename.substringBeforeLast(".")}_${System.currentTimeMillis()}.png"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, outName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + "/SteelOCR")
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            uri?.let {
                contentResolver.openOutputStream(it)?.use { os ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, os)
                }
            }
        } else {
            val dir = java.io.File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                "SteelOCR"
            )
            if (!dir.exists()) dir.mkdirs()
            java.io.File(dir, outName).outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }
}

// ──────────────────────────────────────────────────────────────────────
// グリッド用アダプター
// ──────────────────────────────────────────────────────────────────────

class BatchGridAdapter(
    private val items: List<BatchItem>
) : RecyclerView.Adapter<BatchGridAdapter.VH>() {

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val image:    ImageView = view.findViewById(R.id.imageBatchThumb)
        val filename: TextView  = view.findViewById(R.id.textBatchFilename)
        val time:     TextView  = view.findViewById(R.id.textBatchTime)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_batch_result, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.image.setImageBitmap(item.processedBitmap)
        holder.filename.text = item.filename
        holder.time.text = if (item.elapsedMs >= 0) "${item.elapsedMs}ms" else "エラー"
    }

    override fun getItemCount() = items.size
}
