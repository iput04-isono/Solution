package com.crossvision.f.ui.camera

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.crossvision.f.databinding.ActivityCameraBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * カメラ画面
 * UI設計書 1.4.1 カメラ起動画面に準拠
 * CameraXを使用して撮影を行う
 */
class CameraActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCameraBinding
    private var imageCapture: ImageCapture? = null
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var outputDirectory: File

    companion object {
        private const val TAG = "CameraActivity"
        private const val FILENAME_FORMAT = "yyyyMMdd_HHmmss"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCameraBinding.inflate(layoutInflater)
        setContentView(binding.root)

        outputDirectory = getOutputDirectory()
        cameraExecutor = Executors.newSingleThreadExecutor()

        startCamera()
        setupUI()
    }

    private fun setupUI() {
        // 閉じるボタン
        binding.btnClose.setOnClickListener {
            finish()
        }

        // シャッターボタン
        binding.btnCapture.setOnClickListener {
            takePhoto()
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            // プレビュー
            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(binding.previewView.surfaceProvider)
                }

            // 画像キャプチャ（端末の向きに合わせた回転情報をEXIFに付与）
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setTargetRotation(binding.previewView.display.rotation)
                .build()

            // 背面カメラを使用
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageCapture
                )
            } catch (e: Exception) {
                Log.e(TAG, "カメラの起動に失敗: ${e.message}", e)
                Toast.makeText(this, "カメラの起動に失敗しました", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePhoto() {
        val imageCapture = imageCapture ?: return

        // 撮影直前に端末の向きを再取得して回転情報を更新（カメラ起動後に回転した場合に対応）
        binding.previewView.display?.rotation?.let { imageCapture.targetRotation = it }

        val photoFile = File(
            outputDirectory,
            SimpleDateFormat(FILENAME_FORMAT, Locale.JAPAN)
                .format(System.currentTimeMillis()) + ".jpg"
        )

        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val resultIntent = Intent().apply {
                        putExtra("IMAGE_PATH", photoFile.absolutePath)
                    }
                    setResult(RESULT_OK, resultIntent)
                    finish()
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "撮影エラー: ${exception.message}", exception)
                    Toast.makeText(
                        this@CameraActivity,
                        "撮影に失敗しました",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        )
    }

    private fun getOutputDirectory(): File {
        val mediaDir = filesDir.let {
            File(it, "captured_images").apply { mkdirs() }
        }
        return if (mediaDir.exists()) mediaDir else filesDir
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}
