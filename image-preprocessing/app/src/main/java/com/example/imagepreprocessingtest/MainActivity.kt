/**
 * MainActivity.kt
 *
 * 鉄骨文字認識アプリ — 画像前処理検証デモ
 *
 * 担当: 坂井壱謙（画像処理班・前処理担当）
 *
 * 概要:
 *   このアクティビティは「前処理デモ」のメイン画面を管理する。
 *   assets/images/ に格納されたテスト用鉄骨画像を読み込み、
 *   ImagePreprocessor クラスを通じて各種前処理を実行して結果を表示する。
 *
 * 主な機能:
 *   - 前処理モード選択（標準 / 背景正規化 / 歪み補正 / 全改善）
 *   - 1枚単位の前処理実行（通常 / 各ステップ時間計測）
 *   - 全画像一括処理 → 処理時間サマリー表示
 *   - 前処理済み画像のギャラリー保存（1枚 / 全枚）
 */
package com.example.imagepreprocessingtest

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.imagepreprocessingtest.databinding.ActivityMainBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opencv.android.OpenCVLoader

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var preprocessor: ImagePreprocessor

    /** assets/images/ から読み込んだ画像ファイル名のリスト */
    private var currentImageIndex = 0
    private var currentBitmap: Bitmap? = null

    /** 直前に実行した前処理の結果（保存・OCR用に保持） */
    private var lastProcessedBitmap: Bitmap? = null
    private val imageFiles = mutableListOf<String>()
    private var isOpenCVInitialized = false

    /** 一括処理の結果キャッシュ（ファイル名 → 前処理済みBitmap） */
    private val batchResults = mutableMapOf<String, Bitmap>()

    // ----------------------------------------------------------------
    // ライフサイクル
    // ----------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        initOpenCV()      // OpenCV ネイティブライブラリの初期化
        loadImageList()   // assets/images/ のファイル一覧を取得

        if (imageFiles.isNotEmpty()) {
            loadCurrentImage()
        }

        setupModeSpinner() // 前処理モード選択ドロップダウン
        setupButtons()     // 各ボタンのクリックリスナー
        setupSpinner()     // 画像選択ドロップダウン
    }

    // ----------------------------------------------------------------
    // 初期化
    // ----------------------------------------------------------------

    /**
     * OpenCV の初期化。
     * initLocal() はアプリ内に同梱した .so ライブラリを直接ロードするため、
     * OpenCV Manager アプリのインストールが不要。
     */
    private fun initOpenCV() {
        if (OpenCVLoader.initLocal()) {
            Log.i(TAG, "OpenCV loaded successfully")
            isOpenCVInitialized = true
            preprocessor = ImagePreprocessor()
            binding.textViewStatus.text = "OpenCV: ✅ 初期化完了"
        } else {
            Log.e(TAG, "OpenCV initialization failed")
            isOpenCVInitialized = false
            binding.textViewStatus.text = "OpenCV: ❌ 初期化失敗"
            Toast.makeText(this, "OpenCVの初期化に失敗しました", Toast.LENGTH_LONG).show()
        }
    }

    // ----------------------------------------------------------------
    // UI セットアップ
    // ----------------------------------------------------------------

    /**
     * 前処理モード選択 Spinner のセットアップ。
     * ImagePreprocessor.Mode の各エントリをリストとして表示し、
     * 選択時に preprocessor.mode を更新する。
     */
    private fun setupModeSpinner() {
        val modes = ImagePreprocessor.Mode.entries
        val labels = modes.map { it.label }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerMode.adapter = adapter
        binding.textViewModeDesc.text = modes[0].description

        binding.spinnerMode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selected = modes[position]
                preprocessor.mode = selected
                binding.textViewModeDesc.text = selected.description
                // モード変更時は前処理結果をリセットして再処理を促す
                lastProcessedBitmap = null
                binding.imageViewProcessed.setImageBitmap(null)
                binding.textViewProcessingTime.text = "処理時間: -"
                binding.textViewDetailedTiming.text = ""
                binding.buttonSaveImage.isEnabled = false
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    /** 各ボタンにクリックリスナーを設定する */
    private fun setupButtons() {
        binding.buttonProcess.setOnClickListener {
            if (!isOpenCVInitialized) {
                Toast.makeText(this, "OpenCVが初期化されていません", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            processCurrentImage()
        }

        binding.buttonProcessDetailed.setOnClickListener {
            if (!isOpenCVInitialized) {
                Toast.makeText(this, "OpenCVが初期化されていません", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            processCurrentImageDetailed()
        }

        // 前の画像に移動
        binding.buttonNext.setOnClickListener {
            if (currentImageIndex < imageFiles.size - 1) {
                currentImageIndex++
                binding.spinnerImages.setSelection(currentImageIndex)
                loadCurrentImage()
            }
        }

        // 次の画像に移動
        binding.buttonPrev.setOnClickListener {
            if (currentImageIndex > 0) {
                currentImageIndex--
                binding.spinnerImages.setSelection(currentImageIndex)
                loadCurrentImage()
            }
        }

        // 前処理済み画像をギャラリーに保存（1枚）
        binding.buttonSaveImage.setOnClickListener {
            val bmp = lastProcessedBitmap
            if (bmp == null) {
                Toast.makeText(this, "先に前処理を実行してください", Toast.LENGTH_SHORT).show()
            } else {
                saveImageToGallery(imageFiles.getOrElse(currentImageIndex) { "image" }, bmp)
            }
        }

        // 全画像を一括前処理
        binding.buttonProcessAll.setOnClickListener {
            if (!isOpenCVInitialized) {
                Toast.makeText(this, "OpenCVが初期化されていません", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (imageFiles.isEmpty()) {
                Toast.makeText(this, "画像が読み込まれていません", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            processAllImages()
        }

        // 全画像の前処理済み結果をギャラリーに一括保存
        binding.buttonSaveAll.setOnClickListener {
            if (batchResults.isEmpty()) {
                Toast.makeText(this, "先に全画像一括処理を実行してください", Toast.LENGTH_SHORT).show()
            } else {
                saveAllImages()
            }
        }
    }

    /** 画像選択 Spinner のセットアップ */
    private fun setupSpinner() {
        binding.spinnerImages.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (currentImageIndex != position) {
                    currentImageIndex = position
                    loadCurrentImage()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    // ----------------------------------------------------------------
    // 画像ロード
    // ----------------------------------------------------------------

    /**
     * assets/images/ 内の画像ファイル一覧を取得し、Spinner に設定する。
     * 対応フォーマット: jpg / jpeg / png
     */
    private fun loadImageList() {
        try {
            val assets = assets.list("images")
            if (assets != null) {
                imageFiles.clear()
                imageFiles.addAll(assets.filter {
                    it.endsWith(".jpg", ignoreCase = true) ||
                    it.endsWith(".jpeg", ignoreCase = true) ||
                    it.endsWith(".png", ignoreCase = true)
                }.sorted())

                val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, imageFiles)
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                binding.spinnerImages.adapter = adapter

                binding.textViewImageCount.text = "画像数: ${imageFiles.size}枚"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load image list", e)
            Toast.makeText(this, "画像リストの読み込みに失敗: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * currentImageIndex に対応する画像を assets から読み込み、
     * 元画像 ImageView に表示する。
     * 前処理結果と保存ボタンはリセットされる。
     */
    private fun loadCurrentImage() {
        if (imageFiles.isEmpty()) return

        val filename = imageFiles[currentImageIndex]
        binding.textViewImageName.text = "画像: $filename"

        CoroutineScope(Dispatchers.Main).launch {
            val bitmap = withContext(Dispatchers.IO) {
                try {
                    assets.open("images/$filename").use { BitmapFactory.decodeStream(it) }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to load image: $filename", e)
                    null
                }
            }

            if (bitmap != null) {
                currentBitmap = bitmap
                lastProcessedBitmap = null
                binding.imageViewOriginal.setImageBitmap(bitmap)
                binding.imageViewProcessed.setImageBitmap(null)
                binding.textViewProcessingTime.text = "処理時間: -"
                binding.textViewImageSize.text = "サイズ: ${bitmap.width} x ${bitmap.height}"
                binding.textViewDetailedTiming.text = ""
                binding.buttonSaveImage.isEnabled = false
            } else {
                Toast.makeText(this@MainActivity, "画像の読み込みに失敗: $filename", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ----------------------------------------------------------------
    // 前処理（1枚）
    // ----------------------------------------------------------------

    /**
     * 現在の画像に対して前処理を実行し、処理時間とともに結果を表示する。
     * 処理は Dispatchers.Default（バックグラウンドスレッド）で行い、
     * UI 更新のみ Main スレッドで行う。
     */
    private fun processCurrentImage() {
        val bitmap = currentBitmap ?: return

        CoroutineScope(Dispatchers.Main).launch {
            setProcessingState(true)

            val (processedBitmap, processingTime) = withContext(Dispatchers.Default) {
                preprocessor.preprocessWithTiming(bitmap)
            }

            lastProcessedBitmap = processedBitmap
            binding.imageViewProcessed.setImageBitmap(processedBitmap)
            binding.textViewProcessingTime.text = "処理時間: ${processingTime}ms"
            binding.textViewDetailedTiming.text = ""
            binding.buttonSaveImage.isEnabled = true

            setProcessingState(false)
            showResultToast(processingTime)
        }
    }

    /**
     * 各ステップの処理時間を個別に計測しながら前処理を実行する。
     * 詳細タイミングは textViewDetailedTiming に一覧表示される。
     */
    private fun processCurrentImageDetailed() {
        val bitmap = currentBitmap ?: return

        CoroutineScope(Dispatchers.Main).launch {
            setProcessingState(true)

            val result = withContext(Dispatchers.Default) {
                preprocessor.preprocessWithDetailedTiming(bitmap)
            }

            lastProcessedBitmap = result.bitmap
            binding.imageViewProcessed.setImageBitmap(result.bitmap)
            binding.textViewProcessingTime.text = "合計: ${result.totalTimeMs}ms"

            // 各ステップ名と処理時間を整形して表示
            val detailText = result.stepTimings.entries.joinToString("\n") { (step, time) ->
                "  $step: ${time}ms"
            }
            binding.textViewDetailedTiming.text = detailText
            binding.buttonSaveImage.isEnabled = true

            setProcessingState(false)
            showResultToast(result.totalTimeMs)
        }
    }

    // ----------------------------------------------------------------
    // 一括処理
    // ----------------------------------------------------------------

    /**
     * 全画像を順番に前処理し、進捗とサマリーを表示する。
     * 処理結果は batchResults にキャッシュされ、後で一括保存できる。
     */
    private fun processAllImages() {
        val total = imageFiles.size
        batchResults.clear()

        setBatchState(true, 0, total)

        CoroutineScope(Dispatchers.Main).launch {
            val timings = mutableListOf<Pair<String, Long>>()

            for ((index, filename) in imageFiles.withIndex()) {
                // プログレスをリアルタイム更新
                binding.textViewBatchProgress.text =
                    "処理中: ${index + 1} / $total  [$filename]"
                binding.progressBarBatch.progress =
                    ((index.toFloat() / total) * 100).toInt()

                val (processedBitmap, elapsedMs) = withContext(Dispatchers.Default) {
                    try {
                        val stream = assets.open("images/$filename")
                        val bmp    = BitmapFactory.decodeStream(stream)
                        stream.close()
                        preprocessor.preprocessWithTiming(bmp)
                    } catch (e: Exception) {
                        Log.e(TAG, "batch: failed to process $filename", e)
                        Pair(null, -1L)
                    }
                }

                if (processedBitmap != null) {
                    batchResults[filename] = processedBitmap
                    timings.add(Pair(filename, elapsedMs))
                } else {
                    timings.add(Pair(filename, -1L))
                }
            }

            binding.progressBarBatch.progress = 100
            showBatchSummary(timings)
            setBatchState(false, total, total)
        }
    }

    /**
     * 一括処理完了後のサマリーを textViewBatchResult に表示する。
     * 各画像の処理時間と目標（3秒以内）の達成状況を一覧化する。
     */
    private fun showBatchSummary(timings: List<Pair<String, Long>>) {
        val total   = timings.size
        val ok      = timings.count { it.second in 0..3000 }
        val failed  = timings.count { it.second < 0 }
        val validMs = timings.filter { it.second >= 0 }.map { it.second }
        val avgMs   = if (validMs.isNotEmpty()) validMs.average().toLong() else 0L
        val maxMs   = validMs.maxOrNull() ?: 0L
        val minMs   = validMs.minOrNull() ?: 0L

        val sb = StringBuilder()
        sb.appendLine("━━━ 一括処理結果 ━━━")
        sb.appendLine("処理数: $total 枚  ✅ ${ok}枚 / ⚠️ ${total - ok - failed}枚 / ❌ ${failed}枚")
        sb.appendLine("平均: ${avgMs}ms  最大: ${maxMs}ms  最小: ${minMs}ms")
        sb.appendLine()
        sb.appendLine("ファイル名                    時間")
        sb.appendLine("─────────────────────────────────")

        for ((name, ms) in timings) {
            val label = when {
                ms < 0     -> "❌ エラー"
                ms <= 3000 -> "✅ ${ms}ms"
                else       -> "⚠️ ${ms}ms"
            }
            sb.appendLine("${name.take(28).padEnd(28)}  $label")
        }

        binding.textViewBatchResult.text = sb.toString()
        binding.textViewBatchResult.visibility = View.VISIBLE
        binding.textViewBatchProgress.text =
            "完了: $total 枚処理済み（平均 ${avgMs}ms）"
    }

    /** 一括処理中のボタン状態とプログレス表示を制御する */
    private fun setBatchState(isRunning: Boolean, current: Int, total: Int) {
        binding.buttonProcessAll.isEnabled      = !isRunning
        binding.buttonProcess.isEnabled         = !isRunning
        binding.buttonProcessDetailed.isEnabled = !isRunning
        binding.buttonSaveAll.isEnabled         = !isRunning && batchResults.isNotEmpty()
        binding.progressBarBatch.visibility     = if (isRunning || current == total) View.VISIBLE else View.GONE
        binding.textViewBatchProgress.visibility = View.VISIBLE
        binding.buttonProcessAll.text           = if (isRunning) "処理中..." else "全画像 一括処理"

        if (!isRunning && batchResults.isNotEmpty()) {
            binding.buttonSaveAll.isEnabled = true
        }
    }

    // ----------------------------------------------------------------
    // 画像保存
    // ----------------------------------------------------------------

    /** 全画像の前処理済み結果をギャラリーに一括保存する */
    private fun saveAllImages() {
        val total = batchResults.size
        binding.buttonSaveAll.isEnabled = false
        binding.buttonSaveAll.text = "保存中..."

        CoroutineScope(Dispatchers.Main).launch {
            var saved = 0
            withContext(Dispatchers.IO) {
                for ((filename, bitmap) in batchResults) {
                    try {
                        saveImageToGallery(filename, bitmap)
                        saved++
                    } catch (e: Exception) {
                        Log.e(TAG, "saveAll: failed for $filename", e)
                    }
                }
            }
            binding.buttonSaveAll.isEnabled = true
            binding.buttonSaveAll.text = "全画像 一括保存"
            Toast.makeText(this@MainActivity, "保存完了: $saved / $total 枚", Toast.LENGTH_LONG).show()
        }
    }

    // ----------------------------------------------------------------
    // UI ヘルパー
    // ----------------------------------------------------------------

    /** 前処理実行中のボタン有効/無効を切り替える */
    private fun setProcessingState(isProcessing: Boolean) {
        binding.buttonProcess.isEnabled         = !isProcessing
        binding.buttonProcessDetailed.isEnabled = !isProcessing
        binding.buttonSaveImage.isEnabled       = !isProcessing && lastProcessedBitmap != null
        binding.buttonProcess.text              = if (isProcessing) "処理中..." else "前処理実行"
        binding.buttonProcessDetailed.text      = if (isProcessing) "処理中..." else "詳細計測"
    }

    /** 処理時間が目標（3秒）以内かどうかをトーストで通知する */
    private fun showResultToast(processingTime: Long) {
        val message = if (processingTime <= 3000) {
            "✅ 目標達成: ${processingTime}ms (< 3秒)"
        } else {
            "⚠️ 目標超過: ${processingTime}ms (> 3秒)"
        }
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    /**
     * 前処理済み画像をデバイスのギャラリー（Pictures/SteelOCR/）に PNG として保存する。
     * Android 10 以降は MediaStore API を使用し、それ以前は直接ファイル書き込みを行う。
     *
     * @param sourceFilename 元のファイル名（保存名に使用）
     * @param bitmap         保存する前処理済み Bitmap
     */
    private fun saveImageToGallery(sourceFilename: String, bitmap: Bitmap) {
        val filename = "preprocess_${sourceFilename.substringBeforeLast(".")}_${System.currentTimeMillis()}.png"

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+ (API 29+): MediaStore API 経由で保存
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/SteelOCR")
                }
                val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                uri?.let {
                    contentResolver.openOutputStream(it)?.use { os ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, os)
                    }
                    Toast.makeText(this, "保存しました: $filename", Toast.LENGTH_SHORT).show()
                } ?: Toast.makeText(this, "保存に失敗しました", Toast.LENGTH_SHORT).show()
            } else {
                // Android 9 以前: 直接ファイルシステムに書き込み
                val dir = java.io.File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    "SteelOCR"
                )
                if (!dir.exists()) dir.mkdirs()
                java.io.File(dir, filename).outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                Toast.makeText(this, "保存しました: $filename", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "saveImageToGallery failed", e)
            Toast.makeText(this, "保存エラー: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
