package com.example.mainproject

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
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
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.lifecycleScope
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.example.mainproject.data.db.AppDatabase
import com.example.mainproject.data.repository.RegistrationRepository
import com.example.mainproject.ocr.OcrProcessor
import com.example.mainproject.worker.SyncWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var imagePreview   : ImageView
    private lateinit var overlayLabel   : TextView
    private lateinit var overlayImage   : ImageView
    private lateinit var ocrResultText  : TextView
    private lateinit var unmatchedSection: LinearLayout
    private lateinit var unmatchedText  : TextView
    private lateinit var saveButton     : Button
    private lateinit var progressBar    : ProgressBar
    private lateinit var divisionGroup  : RadioGroup

    private var ocrProcessor: OcrProcessor? = null
    private var currentMatchedItems: List<OcrProcessor.MatchedItem> = emptyList()
    private var lastPhotoPath: String? = null   // EXIF補正用

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
            photoUri?.let { uri -> runOcrFromUri(uri, isCamera = true) }
        }
    }

    // ギャラリー選択
    private val pickImage = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let { runOcrFromUri(it, isCamera = false) }
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

        imagePreview    = findViewById(R.id.imagePreview)
        overlayLabel    = findViewById(R.id.overlayLabel)
        overlayImage    = findViewById(R.id.overlayImage)
        ocrResultText   = findViewById(R.id.ocrResultText)
        unmatchedSection= findViewById(R.id.unmatchedSection)
        unmatchedText   = findViewById(R.id.unmatchedText)
        saveButton      = findViewById(R.id.saveButton)
        progressBar     = findViewById(R.id.progressBar)
        divisionGroup   = findViewById(R.id.divisionGroup)

        // OCR処理クラスをバックグラウンドで初期化
        lifecycleScope.launch(Dispatchers.IO) {
            ocrProcessor = OcrProcessor(applicationContext)
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
        lastPhotoPath = photoFile.absolutePath
        val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", photoFile)
        photoUri = uri
        takePicture.launch(uri)
    }

    // ──────────────────────────────────────────────
    // URI → Bitmap（EXIF補正あり） → OCR
    // ──────────────────────────────────────────────

    private fun runOcrFromUri(uri: Uri, isCamera: Boolean = false) {
        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                try {
                    val raw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        ImageDecoder.decodeBitmap(
                            ImageDecoder.createSource(contentResolver, uri)
                        ) { decoder, _, _ ->
                            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                        }.let { bmp ->
                            if (bmp.config == Bitmap.Config.HARDWARE)
                                bmp.copy(Bitmap.Config.ARGB_8888, false)
                            else bmp
                        }
                    } else {
                        @Suppress("DEPRECATION")
                        MediaStore.Images.Media.getBitmap(contentResolver, uri)
                    }
                    // カメラ撮影の場合は EXIF 回転を補正
                    if (isCamera) correctExifRotation(raw, lastPhotoPath) else raw
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

    /** EXIF の向き情報に基づいて Bitmap を回転補正する */
    private fun correctExifRotation(bitmap: Bitmap, path: String?): Bitmap {
        if (path == null) return bitmap
        return try {
            val exif    = ExifInterface(path)
            val degrees = when (exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90  -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else                                 -> 0f
            }
            if (degrees == 0f) bitmap
            else Bitmap.createBitmap(
                bitmap, 0, 0, bitmap.width, bitmap.height,
                Matrix().apply { postRotate(degrees) }, true
            )
        } catch (_: Exception) { bitmap }
    }

    // ──────────────────────────────────────────────
    // OCR処理（多角形検出 + オーバーレイ + ラベル照合）
    // ──────────────────────────────────────────────

    private fun runOcr(bitmap: Bitmap) {
        saveButton.visibility    = View.GONE
        unmatchedSection.visibility = View.GONE
        overlayImage.visibility  = View.GONE
        overlayLabel.visibility  = View.GONE
        ocrResultText.text       = "解析中..."
        progressBar.visibility   = View.VISIBLE
        imagePreview.setImageBitmap(bitmap)

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                ocrProcessor?.processWithOverlay(bitmap)
            }
            progressBar.visibility = View.GONE

            if (result == null) {
                ocrResultText.text = "OCRエンジンが初期化されていません。しばらく待ってから再試行してください。"
                return@launch
            }

            // オーバーレイ画像を表示
            overlayImage.setImageBitmap(result.overlayBitmap)
            overlayImage.visibility = View.VISIBLE
            overlayLabel.visibility = View.VISIBLE

            currentMatchedItems = result.matched

            // 登録候補（距離 ≤ 3）
            if (result.matched.isEmpty() && result.unmatched.isEmpty()) {
                ocrResultText.text = "文字が検出されませんでした。再撮影してください。"
            } else {
                val sb = StringBuilder()
                if (result.matched.isEmpty()) {
                    sb.appendLine("登録できる製品コードが見つかりませんでした。")
                } else {
                    sb.appendLine("【登録候補】")
                    for (item in result.matched) {
                        val distLabel = if (item.distance == 0) "完全一致" else "距離:${item.distance}"
                        val conf      = "${(item.confidence * 100).toInt()}%"
                        val rawInfo   = if (item.rawOcrText != item.label) "  ← OCR:${item.rawOcrText}" else ""
                        sb.appendLine("✓ ${item.label}  ($distLabel, 信頼度:$conf)$rawInfo")
                    }
                }
                ocrResultText.text = sb.toString().trim()
                if (result.matched.isNotEmpty()) saveButton.visibility = View.VISIBLE

                // 参考情報（距離 > 3）
                if (result.unmatched.isNotEmpty()) {
                    val usb = StringBuilder()
                    for (item in result.unmatched) {
                        usb.appendLine("・${item.rawOcrText}  (信頼度:${(item.confidence * 100).toInt()}%)")
                    }
                    unmatchedText.text = usb.toString().trim()
                    unmatchedSection.visibility = View.VISIBLE
                }
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
        val productNumbers = currentMatchedItems
            .filter { it.confidence >= 0.60f }
            .map { it.label }

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
        ocrProcessor?.close()
    }
}
