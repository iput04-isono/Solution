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

    /**
     * サーバーから最新のマスタデータを取得して同期する
     */
    fun syncMasterData() {
        _isSyncing.value = true
        viewModelScope.launch {
            try {
                // 工事・工程の同期
                syncManager.syncConstructionsAndProcesses()
                // ついでに製品コードマスターも同期しておく
                syncManager.syncProductLabels()
            } catch (e: Exception) {
                // エラー時はログ出力のみ（オフライン等の場合があるため）
            } finally {
                _isSyncing.value = false
            }
        }
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
