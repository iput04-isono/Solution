package com.crossvision.f.ui.camera

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.crossvision.f.databinding.ActivityCameraBinding
import com.crossvision.f.ocr.ImagePreprocessor
import com.crossvision.f.ocr.OcrEngine
import kotlinx.coroutines.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * カメラ画面
 * UI設計書 1.4.1 カメラ起動画面に準拠
 * CameraXを使用して撮影を行う。
 *
 * 【ライブビューファインダー機能】
 * ImageAnalysis ユースケースにより、プレビュー中のフレームをDBNetに渡し、
 * 検出された文字列領域をDetectionOverlayViewにリアルタイム描画する。
 * 認識（ppocr_rec.onnx）はシャッター後のみ実行し、ライブでは検出のみに限定する。
 */
class CameraActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCameraBinding
    private var imageCapture: ImageCapture? = null
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var outputDirectory: File

    // ── ライブ検出用 ─────────────────────────────────────────────────────
    private var ocrEngine: OcrEngine? = null
    private val preprocessor = ImagePreprocessor()

    /** バックグラウンド解析用スコープ */
    private val analysisScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    /**
     * フレーム解析のスロットリング制御フラグ。
     * true = 解析中 → 新しいフレームをスキップ。
     * これにより、重い処理がキューに積み重なるのを防ぐ。
     */
    @Volatile private var isAnalyzing = false

    companion object {
        private const val TAG = "CameraActivity"
        private const val FILENAME_FORMAT = "yyyyMMdd_HHmmss"

        /** ライブ検出の最小間隔（ms）。前回の解析完了からこの時間以上空けてから次を実行。 */
        private const val ANALYSIS_INTERVAL_MS = 800L
    }

    /** 最後に解析を完了した時刻（スロットリング用） */
    private var lastAnalysisTimeMs = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCameraBinding.inflate(layoutInflater)
        setContentView(binding.root)

        outputDirectory = getOutputDirectory()
        cameraExecutor = Executors.newSingleThreadExecutor()

        // OcrEngineをバックグラウンドで初期化（起動時のUIブロックを防ぐ）
        analysisScope.launch {
            ocrEngine = OcrEngine(this@CameraActivity)
            Log.d(TAG, "OcrEngine 初期化完了（ライブ検出用）")
        }

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

            // ── ライブ検出用 ImageAnalysis ────────────────────────────────
            val imageAnalysis = ImageAnalysis.Builder()
                // 解析が追いつかない場合は最新フレームだけを保持（キュー溢れ防止）
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { analysis ->
                    analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                        analyzeFrame(imageProxy)
                    }
                }

            // 背面カメラを使用
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageCapture, imageAnalysis
                )
            } catch (e: Exception) {
                Log.e(TAG, "カメラの起動に失敗: ${e.message}", e)
                Toast.makeText(this, "カメラの起動に失敗しました", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /**
     * ImageAnalysis アナライザーのコールバック。
     * スロットリングで間引きながらDBNet検出を実行し、オーバーレイを更新する。
     */
    private fun analyzeFrame(imageProxy: ImageProxy) {
        val now = System.currentTimeMillis()

        // スロットリング: 前回完了から ANALYSIS_INTERVAL_MS 未満はスキップ
        if (isAnalyzing || now - lastAnalysisTimeMs < ANALYSIS_INTERVAL_MS) {
            imageProxy.close()
            return
        }

        val engine = ocrEngine
        if (engine == null) {
            // エンジン初期化前はスキップ
            imageProxy.close()
            return
        }

        isAnalyzing = true

        // ImageAnalysis フレームはカメラセンサー向き（ランドスケープ）のまま届く。
        // 画面表示と合わせるため、rotationDegrees 分だけ回転補正する。
        val rotationDegrees = imageProxy.imageInfo.rotationDegrees
        val bitmap: Bitmap
        try {
            val raw = imageProxy.toBitmap()
            bitmap = if (rotationDegrees != 0) {
                val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
                Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
            } else raw
        } finally {
            imageProxy.close()   // 変換後すぐ解放して CameraX にバッファを返す
        }

        analysisScope.launch {
            try {
                val processed = preprocessor.preprocess(bitmap)
                val polygons  = engine.detectTextPolygonOnly(processed)

                // UIスレッドにオーバーレイを更新を依頼
                withContext(Dispatchers.Main) {
                    binding.detectionOverlay.updatePolygons(
                        polygons,
                        processed.width,
                        processed.height
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "ライブ検出エラー（無視して継続）: ${e.message}")
            } finally {
                lastAnalysisTimeMs = System.currentTimeMillis()
                isAnalyzing = false
            }
        }
    }


    private fun takePhoto() {
        val imageCapture = imageCapture ?: return

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
                    // シャッター後はオーバーレイを消す（静止画処理画面へ遷移するため）
                    binding.detectionOverlay.clearPolygons()

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

        // ocrEngine を null にしてから scope をキャンセルする。
        // ONNX 推論はネイティブコードのため CancellationException では止まらない。
        // 即座に close() するとネイティブコードが解放済みセッションにアクセスし
        // SIGSEGV が発生するため、500ms の猶予を設けてから close() する。
        val engineToClose = ocrEngine
        ocrEngine = null
        analysisScope.cancel()
        Thread {
            Thread.sleep(500)
            engineToClose?.close()
        }.also { it.isDaemon = true }.start()
    }
}
