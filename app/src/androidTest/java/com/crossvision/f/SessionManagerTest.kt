package com.crossvision.f

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.crossvision.f.data.local.SessionManager
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * EncryptedSharedPreferences (暗号化共有設定) を用いたセッション情報の
 * 保存、読み込み、削除が正常に行えるかをテストするクラス。
 */
@RunWith(AndroidJUnit4::class)
class SessionManagerTest {

    private lateinit var context: Context
    private lateinit var sessionManager: SessionManager

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        sessionManager = SessionManager(context)
        sessionManager.clearSession()
    }

    @Test
    fun testSaveAndReadSession() {
        // 初期状態ではログインしていないこと
        assertFalse("初期状態ではログイン状態が false であること", sessionManager.isLoggedIn())
        assertNull("初期状態ではユーザーIDが null であること", sessionManager.getUserId())
        assertNull("初期状態ではパスワードが null であること", sessionManager.getPassword())
        assertNull("初期状態ではユーザー名が null であること", sessionManager.getUserName())

        // セッションを暗号化保存
        sessionManager.saveSession("test_user_id", "test_secure_password", "テスト作業員")

        // 暗号化したデータが正しく復号されて取得できること
        assertTrue("保存後はログイン状態が true になること", sessionManager.isLoggedIn())
        assertEquals("保存したユーザーIDと一致すること", "test_user_id", sessionManager.getUserId())
        assertEquals("保存したパスワードと一致すること", "test_secure_password", sessionManager.getPassword())
        assertEquals("保存したユーザー名と一致すること", "テスト作業員", sessionManager.getUserName())
    }

    @Test
    fun testClearSession() {
        // データを保存
        sessionManager.saveSession("test_user_id", "test_secure_password", "テスト作業員")
        assertTrue("保存後にログイン状態が true になっていること", sessionManager.isLoggedIn())

        // セッションをクリア
        sessionManager.clearSession()

        // クリア後にすべてのセッション情報が消去され、ログイン状態が false に戻ること
        assertFalse("クリア後はログイン状態が false になること", sessionManager.isLoggedIn())
        assertNull("クリア後はユーザーIDが null になること", sessionManager.getUserId())
        assertNull("クリア後はパスワードが null になること", sessionManager.getPassword())
        assertNull("クリア後はユーザー名が null になること", sessionManager.getUserName())
    }
}
