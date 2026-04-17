package com.crossvision.f

import android.app.Application
import androidx.work.Configuration
import androidx.work.WorkManager

/**
 * アプリケーションクラス
 * Room DB初期化やWorkManager設定などグローバルな初期化処理を行う
 */
class CrossVisionApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // WorkManagerはデフォルトの自動初期化を使用
    }
}
