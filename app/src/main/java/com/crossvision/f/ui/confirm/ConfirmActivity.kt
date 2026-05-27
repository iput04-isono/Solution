package com.crossvision.f.ui.confirm

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.EditText
import android.widget.AutoCompleteTextView
import android.widget.ArrayAdapter
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.crossvision.f.databinding.ActivityConfirmBinding
import com.crossvision.f.ocr.ProductCodeValidator
import com.crossvision.f.ui.register.RegisterActivity
import com.crossvision.f.ui.process.ProcessSelectionActivity
import androidx.activity.result.contract.ActivityResultContracts
import android.graphics.Matrix
import android.graphics.PointF
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import java.io.File
import androidx.lifecycle.lifecycleScope
import com.crossvision.f.data.repository.AppRepository
import kotlinx.coroutines.launch

/**
 * 認識結果確認画面
 * UI設計書 1.5.1 認識結果確認画面に準拠
 */
class ConfirmActivity : AppCompatActivity() {

    private lateinit var binding: ActivityConfirmBinding
    private lateinit var adapter: RecognizedProductAdapter
    private var constructionName = ""
    private var processName = ""
    private var userId = ""
    private var overlayBitmap: android.graphics.Bitmap? = null
    
    private lateinit var repository: AppRepository
    private var masterProductCodes = setOf<String>()
    private var unmatchedItems = mutableListOf<String>()

    // ズーム・パン用
    private var imageMatrix = Matrix()
    private var savedMatrix = Matrix()
    private var mode = NONE
    private val start = PointF()
    private val mid = PointF()
    private var oldDist = 1f
    private lateinit var scaleDetector: ScaleGestureDetector

    companion object {
        private const val NONE = 0
        private const val DRAG = 1
        private const val ZOOM = 2
    }

    // 工事・工程変更用ランチャー
    private val editProcessLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val data = result.data
            if (data != null) {
                constructionName = data.getStringExtra("CONSTRUCTION_NAME") ?: constructionName
                processName = data.getStringExtra("PROCESS_NAME") ?: processName
                updateProcessInfoUI()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityConfirmBinding.inflate(layoutInflater)
        setContentView(binding.root)

        userId = intent.getStringExtra("USER_ID") ?: ""
        constructionName = intent.getStringExtra("CONSTRUCTION_NAME") ?: ""
        processName = intent.getStringExtra("PROCESS_NAME") ?: ""

        val productCodes    = intent.getStringArrayListExtra("PRODUCT_CODES") ?: arrayListOf()
        val rawTexts        = intent.getStringArrayListExtra("RAW_TEXTS") ?: arrayListOf()
        val debugInfo       = intent.getStringArrayListExtra("DEBUG_INFO") ?: arrayListOf()
        val unmatchedTexts  = intent.getStringArrayListExtra("UNMATCHED_TEXTS") ?: arrayListOf()
        val cropPaths       = intent.getStringArrayListExtra("CROP_PATHS") ?: arrayListOf()
        val overlayPath     = intent.getStringExtra("OVERLAY_IMAGE_PATH")

        unmatchedItems.clear()
        unmatchedItems.addAll(unmatchedTexts)

        setupToolbar()
        setupOverlayImage(overlayPath)
        
        repository = AppRepository(this)
        loadMasterData {
            setupRecyclerView(productCodes, rawTexts, debugInfo, cropPaths)
        }
        
        setupUnmatchedSection()
        updateProcessInfoUI()
        setupUI()
    }

    private fun loadMasterData(onComplete: () -> Unit) {
        lifecycleScope.launch {
            try {
                val labels = repository.getAllProductLabels()
                masterProductCodes = labels.map { it.code }.toSet()
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                onComplete()
            }
        }
    }

    private fun updateProcessInfoUI() {
        val displayConst = if (constructionName.isNotEmpty()) constructionName else "未指定"
        val displayProc = if (processName.isNotEmpty()) processName else "未指定"
        binding.tvProcessInfo.text = "【$displayConst】$displayProc"
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener { finish() }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupOverlayImage(path: String?) {
        if (path == null) return
        val file = File(path)
        if (!file.exists()) return
        
        // 以前のビットマップがあれば解放
        overlayBitmap?.recycle()
        
        // メモリ節約のため、リサイズして読み込む
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeFile(path, options)
        
        // 長辺を1280px程度に制限（メモリ不足とANR対策）
        val targetSize = 1280
        var inSampleSize = 1
        if (options.outHeight > targetSize || options.outWidth > targetSize) {
            val halfHeight = options.outHeight / 2
            val halfWidth = options.outWidth / 2
            while (halfHeight / inSampleSize >= targetSize && halfWidth / inSampleSize >= targetSize) {
                inSampleSize *= 2
            }
        }
        
        options.inJustDecodeBounds = false
        options.inSampleSize = inSampleSize
        overlayBitmap = BitmapFactory.decodeFile(path, options)
        
        val bitmap = overlayBitmap ?: return
        binding.ivOverlay.setImageBitmap(bitmap)
        binding.ivOverlay.visibility = View.VISIBLE
        binding.ivOverlay.scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE

        // タップで全画面表示
        binding.ivOverlay.setOnClickListener {
            showFullScreenImage(bitmap)
        }
    }

    /** 画像を全画面ダイアログで表示（ズーム機能付き） */
    @SuppressLint("ClickableViewAccessibility")
    private fun showFullScreenImage(bitmap: android.graphics.Bitmap) {
        val dialog = android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        
        // メインコンテナ
        val root = android.widget.FrameLayout(this).apply {
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(android.graphics.Color.BLACK)
        }

        // ズーム可能な画像
        val imageView = android.widget.ImageView(this).apply {
            setImageBitmap(bitmap)
            scaleType = android.widget.ImageView.ScaleType.MATRIX
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        root.addView(imageView)

        // 閉じるボタン (X)
        val closeButton = android.widget.ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            background = android.graphics.drawable.ColorDrawable(android.graphics.Color.parseColor("#44000000"))
            imageTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE)
            layoutParams = android.widget.FrameLayout.LayoutParams(
                (48 * resources.displayMetrics.density).toInt(),
                (48 * resources.displayMetrics.density).toInt()
            ).apply {
                gravity = android.view.Gravity.TOP or android.view.Gravity.END
                setMargins(0, (16 * resources.displayMetrics.density).toInt(), (16 * resources.displayMetrics.density).toInt(), 0)
            }
            setOnClickListener { dialog.dismiss() }
        }
        root.addView(closeButton)

        dialog.setContentView(root)

        val matrix = Matrix()
        val savedMat = Matrix()
        val startPoint = PointF()
        val midPoint = PointF()
        var oldDist = 1f
        var currentMode = NONE

        val scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                matrix.postScale(detector.scaleFactor, detector.scaleFactor, detector.focusX, detector.focusY)
                imageView.imageMatrix = matrix
                return true
            }
        })

        imageView.setOnTouchListener { _, event ->
            scaleDetector.onTouchEvent(event)
            when (event.action and MotionEvent.ACTION_MASK) {
                MotionEvent.ACTION_DOWN -> {
                    savedMat.set(matrix)
                    startPoint.set(event.x, event.y)
                    currentMode = DRAG
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    oldDist = spacing(event)
                    if (oldDist > 10f) {
                        savedMat.set(matrix)
                        midPoint(midPoint, event)
                        currentMode = ZOOM
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> currentMode = NONE
                MotionEvent.ACTION_MOVE -> {
                    if (currentMode == DRAG) {
                        matrix.set(savedMat)
                        matrix.postTranslate(event.x - startPoint.x, event.y - startPoint.y)
                    } else if (currentMode == ZOOM) {
                        val newDist = spacing(event)
                        if (newDist > 10f) {
                            matrix.set(savedMat)
                            val scale = newDist / oldDist
                            matrix.postScale(scale, scale, midPoint.x, midPoint.y)
                        }
                    }
                }
            }
            imageView.imageMatrix = matrix
            true
        }

        dialog.setOnDismissListener {
            imageView.setImageDrawable(null) // メモリ解放を助ける
        }

        dialog.show()
    }

    private fun spacing(event: MotionEvent): Float {
        if (event.pointerCount < 2) return 0f
        val x = event.getX(0) - event.getX(1)
        val y = event.getY(0) - event.getY(1)
        return Math.sqrt((x * x + y * y).toDouble()).toFloat()
    }

    private fun midPoint(point: PointF, event: MotionEvent) {
        if (event.pointerCount < 2) return
        val x = event.getX(0) + event.getX(1)
        val y = event.getY(0) + event.getY(1)
        point.set(x / 2, y / 2)
    }

    private fun setupUnmatchedSection() {
        val container = binding.unmatchedList
        container.removeAllViews()
        if (unmatchedItems.isEmpty()) {
            binding.unmatchedSection.visibility = View.GONE
            return
        }
        binding.unmatchedSection.visibility = View.VISIBLE
        val dp8 = (8 * resources.displayMetrics.density).toInt()
        val dp4 = (4 * resources.displayMetrics.density).toInt()
        unmatchedItems.forEach { fullText ->
            val tv = TextView(this).apply {
                this.text = "・$fullText"
                textSize = 13f
                setTextColor(0xFF495057.toInt())
                setPadding(0, dp4, 0, dp4)
                val bgDrawable = android.graphics.drawable.GradientDrawable().apply {
                    setColor(0xFFFFFFFF.toInt())
                    cornerRadius = dp4.toFloat()
                    setStroke(1, 0xFFCED4DA.toInt())
                }
                background = bgDrawable
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, dp4, 0, 0) }
                layoutParams = lp
                setPadding(dp8, dp8, dp8, dp8)
                setOnClickListener {
                    showUnmatchedEditDialog(fullText)
                }
            }
            container.addView(tv)
        }
    }

    private fun setupRecyclerView(
        codes: List<String>, 
        rawTexts: List<String>, 
        debugInfo: List<String> = emptyList(),
        cropPaths: List<String> = emptyList()
    ) {
        adapter = RecognizedProductAdapter(
            onEditClick = { position -> showEditDialog(position) },
            onDeleteClick = { position -> showDeleteDialog(position) },
            onSelectionChanged = { _, _ -> updateRegisterButton() }
        )

        binding.rvResults.layoutManager = LinearLayoutManager(this)
        binding.rvResults.adapter = adapter

        val items = codes.mapIndexed { index, code ->
            val raw = rawTexts.getOrElse(index) { code }
            val debug = debugInfo.getOrElse(index) { "" }
            val cropPath = cropPaths.getOrNull(index)?.takeIf { it.isNotEmpty() }
            
            // デバッグ時：rawTextにデバッグ情報を付加して表示
            val displayRaw = if (debug.isNotEmpty()) "$raw\n[$debug]" else raw
            RecognizedItem(
                productCode = code,
                rawText = displayRaw,
                cropImagePath = cropPath,
                isInMaster = masterProductCodes.contains(code)
            )
        }
        adapter.setItems(items)

        updateResultCount()
        updateEmptyState()
    }

    private fun setupUI() {
        // 工事・工程情報 変更ボタン
        binding.btnEditProcess.setOnClickListener {
            val intent = Intent(this, ProcessSelectionActivity::class.java).apply {
                putExtra("USER_ID", userId)
                putExtra("EDIT_MODE", true) // 編集モードフラグ
            }
            editProcessLauncher.launch(intent)
        }

        // 手動追加ボタン
        binding.btnAddManual.setOnClickListener {
            showAddDialog()
        }

        // 再撮影ボタン
        binding.btnRetake.setOnClickListener {
            finish()
        }

        // 登録ボタン
        binding.btnRegister.setOnClickListener {
            val selectedItems = adapter.getSelectedItems()
            if (selectedItems.isNotEmpty()) {
                navigateToRegister(selectedItems)
            }
        }
    }

    private fun showEditDialog(position: Int) {
        val currentItem = adapter.getItems()[position]
        val autoCompleteTextView = AutoCompleteTextView(this).apply {
            setText(currentItem.productCode)
            setPadding(48, 32, 48, 32)
            val adapter = ArrayAdapter(this@ConfirmActivity, android.R.layout.simple_dropdown_item_1line, masterProductCodes.toList())
            setAdapter(adapter)
            threshold = 1
        }

        AlertDialog.Builder(this)
            .setTitle("製品コードを編集")
            .setView(autoCompleteTextView)
            .setPositiveButton("保存") { _, _ ->
                val newCode = autoCompleteTextView.text.toString().trim()
                val validation = ProductCodeValidator.validate(newCode)
                if (validation.isValid) {
                    val isInMaster = masterProductCodes.contains(newCode)
                    if (!isInMaster) {
                        AlertDialog.Builder(this)
                            .setTitle("マスター未登録の警告")
                            .setMessage("入力された製品コード「$newCode」はマスターに登録されていません。\nこのまま保存しますか？")
                            .setPositiveButton("はい") { _, _ ->
                                adapter.updateItem(position, newCode, false)
                            }
                            .setNegativeButton("いいえ", null)
                            .show()
                    } else {
                        adapter.updateItem(position, newCode, true)
                    }
                } else {
                    AlertDialog.Builder(this)
                        .setTitle("入力エラー")
                        .setMessage(validation.message)
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun showDeleteDialog(position: Int) {
        AlertDialog.Builder(this)
            .setTitle("削除確認")
            .setMessage("この製品コードを削除しますか？")
            .setPositiveButton("削除") { _, _ ->
                adapter.removeItem(position)
                updateResultCount()
                updateEmptyState()
                updateRegisterButton()
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun showAddDialog() {
        val autoCompleteTextView = AutoCompleteTextView(this).apply {
            hint = "製品コードを入力"
            setPadding(48, 32, 48, 32)
            val adapter = ArrayAdapter(this@ConfirmActivity, android.R.layout.simple_dropdown_item_1line, masterProductCodes.toList())
            setAdapter(adapter)
            threshold = 1
        }

        AlertDialog.Builder(this)
            .setTitle("製品コードを手動追加")
            .setView(autoCompleteTextView)
            .setPositiveButton("追加") { _, _ ->
                val code = autoCompleteTextView.text.toString().trim()
                val cleaned = ProductCodeValidator.cleanProductCode(code)
                val validation = ProductCodeValidator.validate(cleaned)

                if (validation.isValid) {
                    val isInMaster = masterProductCodes.contains(cleaned)
                    val action = {
                        adapter.addItem(
                            RecognizedItem(
                                productCode = cleaned,
                                rawText = code,
                                isEdited = true,
                                isInMaster = isInMaster
                            )
                        )
                        updateResultCount()
                        updateEmptyState()
                    }

                    if (!isInMaster) {
                        AlertDialog.Builder(this)
                            .setTitle("マスター未登録の警告")
                            .setMessage("入力された製品コード「$cleaned」はマスターに登録されていません。\nこのまま追加しますか？")
                            .setPositiveButton("はい") { _, _ -> action() }
                            .setNegativeButton("いいえ", null)
                            .show()
                    } else {
                        action()
                    }
                } else {
                    AlertDialog.Builder(this)
                        .setTitle("入力エラー")
                        .setMessage(validation.message)
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun showUnmatchedEditDialog(fullText: String) {
        val cleanCode = fullText.substringBefore("  (信頼度:").trim()
        val autoCompleteTextView = AutoCompleteTextView(this).apply {
            setText(cleanCode)
            setPadding(48, 32, 48, 32)
            val adapter = ArrayAdapter(this@ConfirmActivity, android.R.layout.simple_dropdown_item_1line, masterProductCodes.toList())
            setAdapter(adapter)
            threshold = 1
        }

        AlertDialog.Builder(this)
            .setTitle("参考情報を修正して追加")
            .setView(autoCompleteTextView)
            .setPositiveButton("追加") { _, _ ->
                val code = autoCompleteTextView.text.toString().trim()
                val cleaned = ProductCodeValidator.cleanProductCode(code)
                val validation = ProductCodeValidator.validate(cleaned)

                if (validation.isValid) {
                    val isInMaster = masterProductCodes.contains(cleaned)
                    val action = {
                        adapter.addItem(
                            RecognizedItem(
                                productCode = cleaned,
                                rawText = code,
                                isEdited = true,
                                isInMaster = isInMaster
                            )
                        )
                        updateResultCount()
                        updateEmptyState()
                        unmatchedItems.remove(fullText)
                        setupUnmatchedSection()
                    }

                    if (!isInMaster) {
                        AlertDialog.Builder(this)
                            .setTitle("マスター未登録の警告")
                            .setMessage("入力された製品コード「$cleaned」はマスターに登録されていません。\nこのまま追加しますか？")
                            .setPositiveButton("はい") { _, _ -> action() }
                            .setNegativeButton("いいえ", null)
                            .show()
                    } else {
                        action()
                    }
                } else {
                    AlertDialog.Builder(this)
                        .setTitle("入力エラー")
                        .setMessage(validation.message)
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun updateResultCount() {
        val count = adapter.itemCount
        binding.tvResultCount.text = "${count}件の製品コードを認識しました"
    }

    private fun updateEmptyState() {
        val isEmpty = adapter.itemCount == 0
        binding.rvResults.visibility  = if (isEmpty) View.GONE else View.VISIBLE
        binding.emptyLayout.visibility = if (isEmpty) View.VISIBLE else View.GONE
    }

    private fun updateRegisterButton() {
        binding.btnRegister.isEnabled = adapter.getSelectedItems().isNotEmpty()
    }

    private fun navigateToRegister(items: List<RecognizedItem>) {
        val intent = Intent(this, RegisterActivity::class.java).apply {
            putExtra("USER_ID", userId)
            putExtra("CONSTRUCTION_NAME", constructionName)
            putExtra("PROCESS_NAME", processName)
            putStringArrayListExtra(
                "PRODUCT_CODES",
                ArrayList(items.map { it.productCode })
            )
        }
        startActivity(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        overlayBitmap?.recycle()
        overlayBitmap = null
    }
}
