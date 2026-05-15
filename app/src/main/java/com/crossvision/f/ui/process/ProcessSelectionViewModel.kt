package com.crossvision.f.ui.process

import androidx.lifecycle.*
import com.crossvision.f.data.model.Construction
import com.crossvision.f.data.model.Process
import com.crossvision.f.data.repository.AppRepository
import com.crossvision.f.sync.SyncManager
import kotlinx.coroutines.launch

/**
 * 工事・工程選択画面のViewModel
 */
/**
 * 工事・工程選択画面のViewModel
 */
class ProcessSelectionViewModel(
    private val repository: AppRepository,
    private val syncManager: SyncManager
) : ViewModel() {

    // 対象区分（製品、部品、置き場）
    private val _selectedCategory = MutableLiveData("product")
    val selectedCategory: LiveData<String> = _selectedCategory

    // 工事一覧
    val constructions: LiveData<List<Construction>> = repository.getAllConstructions()

    // 選択中の工事
    private val _selectedConstruction = MutableLiveData<Construction?>()
    val selectedConstruction: LiveData<Construction?> = _selectedConstruction

    // 工程一覧（工事に連動）
    val processes: LiveData<List<Process>> = _selectedConstruction.switchMap { construction ->
        if (construction != null) {
            repository.getProcessesByConstructionId(construction.id)
        } else {
            MutableLiveData(emptyList())
        }
    }

    // 選択中の工程
    private val _selectedProcess = MutableLiveData<Process?>()
    val selectedProcess: LiveData<Process?> = _selectedProcess

    // 「次へ」ボタンの有効/無効
    val isNextEnabled: LiveData<Boolean> = MediatorLiveData<Boolean>().apply {
        addSource(_selectedConstruction) { value = it != null && _selectedProcess.value != null }
        addSource(_selectedProcess) { value = _selectedConstruction.value != null && it != null }
    }

    // 同期状態の管理
    private val _isSyncing = MutableLiveData(false)
    val isSyncing: LiveData<Boolean> = _isSyncing

    // 同期メッセージ（Toast表示用など）
    private val _syncStatusMessage = MutableLiveData<String?>()
    val syncStatusMessage: LiveData<String?> = _syncStatusMessage

    /**
     * サーバーから最新のマスタデータを取得して同期する
     */
    fun syncMasterData() {
        _isSyncing.value = true
        viewModelScope.launch {
            try {
                if (!syncManager.isNetworkAvailable()) {
                    _syncStatusMessage.value = "ネットワークに接続されていません。オフラインモードで動作します。"
                    return@launch
                }

                // 工事・工程の同期
                val constSuccess = syncManager.syncConstructionsAndProcesses()
                // 製品コードマスターも同期
                val labelCount = syncManager.syncProductLabels()
                
                if (constSuccess) {
                    _syncStatusMessage.value = "最新データを取得しました。"
                } else {
                    _syncStatusMessage.value = "サーバーとの通信に失敗しました。以前のデータを使用します。"
                }
            } catch (e: Exception) {
                // エラー時はログ出力のみ（オフライン等の場合があるため）
                _syncStatusMessage.value = "同期中にエラーが発生しました。接続設定を確認してください。"
            } finally {
                _isSyncing.value = false
            }
        }
    }

    /**
     * メッセージ表示後にクリアするためのメソッド
     */
    fun clearSyncStatusMessage() {
        _syncStatusMessage.value = null
    }

    fun selectConstruction(construction: Construction) {
        _selectedConstruction.value = construction
        _selectedProcess.value = null // 工事変更時に工程選択をリセット
    }

    fun selectProcess(process: Process) {
        _selectedProcess.value = process
    }

    fun selectCategory(category: String) {
        if (_selectedCategory.value != category) {
            _selectedCategory.value = category
            _selectedConstruction.value = null // カテゴリ変更時に工事をリセット
            _selectedProcess.value = null // カテゴリ変更時に工程をリセット
        }
    }
}

class ProcessSelectionViewModelFactory(
    private val repository: AppRepository,
    private val syncManager: SyncManager
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ProcessSelectionViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return ProcessSelectionViewModel(repository, syncManager) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
