package com.crossvision.f.ui.confirm

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.crossvision.f.databinding.ActivityConfirmBinding
import com.crossvision.f.ocr.ProductCodeValidator
import com.crossvision.f.ui.register.RegisterActivity

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityConfirmBinding.inflate(layoutInflater)
        setContentView(binding.root)

        userId = intent.getStringExtra("USER_ID") ?: ""
        constructionName = intent.getStringExtra("CONSTRUCTION_NAME") ?: ""
        processName = intent.getStringExtra("PROCESS_NAME") ?: ""

        val productCodes = intent.getStringArrayListExtra("PRODUCT_CODES") ?: arrayListOf()
        val rawTexts = intent.getStringArrayListExtra("RAW_TEXTS") ?: arrayListOf()
        val candidates = intent.getStringArrayListExtra("CANDIDATES") ?: arrayListOf()
        val imagePath = intent.getStringExtra("IMAGE_PATH")

        setupToolbar()
        showCapturedImage(imagePath)
        setupRecyclerView(productCodes, rawTexts, candidates)
        setupUI()
    }

    private fun showCapturedImage(path: String?) {
        if (path.isNullOrEmpty()) return
        
        try {
            val bitmap = android.graphics.BitmapFactory.decodeFile(path)
            if (bitmap != null) {
                binding.ivCapturedImage.setImageBitmap(bitmap)
                binding.cardImagePreview.visibility = View.VISIBLE
            }
        } catch (e: Exception) {
            android.util.Log.e("ConfirmActivity", "画像の読み込みに失敗: ${e.message}")
        }
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener { finish() }
    }

    private fun setupRecyclerView(codes: List<String>, rawTexts: List<String>, candidatesList: List<String>) {
        adapter = RecognizedProductAdapter(
            onEditClick = { position -> showEditDialog(position) },
            onDeleteClick = { position -> showDeleteDialog(position) },
            onSelectionChanged = { _, _ -> updateRegisterButton() }
        )

        binding.rvResults.layoutManager = LinearLayoutManager(this)
        binding.rvResults.adapter = adapter

        val items = codes.mapIndexed { index, code ->
            val candidates = candidatesList.getOrNull(index)?.split("|")?.filter { it.isNotEmpty() } ?: emptyList()
            RecognizedItem(
                productCode = code,
                rawText = rawTexts.getOrElse(index) { code },
                candidates = candidates
            )
        }
        adapter.setItems(items)

        updateResultCount()
        updateEmptyState()
    }

    private fun setupUI() {
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
        binding.rvResults.visibility = if (isEmpty) View.GONE else View.VISIBLE
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
