package com.crossvision.f.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * 暗号化された SharedPreferences (EncryptedSharedPreferences) を使用して
 * ログインセッション（ユーザーID、パスワードなど）を安全に保持・管理するクラス。
 */
class SessionManager(private val context: Context) {

    companion object {
        private const val PREF_NAME = "secure_prefs"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_PASSWORD = "password"
        private const val KEY_USER_NAME = "user_name"
        private const val KEY_IS_LOGGED_IN = "is_logged_in"
        private const val KEY_LAST_ACTIVITY_TIME = "last_activity_time"
    }

    private val sharedPreferences: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context,
            PREF_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    /**
     * ログインセッションを保存する
     */
    fun saveSession(userId: String, password: String, userName: String) {
        sharedPreferences.edit().apply {
            putString(KEY_USER_ID, userId)
            putString(KEY_PASSWORD, password)
            putString(KEY_USER_NAME, userName)
            putBoolean(KEY_IS_LOGGED_IN, true)
            putLong(KEY_LAST_ACTIVITY_TIME, System.currentTimeMillis())
            apply()
        }
    }

    /**
     * ログイン中かどうか
     */
    fun isLoggedIn(): Boolean {
        return sharedPreferences.getBoolean(KEY_IS_LOGGED_IN, false)
    }

    /**
     * 保存されているユーザーIDを取得
     */
    fun getUserId(): String? {
        return sharedPreferences.getString(KEY_USER_ID, null)
    }

    /**
     * 保存されているパスワードを取得
     */
    fun getPassword(): String? {
        return sharedPreferences.getString(KEY_PASSWORD, null)
    }

    /**
     * 保存されているユーザー名を取得
     */
    fun getUserName(): String? {
        return sharedPreferences.getString(KEY_USER_NAME, null)
    }

    /**
     * セッションをクリア（ログアウト時など）
     */
    fun clearSession() {
        sharedPreferences.edit().apply {
            remove(KEY_USER_ID)
            remove(KEY_PASSWORD)
            remove(KEY_USER_NAME)
            putBoolean(KEY_IS_LOGGED_IN, false)
            remove(KEY_LAST_ACTIVITY_TIME)
            apply()
        }
    }

    /**
     * 最終操作時刻を現在時刻に更新する
     */
    fun updateLastActivityTime() {
        if (isLoggedIn()) {
            sharedPreferences.edit().putLong(KEY_LAST_ACTIVITY_TIME, System.currentTimeMillis()).apply()
        }
    }

    /**
     * 指定されたタイムアウト時間（ミリ秒）を経過しているか判定する
     */
    fun isSessionExpired(timeoutMs: Long): Boolean {
        if (!isLoggedIn()) return false
        val lastTime = sharedPreferences.getLong(KEY_LAST_ACTIVITY_TIME, 0L)
        if (lastTime == 0L) return false // 未保存の場合は無効にしない
        return (System.currentTimeMillis() - lastTime) > timeoutMs
    }
}
