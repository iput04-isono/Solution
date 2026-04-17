/**
 * CompareResultsActivity.kt
 *
 * 前処理条件 × OCR 比較実行画面。
 * MainActivity から "比較する画像のファイル名" を受け取り、
 * 全20条件 × OCR の結果を RecyclerView に表示する。
 *
 * 処理フロー:
 *   1. assets から選択画像を読み込む
 *   2. 全20条件を順番に前処理 → MLKit OCR 実行
 *   3. 結果をリアルタイムで RecyclerView に反映
 *   4. 「精度順ソート」ボタンで結果を並べ替え
 */
package com.example.imagepreprocessingtest

import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.*

class CompareResultsActivity : AppCompatActivity() {

    companion object {
        /** MainActivity から渡す画像ファイル名のキー */
        const val EXTRA_IMAGE_FILENAME = "image_filename"
    }

    private lateinit var preprocessor: ImagePreprocessor
    private lateinit var adapter: CompareResultAdapter

    private lateinit var progressBar: ProgressBar
    private lateinit var textProgress: TextView
    private lateinit var buttonStart: Button
    private lateinit var buttonSort: Button
    private lateinit var recyclerView: RecyclerView

    private val results = mutableListOf<CompareResult>()
    private var isSortedByConf = false

    /** 現在のフィルター: null=全条件, true=白黒あり, false=グレーのみ */
    private var filterBinarized: Boolean? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_compare_results)

        val filename = intent.getStringExtra(EXTRA_IMAGE_FILENAME) ?: ""

        progressBar  = findViewById(R.id.progressBarCompare)
        textProgress = findViewById(R.id.textCompareProgress)
        buttonStart  = findViewById(R.id.buttonStartCompare)
        buttonSort   = findViewById(R.id.buttonSortByConfidence)
        recyclerView = findViewById(R.id.recyclerViewResults)

        findViewById<TextView>(R.id.textCompareImageName).text = "画像: $filename"

        preprocessor = ImagePreprocessor()

        // RecyclerView のセットアップ
        adapter = CompareResultAdapter(emptyList())
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        // 初期リスト（未実行状態）を生成
        val initialItems = preprocessor.compareConditions.map { cond ->
            CompareResult(
                condition   = cond,
                bitmap      = null,
                ocrText     = "（待機中）",
                confidence  = 0f,
                ocrDetail   = "",
                preprocessMs = 0L,
                ocrMs        = 0L
            )
        }
        results.addAll(initialItems)
        adapter.updateItems(results.toList())

        buttonStart.setOnClickListener {
            if (filename.isEmpty()) {
                Toast.makeText(this, "画像が指定されていません", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            startCompare(filename)
        }

        buttonSort.setOnClickListener {
            if (isSortedByConf) {
                adapter.sortByConditionOrder()
                buttonSort.text = "↓ 精度順"
                isSortedByConf = false
            } else {
                adapter.sortByConfidence()
                buttonSort.text = "↑ 条件順"
                isSortedByConf = true
            }
        }

        // フィルターボタン
        val btnFilterAll    = findViewById<Button>(R.id.buttonFilterAll)
        val btnFilterBinary = findViewById<Button>(R.id.buttonFilterBinary)
        val btnFilterGray   = findViewById<Button>(R.id.buttonFilterGray)

        btnFilterAll.setOnClickListener {
            filterBinarized = null
            applyFilter()
            updateFilterButtonAlpha(btnFilterAll, btnFilterBinary, btnFilterGray, 0)
        }
        btnFilterBinary.setOnClickListener {
            filterBinarized = true
            applyFilter()
            updateFilterButtonAlpha(btnFilterAll, btnFilterBinary, btnFilterGray, 1)
        }
        btnFilterGray.setOnClickListener {
            filterBinarized = false
            applyFilter()
            updateFilterButtonAlpha(btnFilterAll, btnFilterBinary, btnFilterGray, 2)
        }
    }

    /** フィルター適用：現在の filterBinarized に基づいてリストを絞り込む */
    private fun applyFilter() {
        val filtered = when (filterBinarized) {
            true  -> results.filter { it.condition.binarized }
            false -> results.filter { !it.condition.binarized }
            null  -> results.toList()
        }
        adapter.updateItems(filtered)
        textProgress.text = "表示: ${filtered.size} 件" +
            when (filterBinarized) {
                true  -> "（白黒あり）"
                false -> "（グレーのみ）"
                null  -> "（全条件）"
            }
    }

    /** 選択中フィルターボタンを強調表示する */
    private fun updateFilterButtonAlpha(
        btnAll: Button, btnBin: Button, btnGray: Button, selected: Int
    ) {
        btnAll.alpha  = if (selected == 0) 1.0f else 0.45f
        btnBin.alpha  = if (selected == 1) 1.0f else 0.45f
        btnGray.alpha = if (selected == 2) 1.0f else 0.45f
    }

    /**
     * 全条件を順番に実行する。
     * 各条件の前処理 + OCR が完了するたびに UI を更新する。
     */
    private fun startCompare(filename: String) {
        buttonStart.isEnabled = false
        buttonSort.isEnabled  = false
        buttonStart.text      = "実行中..."

        val conditions  = preprocessor.compareConditions
        val total       = conditions.size
        progressBar.max = total
        progressBar.progress = 0

        CoroutineScope(Dispatchers.Main).launch {
            // assets から元画像を読み込む
            val sourceBitmap = withContext(Dispatchers.IO) {
                try {
                    assets.open("images/$filename").use { BitmapFactory.decodeStream(it) }
                } catch (e: Exception) {
                    null
                }
            }

            if (sourceBitmap == null) {
                Toast.makeText(
                    this@CompareResultsActivity,
                    "画像の読み込みに失敗: $filename", Toast.LENGTH_LONG
                ).show()
                buttonStart.isEnabled = true
                buttonStart.text = "▶ 比較開始"
                return@launch
            }

            // 各条件を順番に処理
            for ((index, cond) in conditions.withIndex()) {
                textProgress.text = "${index + 1} / $total  実行中: ${cond.name}"
                progressBar.progress = index

                val result = withContext(Dispatchers.Default) {
                    // ① 前処理
                    val (processedBitmap, preprocessMs) = preprocessor.applyCondition(
                        sourceBitmap, cond.id
                    )

                    // ② MLKit OCR
                    var ocrText     = "（エラー）"
                    var confidence  = 0f
                    var ocrDetail   = ""
                    var ocrMs       = 0L
                    try {
                        val start = System.currentTimeMillis()
                        val ocrResult = OcrEngine.recognize(processedBitmap)
                        ocrMs      = System.currentTimeMillis() - start
                        ocrText    = ocrResult.text
                        confidence = ocrResult.confidence
                        ocrDetail  = ocrResult.rawBlocks
                    } catch (e: Exception) {
                        ocrText = "OCRエラー: ${e.message}"
                    }

                    CompareResult(
                        condition    = cond,
                        bitmap       = processedBitmap,
                        ocrText      = ocrText,
                        confidence   = confidence,
                        ocrDetail    = ocrDetail,
                        preprocessMs = preprocessMs,
                        ocrMs        = ocrMs
                    )
                }

                // UI スレッドでリスト更新
                results[index] = result
                adapter.updateItems(results.toList())
                progressBar.progress = index + 1
            }

            // 完了後にフィルターを再適用して表示を整える
            applyFilter()
            val successCount = results.count { it.confidence >= 0.60f }
            textProgress.text =
                "完了: $total 条件  精度60%以上: $successCount 件"
            buttonStart.isEnabled = true
            buttonStart.text      = "▶ 再実行"
            buttonSort.isEnabled  = true

            Toast.makeText(
                this@CompareResultsActivity,
                "比較完了。精度順ソートで最良条件を確認できます",
                Toast.LENGTH_LONG
            ).show()
        }
    }
}
