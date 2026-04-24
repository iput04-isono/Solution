package com.crossvision.f.ui.register

import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.crossvision.f.R
import com.crossvision.f.data.model.Registration
import com.crossvision.f.data.model.SyncStatus
import com.crossvision.f.data.repository.AppRepository
import com.crossvision.f.databinding.ActivityRegisterBinding
import com.crossvision.f.sync.SyncManager
import com.crossvision.f.sync.SyncWorker
import com.google.android.material.chip.Chip
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch

/**
 * 登録画面
 * UI設計書 1.6.1 登録画面に準拠
 */
class RegisterActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRegisterBinding
    private lateinit var repository: AppRepository
    private lateinit var syncManager: SyncManager
    private var productCodes = listOf<String>()
    private var constructionName = ""
    private var processName = ""
    private var userId = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRegisterBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repository = AppRepository(applicationContext)
        syncManager = SyncManager(applicationContext)

        userId = intent.getStringExtra("USER_ID") ?: ""
        constructionName = intent.getStringExtra("CONSTRUCTION_NAME") ?: ""
        processName = intent.getStringExtra("PROCESS_NAME") ?: ""
        productCodes = intent.getStringArrayListExtra("PRODUCT_CODES") ?: arrayListOf()

        setupToolbar()
        setupUI()
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener { finish() }
    }

    private fun setupUI() {
        // 製品コード一覧を動的に追加
        productCodes.forEachIndexed { index, code ->
            val codeView = TextView(this).apply {
                text = "${index + 1}. $code"
                textSize = 16f
                setTextColor(getColor(R.color.text_primary))
                setPadding(0, 8, 0, 8)
            }
            binding.llProductCodes.addView(codeView)
        }

        // 工事・工程情報の表示
        binding.tvConstructionName.text = "工事名: $constructionName"
        binding.tvProcessName.text = "工程名: $processName"

        // 登録ボタン
        binding.btnRegister.setOnClickListener {
            performRegistration()
        }
    }

    private fun performRegistration() {

        binding.btnRegister.isEnabled = false
        binding.progressRegister.visibility = android.view.View.VISIBLE

        lifecycleScope.launch {
            try {
                val isOnline = syncManager.isNetworkAvailable()
                val initialStatus = if (isOnline) SyncStatus.PENDING else SyncStatus.PENDING

                // 各製品コードを登録
                val registrations = productCodes.map { code ->
                    Registration(
                        productCode = code,
                        constructionName = constructionName,
                        processName = processName,
                        warehouseNo = "",
                        columnNo = "",
                        tierNo = "",
                        syncStatus = initialStatus,
                        userId = userId
                    )
                }

                repository.insertRegistrations(registrations)
                
                // オンラインなら即座に同期を試行
                if (isOnline) {
                    val syncedCount = syncManager.syncPendingRegistrations()
                    if (syncedCount == productCodes.size) {
                        showSuccessMessage("登録完了：${productCodes.size}件\nサーバーへの送信も正常に完了しました。")
                    } else if (syncedCount > 0) {
                        showSuccessMessage("一部送信完了：${syncedCount}/${productCodes.size}件\n残りのデータは通信環境が改善し次第、自動的に再送されます。")
                    } else {
                        showSuccessMessage("ローカル保存完了：${productCodes.size}件\nサーバーへの送信に失敗しました。データは安全に保存されており、後で自動的に再送されます。")
                    }
                } else {
                    showSuccessMessage("オフライン保存：${productCodes.size}件\n現在は通信できないため、端末内に保存しました。ネットワーク復帰時に自動で送信されます。")
                }

            } catch (e: Exception) {
                Snackbar.make(binding.root, "登録に失敗しました: ${e.message}", Snackbar.LENGTH_LONG)
                    .show()
            } finally {
                binding.btnRegister.isEnabled = true
                binding.progressRegister.visibility = android.view.View.GONE
            }
        }
    }

    private fun showSuccessMessage(message: String) {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("登録状況")
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton("OK") { _, _ ->
                val intent = android.content.Intent(this, com.crossvision.f.ui.process.ProcessSelectionActivity::class.java)
                intent.flags = android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
                startActivity(intent)
                finish()
            }
            .show()
    }
}
