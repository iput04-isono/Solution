package com.crossvision.f

import android.app.Application
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
            android.util.Log.i("CrossVisionApp", "サーバーを発見しました: $url")
            com.crossvision.f.data.api.RetrofitClient.serverBaseUrl = url
        }
        nsdHelper.startDiscovery()
    }

    override fun onTerminate() {
        super.onTerminate()
        nsdHelper.stopDiscovery()
    }
}
