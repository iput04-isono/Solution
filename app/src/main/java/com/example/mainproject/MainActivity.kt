package com.example.mainproject

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.ConnectivityManager
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
import androidx.core.content.ContextCompat
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
import com.example.mainproject.ocr.LabelMatcher
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
    private var matchedTexts: List<String> = emptyList()  // LabelMatcher補正済みテキスト
    private var ocrEngine: OcrEngine? = null
    private var labelMatcher: LabelMatcher? = null
    private val preprocessor = ImagePreprocessor()

    // フル解像度カメラ撮影用 URI
    private var photoUri: Uri? = null

    // カメラ権限リクエスト
    private val requestCameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            launchCamera()
        } else {
            Toast.makeText(this, "カメラの権限が必要です", Toast.LENGTH_SHORT).show()
        }
    }

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

        // OCRエンジン・LabelMatcherをバックグラウンドで初期化
        lifecycleScope.launch(Dispatchers.IO) {
            ocrEngine = OcrEngine(applicationContext)
            labelMatcher = LabelMatcher(applicationContext)
        }

        // 定期同期スケジュール
        SyncWorker.schedule(this)

        // カメラボタン（権限チェックしてから起動）
        findViewById<Button>(R.id.cameraButton).setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED
            ) {
                launchCamera()
            } else {
                requestCameraPermission.launch(Manifest.permission.CAMERA)
            }
        }

        // ギャラリーボタン
        findViewById<Button>(R.id.galleryButton).setOnClickListener {
            pickImage.launch("image/*")
        }

        // 保存ボタン
        saveButton.setOnClickListener { saveToDb() }

        // 同期ボタン（手動で強制同期したい場合用）
        findViewById<Button>(R.id.syncButton).setOnClickListener {
            if (isNetworkAvailable()) {
                enqueueSyncNow()
                Toast.makeText(this, "同期を開始しました", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "オフラインです。接続時に自動同期されます", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ──────────────────────────────────────────────
    // カメラ起動（FileProvider経由）
    // ──────────────────────────────────────────────

    private fun launchCamera() {
        val photoFile = File(cacheDir, "camera_images/photo_${System.currentTimeMillis()}.jpg")
            .also { it.parentFile?.mkdirs() }
        val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", photoFile)
        photoUri = uri
        takePicture.launch(uri)
    }

    // ──────────────────────────────────────────────
    // URI → Bitmap → OCR
    // ──────────────────────────────────────────────

    private fun runOcrFromUri(uri: Uri) {
        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        ImageDecoder.decodeBitmap(
                            ImageDecoder.createSource(contentResolver, uri)
                        ) { decoder, _, _ ->
                            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                        }.let { bmp ->
                            // HARDWARE configはONNXに渡せないのでARGB_8888にコピー
                            if (bmp.config == Bitmap.Config.HARDWARE) {
                                bmp.copy(Bitmap.Config.ARGB_8888, false)
                            } else {
                                bmp
                            }
                        }
                    } else {
                        @Suppress("DEPRECATION")
                        MediaStore.Images.Media.getBitmap(contentResolver, uri)
                    }
                } catch (e: Exception) {
                    null
                }
            }
            if (bitmap == null) {
                Toast.makeText(this@MainActivity, "画像の読み込みに失敗しました", Toast.LENGTH_SHORT).show()
                return@launch
            }
            runOcr(bitmap)
        }
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

            // LabelMatcherで各OCR結果を補正
            val matcher = labelMatcher
            matchedTexts = results.map { r ->
                val best = matcher?.findBest(r.text)
                best?.first ?: r.text  // マッチなし or 未初期化ならOCR生テキストをそのまま使う
            }

            if (results.isEmpty()) {
                ocrResultText.text = "文字が検出されませんでした。再撮影してください。"
            } else {
                val sb = StringBuilder()
                for ((r, matched) in results.zip(matchedTexts)) {
                    val label = when {
                        r.confidence >= 0.85f -> "[自動確定]"
                        r.confidence >= 0.60f -> "[要確認]  "
                        else                  -> "[再撮影]  "
                    }
                    // OCRテキストと補正後が異なる場合は両方表示
                    val displayText = if (matched != r.text) "$matched  ← ${r.text}" else matched
                    sb.appendLine("$label $displayText  (${(r.confidence * 100).toInt()}%)")
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
            .zip(matchedTexts)
            .filter { (r, _) -> r.confidence >= 0.60f }
            .map { (_, matched) -> matched }

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

            // ネットワーク状態に応じてメッセージを切り替えて同期をキュー
            val isOnline = isNetworkAvailable()
            val message = if (isOnline) {
                enqueueSyncNow()
                "DBに保存しました（${productNumbers.size}件）\nオンライン：即時同期します"
            } else {
                enqueueSyncWhenOnline()
                "DBに保存しました（${productNumbers.size}件）\nオフライン：接続時に自動同期します"
            }

            Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
            saveButton.visibility = View.GONE
            ocrResultText.text    = "保存完了！次の画像を撮影してください。"
        }
    }

    /** ネットワーク接続確認 */
    private fun isNetworkAvailable(): Boolean {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
                ?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        } else {
            @Suppress("DEPRECATION")
            cm.activeNetworkInfo?.isConnected == true
        }
    }

    /** オンライン時：すぐに同期 */
    private fun enqueueSyncNow() {
        val req = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
        WorkManager.getInstance(this).enqueue(req)
    }

    /** オフライン時：接続が回復したら自動同期 */
    private fun enqueueSyncWhenOnline() {
        val req = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
        // WorkManager は制約が満たされるまで待機してから実行する
        WorkManager.getInstance(this).enqueue(req)
    }

    override fun onDestroy() {
        super.onDestroy()
        ocrEngine?.close()
    }
}
