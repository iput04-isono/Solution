package com.crossvision.f.ui.recognize

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.crossvision.f.databinding.ActivityRecognizeBinding
import com.crossvision.f.ocr.OcrProcessor
import com.crossvision.f.ocr.OcrResult
import com.crossvision.f.ui.camera.CameraActivity
import com.crossvision.f.ui.confirm.ConfirmActivity
import kotlinx.coroutines.launch
import java.io.File

/**
 * 画像認識画面
 * UI設計書 1.3.1 画像認識画面に準拠
 */
class RecognizeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRecognizeBinding
    private val ocrProcessor = OcrProcessor()
    private var currentBitmap: Bitmap? = null
    private var constructionName = ""
    private var processName = ""
    private var userId = ""

    // カメラ権限リクエスト
    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            launchCamera()
        } else {
            Toast.makeText(this, "カメラの使用許可が必要です", Toast.LENGTH_LONG).show()
        }
    }

    // カメラ撮影結果
    private val cameraLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val imagePath = result.data?.getStringExtra("IMAGE_PATH")
            if (imagePath != null) {
                val bitmap = BitmapFactory.decodeFile(imagePath)
                if (bitmap != null) {
                    setPreviewImage(bitmap)
                }
            }
        }
    }

    // ギャラリーから画像選択
    private val galleryLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { imageUri ->
            try {
                val inputStream = contentResolver.openInputStream(imageUri)
                val bitmap = BitmapFactory.decodeStream(inputStream)
                inputStream?.close()
                if (bitmap != null) {
                    setPreviewImage(bitmap)
                }
            } catch (e: Exception) {
                Toast.makeText(this, "画像の読み込みに失敗しました", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRecognizeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        userId = intent.getStringExtra("USER_ID") ?: ""
        constructionName = intent.getStringExtra("CONSTRUCTION_NAME") ?: ""
        processName = intent.getStringExtra("PROCESS_NAME") ?: ""

        setupToolbar()
        setupUI()
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener { finish() }
    }

    private fun setupUI() {
        // カメラボタン
        binding.btnCamera.setOnClickListener {
            checkCameraPermissionAndLaunch()
        }

        // ギャラリーボタン
        binding.btnGallery.setOnClickListener {
            galleryLauncher.launch("image/*")
        }

        // 認識実行ボタン
        binding.btnRecognize.setOnClickListener {
            currentBitmap?.let { bitmap ->
                performOcr(bitmap)
            }
        }
    }

    private fun checkCameraPermissionAndLaunch() {
        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            launchCamera()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun launchCamera() {
        val intent = Intent(this, CameraActivity::class.java)
        cameraLauncher.launch(intent)
    }

    private fun setPreviewImage(bitmap: Bitmap) {
        currentBitmap = bitmap
        binding.ivPreview.setImageBitmap(bitmap)
        binding.ivPreview.visibility = View.VISIBLE
        binding.placeholderLayout.visibility = View.GONE
        binding.btnRecognize.visibility = View.VISIBLE
    }

    private fun performOcr(bitmap: Bitmap) {
        binding.progressRecognize.visibility = View.VISIBLE
        binding.btnRecognize.isEnabled = false

        lifecycleScope.launch {
            try {
                val results = ocrProcessor.recognizeMultipleProducts(bitmap)
                navigateToConfirm(results)
            } catch (e: Exception) {
                Toast.makeText(
                    this@RecognizeActivity,
                    "認識に失敗しました: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                binding.progressRecognize.visibility = View.GONE
                binding.btnRecognize.isEnabled = true
            }
        }
    }

    private fun navigateToConfirm(results: List<OcrResult>) {
        val intent = Intent(this, ConfirmActivity::class.java).apply {
            putExtra("USER_ID", userId)
            putExtra("CONSTRUCTION_NAME", constructionName)
            putExtra("PROCESS_NAME", processName)
            putStringArrayListExtra(
                "PRODUCT_CODES",
                ArrayList(results.map { it.cleanedCode })
            )
            putStringArrayListExtra(
                "RAW_TEXTS",
                ArrayList(results.map { it.rawText })
            )
        }
        startActivity(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        ocrProcessor.close()
    }
}
