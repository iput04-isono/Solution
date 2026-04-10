package com.example.mainproject

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.example.mainproject.data.db.AppDatabase
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // --- ここから追加：データベースを触ってみる ---
        lifecycleScope.launch {
            // インスタンスを取得するだけで、DBファイルが作成され、Inspectorに表示されます
            val db = AppDatabase.getInstance(applicationContext)
            // 試しにデータを取得（空でもOK）
            db.registrationDao().getPendingRegistrations()
        }
        // --- ここまで ---
    }
}