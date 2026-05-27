package com.crossvision.f.ui.login

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.crossvision.f.data.local.SessionManager
import com.crossvision.f.data.repository.AppRepository
import com.crossvision.f.databinding.ActivityLoginBinding
import com.crossvision.f.sync.SyncWorker
import com.crossvision.f.ui.process.ProcessSelectionActivity

/**
 * ログイン画面
 * UI設計書 1.1.1 ログイン画面に準拠
 */
class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private lateinit var sessionManager: SessionManager
    private val viewModel: LoginViewModel by viewModels {
        LoginViewModelFactory(AppRepository(applicationContext))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sessionManager = SessionManager(applicationContext)

        if (intent.getBooleanExtra("SESSION_EXPIRED", false)) {
            android.widget.Toast.makeText(this, "一定時間操作がなかったため、自動的にログアウトしました", android.widget.Toast.LENGTH_LONG).show()
        }

        setupUI()
        observeViewModel()

        // バックグラウンド同期のスケジュール開始
        SyncWorker.schedulePeriodicSync(applicationContext)
    }

    private fun setupUI() {
        // ログインボタン
        binding.btnLogin.setOnClickListener {
            performLogin()
        }

        // パスワードフィールドでEnterキー押下時にログイン実行
        binding.etPassword.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                performLogin()
                true
            } else false
        }
    }

    private fun performLogin() {
        val userId = binding.etUserId.text?.toString()?.trim() ?: ""
        val password = binding.etPassword.text?.toString() ?: ""
        viewModel.login(userId, password)
    }

    private fun observeViewModel() {
        // ログイン結果の監視
        viewModel.loginResult.observe(this) { result ->
            when (result) {
                is LoginResult.Success -> {
                    binding.tvError.visibility = View.GONE
                    // セッションを暗号化保存
                    sessionManager.saveSession(
                        result.user.userId,
                        result.user.password,
                        result.user.displayName
                    )
                    // 工事・工程選択画面へ遷移
                    val intent = Intent(this, ProcessSelectionActivity::class.java).apply {
                        putExtra("USER_ID", result.user.userId)
                        putExtra("USER_NAME", result.user.displayName)
                    }
                    startActivity(intent)
                    finish()
                }

                is LoginResult.Error -> {
                    binding.tvError.text = result.message
                    binding.tvError.visibility = View.VISIBLE
                    // ログイン失敗時は念のためセッションをクリア
                    sessionManager.clearSession()
                }
            }
        }

        // ローディング状態の監視
        viewModel.isLoading.observe(this) { isLoading ->
            binding.progressLogin.visibility = if (isLoading) View.VISIBLE else View.GONE
            binding.btnLogin.isEnabled = !isLoading
        }
    }
}
