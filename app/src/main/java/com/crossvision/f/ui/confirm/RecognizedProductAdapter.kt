package com.crossvision.f.ui.confirm

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.crossvision.f.databinding.ItemRecognizedProductBinding

/**
 * 認識結果リストのアダプター
 */
class RecognizedProductAdapter(
    private val onEditClick: (Int) -> Unit,
    private val onDeleteClick: (Int) -> Unit,
    private val onSelectionChanged: (Int, Boolean) -> Unit
) : RecyclerView.Adapter<RecognizedProductAdapter.ViewHolder>() {

    private val items = mutableListOf<RecognizedItem>()

    fun setItems(newItems: List<RecognizedItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun getItems(): List<RecognizedItem> = items.toList()

    fun getSelectedItems(): List<RecognizedItem> = items.filter { it.isSelected }

    fun updateItem(position: Int, code: String) {
        if (position in items.indices) {
            items[position] = items[position].copy(
                productCode = code, 
                isEdited = true
            )
            notifyItemChanged(position)
        }
    }

    fun updateItemFromCandidate(position: Int, code: String) {
        if (position in items.indices) {
            items[position] = items[position].copy(
                productCode = code,
                isEdited = true
            )
            notifyItemChanged(position)
        }
    }

    fun addItem(item: RecognizedItem) {
        items.add(item)
        notifyItemInserted(items.size - 1)
    }

    fun removeItem(position: Int) {
        if (position in items.indices) {
            items.removeAt(position)
            notifyItemRemoved(position)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemRecognizedProductBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position], position)
    }

    override fun getItemCount() = items.size

    inner class ViewHolder(
        private val binding: ItemRecognizedProductBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: RecognizedItem, position: Int) {
            binding.tvProductCode.text = item.productCode
            
            // 候補がある場合はアイコンを表示（または色を変える）
            if (item.candidates.isNotEmpty() && !item.isEdited) {
                binding.tvProductCode.setCompoundDrawablesWithIntrinsicBounds(0, 0, com.crossvision.f.R.drawable.ic_expand_more, 0)
            } else {
                binding.tvProductCode.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0)
            }

            binding.tvRawText.text = if (item.isEdited) "（手動修正済み）" else "認識テキスト: ${item.rawText}"
            binding.cbSelect.isChecked = item.isSelected

            binding.cbSelect.setOnCheckedChangeListener { _, isChecked ->
                if (position in items.indices) {
                    items[position] = items[position].copy(isSelected = isChecked)
                    onSelectionChanged(position, isChecked)
                }
            }

            // 製品コードタップで候補を表示
            binding.tvProductCode.setOnClickListener {
                if (item.candidates.isNotEmpty()) {
                    showCandidatesMenu(it, item.candidates, position)
                } else {
                    onEditClick(position)
                }
            }

            binding.btnEdit.setOnClickListener { onEditClick(position) }
            binding.btnDelete.setOnClickListener { onDeleteClick(position) }
        }

        private fun showCandidatesMenu(view: android.view.View, candidates: List<String>, position: Int) {
            val popup = androidx.appcompat.widget.PopupMenu(view.context, view)
            candidates.forEachIndexed { index, s ->
                popup.menu.add(0, index, index, s)
            }
            popup.setOnMenuItemClickListener { menuItem ->
                updateItemFromCandidate(position, menuItem.title.toString())
                true
            }
            popup.show()
        }
    }
}

/**
 * 認識結果のアイテムデータ
 */
data class RecognizedItem(
    val productCode: String,
    val rawText: String = "",
    val isSelected: Boolean = true,
    val isEdited: Boolean = false,
    val candidates: List<String> = emptyList()
)
