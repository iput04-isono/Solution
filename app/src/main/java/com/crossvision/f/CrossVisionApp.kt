package com.crossvision.f

import android.app.Application
import android.util.Log
import androidx.work.Configuration
import androidx.work.WorkManager

/**
 * アプリケーションクラス
 * Room DB初期化やWorkManager設定などグローバルな初期化処理を行う
 */
class CrossVisionApp : Application() {

    private lateinit var nsdHelper: com.crossvision.f.sync.NsdHelper

    override fun onCreate() {
        super.onCreate()
        
        // サーバー自動発見の開始
        nsdHelper = com.crossvision.f.sync.NsdHelper(this)
        nsdHelper.onServerFound = { host, port ->
            val url = "http://$host:$port/"
            Log.i("CrossVisionApp", "サーバーを発見しました: $url")
            com.crossvision.f.data.api.RetrofitClient.serverBaseUrl = url
        }
        nsdHelper.startDiscovery()

        registerSessionTimeoutObserver()
    }

    private fun registerSessionTimeoutObserver() {
        registerActivityLifecycleCallbacks(object : android.app.Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: android.app.Activity) {
                if (activity !is com.crossvision.f.ui.login.LoginActivity) {
                    val sessionManager = com.crossvision.f.data.local.SessionManager(this@CrossVisionApp)
                    // 本番用: 3時間 (3 * 60 * 60 * 1000)
                    val timeoutMs = 3L * 60L * 60L * 1000L 
                    
                    if (sessionManager.isSessionExpired(timeoutMs)) {
                        Log.i("CrossVisionApp", "セッションタイムアウトのためログアウトします")
                        sessionManager.clearSession()
                        val intent = android.content.Intent(activity, com.crossvision.f.ui.login.LoginActivity::class.java).apply {
                            flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
                            putExtra("SESSION_EXPIRED", true)
                        }
                        activity.startActivity(intent)
                    } else {
                        sessionManager.updateLastActivityTime()
                    }
                }
            }

            override fun onActivityPaused(activity: android.app.Activity) {
                if (activity !is com.crossvision.f.ui.login.LoginActivity) {
                    com.crossvision.f.data.local.SessionManager(this@CrossVisionApp).updateLastActivityTime()
                }
            }

            override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: android.os.Bundle?) {}
            override fun onActivityStarted(activity: android.app.Activity) {}
            override fun onActivityStopped(activity: android.app.Activity) {}
            override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: android.os.Bundle) {}
            override fun onActivityDestroyed(activity: android.app.Activity) {}
        })
    }

    override fun onTerminate() {
        super.onTerminate()
        nsdHelper.stopDiscovery()
    }
}
