package com.crossvision.f.ui.confirm

import android.view.View
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
            
            // 信頼度に応じた色分け（プロトタイプ準拠）
            val confidenceColor = when {
                item.confidence >= 0.85f -> android.graphics.Color.parseColor("#4CAF50") // Green
                item.confidence >= 0.60f -> android.graphics.Color.parseColor("#FFC107") // Yellow
                else -> android.graphics.Color.parseColor("#F44336") // Red
            }
            binding.tvConfidence.run {
                visibility = View.VISIBLE
                text = "信頼度: ${(item.confidence * 100).toInt()}%"
                setTextColor(confidenceColor)
            }
            
            binding.cbSelect.isChecked = item.isSelected

            binding.cbSelect.setOnCheckedChangeListener { _, isChecked ->
                val currentPos = bindingAdapterPosition
                if (currentPos != RecyclerView.NO_POSITION && currentPos in items.indices) {
                    items[currentPos] = items[currentPos].copy(isSelected = isChecked)
                    onSelectionChanged(currentPos, isChecked)
                }
            }

            // 製品コードタップで候補を表示
            binding.tvProductCode.setOnClickListener {
                val currentPos = bindingAdapterPosition
                if (currentPos != RecyclerView.NO_POSITION && currentPos in items.indices) {
                    val currentItem = items[currentPos]
                    if (currentItem.candidates.isNotEmpty()) {
                        showCandidatesMenu(it, currentItem.candidates, currentPos)
                    } else {
                        onEditClick(currentPos)
                    }
                }
            }

            binding.btnEdit.setOnClickListener {
                val currentPos = bindingAdapterPosition
                if (currentPos != RecyclerView.NO_POSITION) {
                    onEditClick(currentPos)
                }
            }
            binding.btnDelete.setOnClickListener {
                val currentPos = bindingAdapterPosition
                if (currentPos != RecyclerView.NO_POSITION) {
                    onDeleteClick(currentPos)
                }
            }
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
    val confidence: Float = 1.0f,
    val candidates: List<String> = emptyList()
)
