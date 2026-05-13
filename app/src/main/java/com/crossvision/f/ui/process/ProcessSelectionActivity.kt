package com.crossvision.f.ui.process

import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.crossvision.f.data.model.Construction
import com.crossvision.f.data.model.Process
import com.crossvision.f.data.repository.AppRepository
import com.crossvision.f.R
import com.crossvision.f.databinding.ActivityProcessSelectionBinding
import com.crossvision.f.ui.library.LibraryActivity
import com.crossvision.f.ui.recognize.RecognizeActivity
import com.google.android.material.button.MaterialButtonToggleGroup

/**
 * 工事・工程選択画面
 * UI設計書 1.2.1 工程選択画面に準拠
 */
class ProcessSelectionActivity : AppCompatActivity() {

    private lateinit var binding: ActivityProcessSelectionBinding
    private val viewModel: ProcessSelectionViewModel by viewModels {
        ProcessSelectionViewModelFactory(AppRepository(applicationContext))
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
                setProcessEnabled(false)
            }
        }

        // 工事選択
        binding.actvConstruction.setOnItemClickListener { _, _, position, _ ->
            val selected = constructionList[position]
            viewModel.selectConstruction(selected)
            binding.actvProcess.setText("", false)
            setProcessEnabled(true)
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
            android.widget.Toast.makeText(this, "マスターデータを再読み込みしています...", android.widget.Toast.LENGTH_SHORT).show()
            // UI状態のリセット
            binding.actvConstruction.setText("", false)
            binding.actvProcess.setText("", false)
            setProcessEnabled(false)
        }

        // ログアウトボタン
        binding.btnLogout.setOnClickListener {
            // ログイン画面へ戻る
            val intent = Intent(this, com.crossvision.f.ui.login.LoginActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
            finish()
        }
    }

    /**
     * 工程ドロップダウンの有効/無効を切り替える。
     * TextInputLayout だけでなく内部の AutoCompleteTextView も明示的に操作することで、
     * 再有効化後にクリックイベントが届かないバグを防ぐ。
     */
    private fun setProcessEnabled(enabled: Boolean) {
        binding.tilProcess.isEnabled = enabled
        binding.actvProcess.isEnabled = enabled
    }

    private fun observeViewModel() {
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
            val adapter = ArrayAdapter(
                this,
                android.R.layout.simple_dropdown_item_1line,
                processes.map { it.name }
            )
            binding.actvProcess.setAdapter(adapter)
            // setAdapter() が TextInputLayout の有効状態をリセットする場合があるため
            // 工事が選択済みであれば setAdapter 後に改めて有効化する
            setProcessEnabled(viewModel.selectedConstruction.value != null)
        }

        // 「次へ」ボタンの有効/無効
        viewModel.isNextEnabled.observe(this) { isEnabled ->
            binding.btnNext.isEnabled = isEnabled
        }
    }
}
