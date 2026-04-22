package com.crossvision.f.ui.login

import androidx.lifecycle.*
import com.crossvision.f.data.model.User
import com.crossvision.f.data.repository.AppRepository
import kotlinx.coroutines.launch

/**
 * ログイン画面のViewModel
 */
class LoginViewModel(private val repository: AppRepository) : ViewModel() {

    private val _loginResult = MutableLiveData<LoginResult>()
    val loginResult: LiveData<LoginResult> = _loginResult

    private val _isLoading = MutableLiveData(false)
    val isLoading: LiveData<Boolean> = _isLoading

    /**
     * ログイン認証を実行
     */
    fun login(userId: String, password: String) {
        // 入力バリデーション
        if (userId.isBlank() && password.isBlank()) {
            _loginResult.value = LoginResult.Error("ユーザーIDとパスワードを入力してください")
            return
        }
        if (userId.isBlank()) {
            _loginResult.value = LoginResult.Error("ユーザーIDを入力してください")
            return
        }
        if (password.isBlank()) {
            _loginResult.value = LoginResult.Error("パスワードを入力してください")
            return
        }

        _isLoading.value = true

        viewModelScope.launch {
            try {
                val user = repository.authenticate(userId, password)
                if (user != null) {
                    _loginResult.value = LoginResult.Success(user)
                } else {
                    _loginResult.value = LoginResult.Error("ユーザーIDもしくはパスワードが違います")
                }
            } catch (e: Exception) {
                _loginResult.value = LoginResult.Error("エラーが発生しました: ${e.message}")
            } finally {
                _isLoading.value = false
            }
        }
    }
}

/**
 * ログイン結果
 */
sealed class LoginResult {
    data class Success(val user: User) : LoginResult()
    data class Error(val message: String) : LoginResult()
}

/**
 * LoginViewModelのFactoryクラス
 */
class LoginViewModelFactory(private val repository: AppRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(LoginViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return LoginViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
