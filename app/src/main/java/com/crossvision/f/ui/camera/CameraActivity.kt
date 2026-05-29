package com.crossvision.f.ui.camera

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CaptureRequest
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import android.graphics.BitmapFactory
import android.media.ExifInterface
import com.crossvision.f.R
import com.crossvision.f.databinding.ActivityCameraBinding
import com.crossvision.f.ocr.ImagePreprocessor
import com.crossvision.f.ocr.ImageQualityChecker
import kotlinx.coroutines.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.*

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
class CameraActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var binding: ActivityCameraBinding
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var scaleGestureDetector: ScaleGestureDetector
    private lateinit var outputDirectory: File

    // ── カメラ機能制御用 ──────────────────────────────────────────────────
    private var cameraControl: CameraControl? = null
    private var cameraInfo: CameraInfo? = null
    private var flashMode = ImageCapture.FLASH_MODE_AUTO // デフォルトはAUTO

    // ── 品質チェック・センサー用 ──────────────────────────────────────────
    private var sensorManager: SensorManager? = null
    private var accelerometer: Sensor? = null
    private var currentRollAngle: Double = 0.0

    private val preprocessor = ImagePreprocessor()
    private var ocrProcessor: com.crossvision.f.ocr.OcrProcessor? = null

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

        /** ライブ検出の最小間隔（ms）。端末負荷軽減のため 150ms 程度確保する。 */
        private const val ANALYSIS_INTERVAL_MS = 150L
    }

    /** 最後に解析を完了した時刻（スロットリング用） */
    private var lastAnalysisTimeMs = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCameraBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // プレビューのスケールタイプをFILL_CENTERに強制固定
        binding.previewView.scaleType = androidx.camera.view.PreviewView.ScaleType.FILL_CENTER

        outputDirectory = getOutputDirectory()
        cameraExecutor = Executors.newSingleThreadExecutor()
        ocrProcessor = com.crossvision.f.ocr.OcrProcessor.getInstance(this)

        setupSensors()
        startCamera()
        setupUI()
    }

    private fun setupSensors() {
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    }

    override fun onResume() {
        super.onResume()
        accelerometer?.let {
            sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager?.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_ACCELEROMETER) {
            val ax = event.values[0]
            val ay = event.values[1]
            // 加速度からロール角（左右の傾き）を計算 (単位: 度)
            currentRollAngle = atan2(ax.toDouble(), ay.toDouble()) * (180.0 / PI)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun setupUI() {
        // 閉じるボタン
        binding.btnClose.setOnClickListener {
            finish()
        }

        // シャッターボタン
        binding.btnCapture.setOnClickListener {
            takePhoto()
        }
        
        setupZoom()
    }

    private fun setupZoom() {
        scaleGestureDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val camera = camera ?: return true
                val currentZoomRatio = camera.cameraInfo.zoomState.value?.linearZoom ?: 0f
                val delta = detector.scaleFactor - 1f
                val newZoom = (currentZoomRatio + delta).coerceIn(0f, 1f)
                camera.cameraControl.setLinearZoom(newZoom)
                return true
            }
        })

        binding.previewView.setOnTouchListener { _, event ->
            scaleGestureDetector.onTouchEvent(event)
            if (event.action == MotionEvent.ACTION_UP) {
                val cam = camera ?: return@setOnTouchListener true
                val factory = binding.previewView.meteringPointFactory
                val point = factory.createPoint(event.x, event.y)
                val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
                    .setAutoCancelDuration(3, TimeUnit.SECONDS)
                    .build()
                cam.cameraControl.startFocusAndMetering(action)

                showFocusIndicator(event.x, event.y)
            }
            true
        }
    }

    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            // プレビュー
            val previewBuilder = Preview.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            
            // 手振れ補正を有効化 (Camera2Interopを使用)
            Camera2Interop.Extender(previewBuilder).apply {
                setCaptureRequestOption(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON)
                setCaptureRequestOption(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON)
            }
            val preview = previewBuilder.build()
                .also {
                    it.setSurfaceProvider(binding.previewView.surfaceProvider)
                }

            // 画像キャプチャ（端末の向きに合わせた回転情報をEXIFに付与）
            val imageCaptureBuilder = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setTargetRotation(binding.previewView.display.rotation)
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .setFlashMode(flashMode)
            
            Camera2Interop.Extender(imageCaptureBuilder).apply {
                setCaptureRequestOption(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON)
            }
            imageCapture = imageCaptureBuilder.build()

            // ── ライブ検出用 ImageAnalysis ────────────────────────────────
            val imageAnalysisBuilder = ImageAnalysis.Builder()
                // 解析が追いつかない場合は最新フレームだけを保持（キュー溢れ防止）
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                // アスペクト比を他と揃える
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            
            Camera2Interop.Extender(imageAnalysisBuilder).apply {
                setCaptureRequestOption(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON)
            }
            val imageAnalysis = imageAnalysisBuilder.build()
                .also { analysis ->
                    analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                        analyzeFrame(imageProxy)
                    }
                }

            // 背面カメラを使用
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                val camera = cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageCapture, imageAnalysis
                )
                setupCameraControls(camera)
            } catch (e: Exception) {
                Log.e(TAG, "カメラの起動に失敗: ${e.message}", e)
                Toast.makeText(this, "カメラの起動に失敗しました", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /**
     * カメラのズーム・フラッシュ・フォーカス・明るさ調整のセットアップ
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun setupCameraControls(camera: Camera) {
        cameraControl = camera.cameraControl
        cameraInfo = camera.cameraInfo

        // 1. フラッシュ設定
        binding.btnFlash.setOnClickListener {
            flashMode = when (flashMode) {
                ImageCapture.FLASH_MODE_AUTO -> ImageCapture.FLASH_MODE_ON
                ImageCapture.FLASH_MODE_ON -> ImageCapture.FLASH_MODE_OFF
                else -> ImageCapture.FLASH_MODE_AUTO
            }
            imageCapture?.flashMode = flashMode

            val iconRes = when (flashMode) {
                ImageCapture.FLASH_MODE_AUTO -> R.drawable.ic_flash_auto
                ImageCapture.FLASH_MODE_ON -> R.drawable.ic_flash_on
                else -> R.drawable.ic_flash_off
            }
            binding.btnFlash.setImageResource(iconRes)
        }

        // 2. ズーム設定 (ピンチイン・アウト)
        val scaleGestureDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val currentZoomRatio = cameraInfo?.zoomState?.value?.zoomRatio ?: 1f
                val delta = detector.scaleFactor
                cameraControl?.setZoomRatio(currentZoomRatio * delta)
                return true
            }
        })

        // 3. ピント合わせ (タップフォーカス)
        binding.previewView.setOnTouchListener { _, event ->
            scaleGestureDetector.onTouchEvent(event)
            if (event.action == MotionEvent.ACTION_UP) {
                val factory = binding.previewView.meteringPointFactory
                val point = factory.createPoint(event.x, event.y)
                val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
                    .setAutoCancelDuration(3, TimeUnit.SECONDS)
                    .build()
                cameraControl?.startFocusAndMetering(action)

                showFocusIndicator(event.x, event.y)
            }
            true
        }

        // 4. 明るさ調整 (露出補正)
        cameraInfo?.exposureState?.let { exposureState ->
            val range = exposureState.exposureCompensationRange
            if (range.upper != range.lower) {
                binding.seekExposure.max = range.upper - range.lower
                binding.seekExposure.progress = exposureState.exposureCompensationIndex - range.lower

                binding.seekExposure.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                        if (fromUser) {
                            cameraControl?.setExposureCompensationIndex(progress + range.lower)
                        }
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                    override fun onStopTrackingTouch(seekBar: SeekBar?) {}
                })
            }
        }
    }

    private fun showFocusIndicator(x: Float, y: Float) {
        val indicator = binding.focusIndicator
        indicator.x = x - indicator.width / 2f
        indicator.y = y - indicator.height / 2f
        indicator.alpha = 1f
        indicator.visibility = android.view.View.VISIBLE

        indicator.animate()
            .setStartDelay(500)
            .setDuration(300)
            .alpha(0f)
            .withEndAction {
                indicator.visibility = android.view.View.INVISIBLE
            }
            .start()
    }

    private fun analyzeFrame(imageProxy: ImageProxy) {
        val now = System.currentTimeMillis()

        // スロットリング: 前回完了から ANALYSIS_INTERVAL_MS 未満はスキップ
        if (isAnalyzing || now - lastAnalysisTimeMs < ANALYSIS_INTERVAL_MS) {
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
            imageProxy.close()
        }

        analysisScope.launch {
            try {
                // 1. 品質チェック（傾き: 縦・横どちらでもOK）
                // 0, 90, 180, 270度付近であれば許容する
                val angleMod = abs(currentRollAngle % 90.0)
                val isTilted = angleMod > 15.0 && angleMod < 75.0
                
                // 2. 品質チェック（ぼけ）
                val blurScore = ImageQualityChecker.calculateBlurScore(bitmap)
                val isBlurred = blurScore < 100.0

                // UIスレッドで警告状態を更新
                withContext(Dispatchers.Main) {
                    updateQualityUI(isBlurred, isTilted)
                }

                // 3. OCR検出と照合
                // ※本来はisBlurredやisTiltedがtrueの時はスキップしても良いが、今回は常に実行してテスト
                // リアルタイム性を高めるため、検出する最大ポリゴン数を 5 に制限する
                // リアルタイムでの文字追尾（UX強化）のため、フルOCRを実行
                val startTime = System.currentTimeMillis()
                val results = ocrProcessor?.recognizeText(bitmap, maxPolygons = 8, detectOnly = false) ?: emptyList()
                val duration = System.currentTimeMillis() - startTime
                Log.d(TAG, "[Performance] OCR処理時間: ${duration}ms, 検出文字列数: ${results.size}個")
                
                withContext(Dispatchers.Main) {
                    binding.detectionOverlayView.updateResults(
                        results,
                        bitmap.width,
                        bitmap.height
                    )
                }

            } catch (e: Exception) {
                Log.w(TAG, "品質解析エラー（無視して継続）: ${e.message}")
            } finally {
                lastAnalysisTimeMs = System.currentTimeMillis()
                isAnalyzing = false
            }
        }
    }

    /** 品質状態に応じてUI（警告メッセージ、ガイド枠の色）を更新する */
    private fun updateQualityUI(isBlurred: Boolean, isTilted: Boolean) {
        val warningText = when {
            isBlurred -> "ぼけています"
            isTilted  -> "傾いています"
            else -> null
        }

        if (warningText != null) {
            binding.tvQualityWarning.text = warningText
            binding.tvQualityWarning.visibility = android.view.View.VISIBLE
            // コーナーマークを赤色に
            binding.guideFrame.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#C62828"))
        } else {
            binding.tvQualityWarning.visibility = android.view.View.GONE
            // コーナーマークを緑色に
            binding.guideFrame.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#2E7D32"))
        }
    }


    private fun takePhoto() {
        // 撮影処理中の二重タップ防止
        binding.btnCapture.isEnabled = false

        val photoFile = File(
            outputDirectory,
            SimpleDateFormat(FILENAME_FORMAT, Locale.JAPAN)
                .format(System.currentTimeMillis()) + ".jpg"
        )

        // PreviewViewから直接現在の表示画像を取得する
        val previewBitmap = binding.previewView.bitmap

        if (previewBitmap == null) {
            binding.btnCapture.isEnabled = true
            Toast.makeText(this, "画像の取得に失敗しました", Toast.LENGTH_SHORT).show()
            return
        }

        // シャッター音を鳴らさずに無音で保存処理を実行
        analysisScope.launch(Dispatchers.IO) {
            val success = savePreviewBitmap(previewBitmap, photoFile)
            
            withContext(Dispatchers.Main) {
                binding.btnCapture.isEnabled = true
                if (success) {
                    val resultIntent = Intent().apply {
                        putExtra("IMAGE_PATH", photoFile.absolutePath)
                    }
                    setResult(RESULT_OK, resultIntent)
                    finish()
                } else {
                    Toast.makeText(
                        this@CameraActivity,
                        "画像の保存に失敗しました",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private fun savePreviewBitmap(previewBitmap: Bitmap, photoFile: File): Boolean {
        return try {
            photoFile.outputStream().use { fos ->
                previewBitmap.compress(Bitmap.CompressFormat.JPEG, 95, fos)
            }
            previewBitmap.recycle()
            true
        } catch (e: Exception) {
            Log.e(TAG, "画像の保存中にエラーが発生しました", e)
            previewBitmap.recycle()
            false
        }
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

        // 解析用のコルーチンスコープをキャンセル
        analysisScope.cancel()
        
        ocrProcessor = null
    }
}
