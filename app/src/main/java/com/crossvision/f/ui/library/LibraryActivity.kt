package com.crossvision.f.ui.library

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.crossvision.f.data.repository.AppRepository
import com.crossvision.f.databinding.ActivityLibraryBinding
import com.crossvision.f.sync.SyncManager
import com.crossvision.f.sync.SyncWorker
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch

/**
 * 登録履歴画面
 * UI設計書 1.7.1 登録履歴画面に準拠
 */
class LibraryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLibraryBinding
    private lateinit var repository: AppRepository
    private lateinit var syncManager: SyncManager
    private lateinit var adapter: RegistrationHistoryAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLibraryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repository = AppRepository(applicationContext)
        syncManager = SyncManager(applicationContext)

        setupToolbar()
        setupRecyclerView()
        setupSearch()
        setupSync()
        observeData()
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener { finish() }
    }

    private fun setupRecyclerView() {
        adapter = RegistrationHistoryAdapter()
        binding.rvHistory.layoutManager = LinearLayoutManager(this)
        binding.rvHistory.adapter = adapter
    }

    private fun setupSearch() {
        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString()?.trim() ?: ""
                if (query.isNotEmpty()) {
                    repository.searchRegistrations(query).observe(this@LibraryActivity) { results ->
                        adapter.submitList(results)
                        updateEmptyState(results.isEmpty())
                    }
                } else {
                    observeAllData()
                }
            }
        })
    }

    private fun setupSync() {
        binding.btnSync.setOnClickListener {
            performSync()
        }
    }

    private fun observeData() {
        observeAllData()

        // 未同期データ数の監視
        repository.getUnsyncedCount().observe(this) { count ->
            if (count > 0) {
                binding.syncStatusBar.visibility = View.VISIBLE
                binding.tvSyncStatus.text = "${count}件の未送信データがあります"
            } else {
                binding.syncStatusBar.visibility = View.GONE
            }
        }
    }

    private fun observeAllData() {
        repository.getAllRegistrations().observe(this) { registrations ->
            adapter.submitList(registrations)
            updateEmptyState(registrations.isEmpty())
        }
    }

    private fun updateEmptyState(isEmpty: Boolean) {
        binding.rvHistory.visibility = if (isEmpty) View.GONE else View.VISIBLE
        binding.emptyLayout.visibility = if (isEmpty) View.VISIBLE else View.GONE
    }

    private fun performSync() {
        if (!syncManager.isNetworkAvailable()) {
            Snackbar.make(binding.root, "ネットワークに接続されていません", Snackbar.LENGTH_SHORT).show()
            return
        }

        binding.btnSync.isEnabled = false
        binding.tvSyncStatus.text = "同期中..."

        lifecycleScope.launch {
            try {
                val count = syncManager.syncPendingRegistrations()
                Snackbar.make(
                    binding.root,
                    "同期が完了しました（${count}件）",
                    Snackbar.LENGTH_SHORT
                ).show()
            } catch (e: Exception) {
                Snackbar.make(
                    binding.root,
                    "同期に失敗しました: ${e.message}",
                    Snackbar.LENGTH_LONG
                ).show()
            } finally {
                binding.btnSync.isEnabled = true
            }
        }
    }
}
