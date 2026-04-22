package com.crossvision.f

import android.app.Application
import androidx.work.Configuration
import androidx.work.WorkManager
import com.crossvision.f.sync.SyncWorker

/**
 * アプリケーションクラス
 * Room DB初期化やWorkManager設定などグローバルな初期化処理を行う
 */
class CrossVisionApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // 定期同期ジョブのスケジュール開始
        SyncWorker.schedulePeriodicSync(this)
    }
}
