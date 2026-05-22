package com.crossvision.f.ui.process

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.crossvision.f.data.model.Construction
import com.crossvision.f.data.model.Process
import com.crossvision.f.data.repository.AppRepository
import com.crossvision.f.R
import com.crossvision.f.databinding.ActivityProcessSelectionBinding
import com.crossvision.f.sync.SyncManager
import com.crossvision.f.ui.library.LibraryActivity
import com.crossvision.f.ui.recognize.RecognizeActivity

/**
 * 工事・工程選択画面
 * UI設計書 1.2.1 工程選択画面に準拠
 */
class ProcessSelectionActivity : AppCompatActivity() {

    private lateinit var binding: ActivityProcessSelectionBinding
    private val viewModel: ProcessSelectionViewModel by viewModels {
        ProcessSelectionViewModelFactory(
            AppRepository(applicationContext),
            SyncManager(applicationContext)
        )
    }

    private var userId: String = ""
    private var isEditMode: Boolean = false
    private var constructionList = listOf<Construction>()
    private var processList = listOf<Process>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProcessSelectionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        userId = intent.getStringExtra("USER_ID") ?: ""
        isEditMode = intent.getBooleanExtra("EDIT_MODE", false)

        setupToolbar()
        setupUI()
        observeViewModel()

        // 起動時にマスタデータを同期
        viewModel.syncMasterData()

        // 自動同期タスクのスケジュール開始
        com.crossvision.f.sync.SyncWorker.schedulePeriodicSync(applicationContext)
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener {
            finish()
        }
    }

    private fun setupUI() {
        // 対象区分切り替え
        binding.toggleCategory.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                val category = when (checkedId) {
                    R.id.btnCategoryProduct -> "product"
                    R.id.btnCategoryPart -> "part"
                    else -> "product"
                }
                viewModel.selectCategory(category)
                // UI状態のリセット
                binding.actvConstruction.setText("", false)
                binding.actvProcess.setText("", false)
                binding.tilProcess.isEnabled = false
            }
        }

        // 工事選択
        binding.actvConstruction.setOnItemClickListener { _, _, position, _ ->
            val selected = constructionList[position]
            viewModel.selectConstruction(selected)
            binding.actvProcess.setText("", false)
            binding.tilProcess.isEnabled = true
        }

        // 工程選択
        binding.actvProcess.setOnItemClickListener { _, _, position, _ ->
            val selected = processList[position]
            viewModel.selectProcess(selected)
        }

        // 次へボタン / 変更を保存ボタン
        if (isEditMode) {
            binding.btnNext.text = "変更を保存"
        }
        
        binding.btnNext.setOnClickListener {
            val construction = viewModel.selectedConstruction.value
            val process = viewModel.selectedProcess.value
            if (construction != null && process != null) {
                if (isEditMode) {
                    // 編集モード：結果を呼び出し元（ConfirmActivity）に返して終了
                    val resultIntent = Intent().apply {
                        putExtra("CONSTRUCTION_NAME", construction.name)
                        putExtra("PROCESS_NAME", process.name)
                    }
                    setResult(RESULT_OK, resultIntent)
                    finish()
                } else {
                    // 通常モード：カメラ起動
                    val intent = Intent(this, RecognizeActivity::class.java).apply {
                        putExtra("USER_ID", userId)
                        putExtra("CATEGORY", viewModel.selectedCategory.value)
                        putExtra("CONSTRUCTION_NAME", construction.name)
                        putExtra("PROCESS_NAME", process.name)
                    }
                    startActivity(intent)
                }
            }
        }

        // 登録履歴ボタン
        binding.btnHistory.setOnClickListener {
            startActivity(Intent(this, LibraryActivity::class.java))
        }

        // 再読み込みボタン
        binding.btnReload.setOnClickListener {
            viewModel.syncMasterData()
            // UI状態のリセット
            binding.actvConstruction.setText("", false)
            binding.actvProcess.setText("", false)
            binding.tilProcess.isEnabled = false
        }

        // ログアウトボタン
        binding.btnLogout.setOnClickListener {
            // 暗号化されたセッション情報をクリア
            com.crossvision.f.data.local.SessionManager(applicationContext).clearSession()
            // ログイン画面へ戻る
            val intent = Intent(this, com.crossvision.f.ui.login.LoginActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
            finish()
        }
    }

    private fun observeViewModel() {
        // 同期状態の反映
        viewModel.isSyncing.observe(this) { isSyncing ->
            binding.progressSync.visibility = if (isSyncing) View.VISIBLE else View.GONE
            binding.btnReload.isEnabled = !isSyncing
        }

        // 同期メッセージの表示
        viewModel.syncStatusMessage.observe(this) { message ->
            if (message != null) {
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                viewModel.clearSyncStatusMessage()
            }
        }

        // 工事一覧の反映
        viewModel.constructions.observe(this) { constructions ->
            constructionList = constructions
            val adapter = ArrayAdapter(
                this,
                android.R.layout.simple_dropdown_item_1line,
                constructions.map { it.name }
            )
            binding.actvConstruction.setAdapter(adapter)
        }

        // 工程一覧の反映（工事に連動）
        viewModel.processes.observe(this) { processes ->
            processList = processes
            if (processes.isEmpty() && viewModel.selectedConstruction.value != null) {
                Toast.makeText(this, "この工事に紐付く工程がありません。サーバーの設定を確認してください。", Toast.LENGTH_LONG).show()
            }
            val adapter = ArrayAdapter(
                this,
                android.R.layout.simple_dropdown_item_1line,
                processes.map { it.name }
            )
            binding.actvProcess.setAdapter(adapter)
        }

        // 「次へ」ボタンの有効/無効
        viewModel.isNextEnabled.observe(this) { isEnabled ->
            binding.btnNext.isEnabled = isEnabled
        }
    }
}
