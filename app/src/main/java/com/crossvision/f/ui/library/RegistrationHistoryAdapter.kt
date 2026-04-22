package com.crossvision.f.ui.library

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.crossvision.f.R
import com.crossvision.f.data.model.Registration
import com.crossvision.f.data.model.SyncStatus
import com.crossvision.f.databinding.ItemRegistrationHistoryBinding
import java.text.SimpleDateFormat
import java.util.*

/**
 * 登録履歴リストのアダプター
 */
class RegistrationHistoryAdapter :
    ListAdapter<Registration, RegistrationHistoryAdapter.ViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemRegistrationHistoryBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(
        private val binding: ItemRegistrationHistoryBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        private val dateFormat = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.JAPAN)

        fun bind(registration: Registration) {
            val context = binding.root.context

            binding.tvProductCode.text = registration.productCode
            binding.tvConstructionProcess.text =
                "${registration.constructionName} / ${registration.processName}"

            // 倉庫位置情報
            val locationParts = mutableListOf<String>()
            if (registration.warehouseNo.isNotBlank()) locationParts.add("倉庫: ${registration.warehouseNo}")
            if (registration.columnNo.isNotBlank()) locationParts.add("列: ${registration.columnNo}")
            if (registration.tierNo.isNotBlank()) locationParts.add("段: ${registration.tierNo}")
            binding.tvLocation.text = locationParts.joinToString(" / ")

            // 日時
            binding.tvDate.text = dateFormat.format(Date(registration.registeredAt))

            // 同期ステータスバッジ
            when (registration.syncStatus) {
                SyncStatus.PENDING -> {
                    binding.tvSyncStatus.text = "未送信"
                    binding.tvSyncStatus.setBackgroundColor(
                        ContextCompat.getColor(context, R.color.status_pending)
                    )
                }
                SyncStatus.SYNCING -> {
                    binding.tvSyncStatus.text = "送信中"
                    binding.tvSyncStatus.setBackgroundColor(
                        ContextCompat.getColor(context, R.color.status_syncing)
                    )
                }
                SyncStatus.SYNCED -> {
                    binding.tvSyncStatus.text = "送信済"
                    binding.tvSyncStatus.setBackgroundColor(
                        ContextCompat.getColor(context, R.color.status_synced)
                    )
                }
                SyncStatus.FAILED -> {
                    binding.tvSyncStatus.text = "失敗"
                    binding.tvSyncStatus.setBackgroundColor(
                        ContextCompat.getColor(context, R.color.status_failed)
                    )
                }
            }
        }
    }

    private class DiffCallback : DiffUtil.ItemCallback<Registration>() {
        override fun areItemsTheSame(oldItem: Registration, newItem: Registration): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: Registration, newItem: Registration): Boolean =
            oldItem == newItem
    }
}
