package com.crossvision.f.ui.confirm

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.crossvision.f.databinding.ActivityConfirmBinding
import com.crossvision.f.ocr.ProductCodeValidator
import com.crossvision.f.ui.register.RegisterActivity
import com.crossvision.f.ui.process.ProcessSelectionActivity
import androidx.activity.result.contract.ActivityResultContracts
import java.io.File

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
        val cropPaths       = intent.getStringArrayListExtra("CROP_PATHS") ?: arrayListOf()
        val debugInfo       = intent.getStringArrayListExtra("DEBUG_INFO") ?: arrayListOf()
        val unmatchedTexts  = intent.getStringArrayListExtra("UNMATCHED_TEXTS") ?: arrayListOf()
        val overlayPath     = intent.getStringExtra("OVERLAY_IMAGE_PATH")

        setupToolbar()
        setupOverlayImage(overlayPath)
        setupRecyclerView(productCodes, rawTexts, debugInfo, cropPaths)
        setupUnmatchedSection(unmatchedTexts)
        updateProcessInfoUI()
        setupUI()
    }

    private fun updateProcessInfoUI() {
        val displayConst = if (constructionName.isNotEmpty()) constructionName else "未指定"
        val displayProc = if (processName.isNotEmpty()) processName else "未指定"
        binding.tvProcessInfo.text = "【$displayConst】$displayProc"
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener { finish() }
    }

    private fun setupOverlayImage(path: String?) {
        if (path == null) return
        val file = File(path)
        if (!file.exists()) return
        val bitmap = BitmapFactory.decodeFile(path) ?: return
        binding.ivOverlay.setImageBitmap(bitmap)
        binding.ivOverlay.visibility = View.VISIBLE
    }

    private fun setupUnmatchedSection(unmatchedTexts: List<String>) {
        if (unmatchedTexts.isEmpty()) return
        binding.unmatchedSection.visibility = View.VISIBLE
        val container = binding.unmatchedList
        container.removeAllViews()
        val dp8 = (8 * resources.displayMetrics.density).toInt()
        val dp4 = (4 * resources.displayMetrics.density).toInt()
        unmatchedTexts.forEach { text ->
            val tv = TextView(this).apply {
                this.text = "・$text"
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
            val displayRaw = if (debug.isNotEmpty()) "$raw\n[$debug]" else raw
            val cropPath = cropPaths.getOrElse(index) { "" }.ifEmpty { null }
            RecognizedItem(
                productCode = code,
                rawText = displayRaw,
                cropImagePath = cropPath
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
        val editText = EditText(this).apply {
            setText(currentItem.productCode)
            setPadding(48, 32, 48, 32)
        }

        AlertDialog.Builder(this)
            .setTitle("製品コードを編集")
            .setView(editText)
            .setPositiveButton("保存") { _, _ ->
                val newCode = editText.text.toString().trim()
                val validation = ProductCodeValidator.validate(newCode)
                if (validation.isValid) {
                    adapter.updateItem(position, newCode)
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
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun showAddDialog() {
        val editText = EditText(this).apply {
            hint = "製品コードを入力"
            setPadding(48, 32, 48, 32)
        }

        AlertDialog.Builder(this)
            .setTitle("製品コードを手動追加")
            .setView(editText)
            .setPositiveButton("追加") { _, _ ->
                val code = editText.text.toString().trim()
                val cleaned = ProductCodeValidator.cleanProductCode(code)
                val validation = ProductCodeValidator.validate(cleaned)

                if (validation.isValid) {
                    adapter.addItem(
                        RecognizedItem(
                            productCode = cleaned,
                            rawText = code,
                            isEdited = true
                        )
                    )
                    updateResultCount()
                    updateEmptyState()
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
}
