package com.example.mainproject

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.example.mainproject.data.db.AppDatabase
import com.example.mainproject.data.repository.RegistrationRepository
import com.example.mainproject.ocr.ImagePreprocessor
import com.example.mainproject.ocr.OcrEngine
import com.example.mainproject.ocr.OcrResult
import com.example.mainproject.worker.SyncWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var imagePreview: ImageView
    private lateinit var ocrResultText: TextView
    private lateinit var saveButton: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var divisionGroup: RadioGroup

    private var ocrResults: List<OcrResult> = emptyList()
    private var ocrEngine: OcrEngine? = null
    private val preprocessor = ImagePreprocessor()

    // フル解像度カメラ撮影用 URI
    private var photoUri: Uri? = null

    // フル解像度カメラ（TakePicture = JPEG保存してURIで受け取る）
    private val takePicture = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        if (success) {
            photoUri?.let { uri -> runOcrFromUri(uri) }
        }
    }

    // ギャラリー選択
    private val pickImage = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let { runOcrFromUri(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        imagePreview  = findViewById(R.id.imagePreview)
        ocrResultText = findViewById(R.id.ocrResultText)
        saveButton    = findViewById(R.id.saveButton)
        progressBar   = findViewById(R.id.progressBar)
        divisionGroup = findViewById(R.id.divisionGroup)

        // OCRエンジンをバックグラウンドで初期化
        lifecycleScope.launch(Dispatchers.IO) {
            ocrEngine = OcrEngine(applicationContext)
        }

        // 定期同期スケジュール
        SyncWorker.schedule(this)

        // カメラボタン（フル解像度）
        findViewById<Button>(R.id.cameraButton).setOnClickListener {
            val photoFile = File(cacheDir, "camera_images/photo_${System.currentTimeMillis()}.jpg")
                .also { it.parentFile?.mkdirs() }
            val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", photoFile)
            photoUri = uri
            takePicture.launch(uri)
        }

        // ギャラリーボタン
        findViewById<Button>(R.id.galleryButton).setOnClickListener {
            pickImage.launch("image/*")
        }

        // 保存ボタン
        saveButton.setOnClickListener { saveToDb() }

        // 同期ボタン
        findViewById<Button>(R.id.syncButton).setOnClickListener {
            val req = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            WorkManager.getInstance(this).enqueue(req)
            Toast.makeText(this, "同期を開始しました", Toast.LENGTH_SHORT).show()
        }
    }

    // ──────────────────────────────────────────────
    // URI → Bitmap → OCR
    // ──────────────────────────────────────────────

    private fun runOcrFromUri(uri: Uri) {
        val bitmap = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                ImageDecoder.decodeBitmap(
                    ImageDecoder.createSource(contentResolver, uri)
                ) { decoder, _, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
            } else {
                @Suppress("DEPRECATION")
                MediaStore.Images.Media.getBitmap(contentResolver, uri)
            }
        } catch (e: Exception) {
            Toast.makeText(this, "画像の読み込みに失敗しました", Toast.LENGTH_SHORT).show()
            return
        }
        runOcr(bitmap)
    }

    // ──────────────────────────────────────────────
    // OCR処理
    // ──────────────────────────────────────────────

    private fun runOcr(bitmap: Bitmap) {
        saveButton.visibility  = View.GONE
        ocrResultText.text     = "解析中..."
        progressBar.visibility = View.VISIBLE
        imagePreview.setImageBitmap(bitmap)

        lifecycleScope.launch {
            val results = withContext(Dispatchers.Default) {
                val engine = ocrEngine ?: return@withContext emptyList<OcrResult>()
                val processed = preprocessor.preprocess(bitmap)
                engine.runOcr(processed)
            }
            progressBar.visibility = View.GONE
            ocrResults = results

            if (results.isEmpty()) {
                ocrResultText.text = "文字が検出されませんでした。再撮影してください。"
            } else {
                val sb = StringBuilder()
                for (r in results) {
                    val label = when {
                        r.confidence >= 0.85f -> "[自動確定]"
                        r.confidence >= 0.60f -> "[要確認]  "
                        else                  -> "[再撮影]  "
                    }
                    sb.appendLine("$label ${r.text}  (${(r.confidence * 100).toInt()}%)")
                }
                ocrResultText.text = sb.toString().trim()
                saveButton.visibility = View.VISIBLE
            }
        }
    }

    // ──────────────────────────────────────────────
    // DB保存（信頼度60%以上を対象）
    // ──────────────────────────────────────────────

    private fun saveToDb() {
        val division = when (divisionGroup.checkedRadioButtonId) {
            R.id.radioStart -> "start"
            else            -> "end"
        }
        val productNumbers = ocrResults
            .filter { it.confidence >= 0.60f }
            .map { it.text }

        if (productNumbers.isEmpty()) {
            Toast.makeText(this, "保存できる認識結果がありません（信頼度60%未満）", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val db   = AppDatabase.getInstance(applicationContext)
                val repo = RegistrationRepository(db.registrationDao())
                repo.saveRegistration(
                    processId      = 1,
                    division       = division,
                    workerId       = 1,
                    deviceId       = Build.ID,
                    productNumbers = productNumbers
                )
            }
            Toast.makeText(
                this@MainActivity,
                "DBに保存しました（${productNumbers.size}件）",
                Toast.LENGTH_SHORT
            ).show()
            saveButton.visibility = View.GONE
            ocrResultText.text    = "保存完了！次の画像を撮影してください。"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        ocrEngine?.close()
    }
}
