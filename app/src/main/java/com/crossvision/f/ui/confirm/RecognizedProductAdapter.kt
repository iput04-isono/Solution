package com.crossvision.f.ui.confirm

import android.graphics.BitmapFactory
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
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

    fun updateItem(position: Int, code: String, isInMaster: Boolean) {
        if (position in items.indices) {
            items[position] = items[position].copy(
                productCode = code, 
                isEdited = true,
                isInMaster = isInMaster
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
            binding.tvRawText.text = if (item.isEdited) "（手動修正済み）" else "認識テキスト: ${item.rawText}"
            binding.cbSelect.setOnCheckedChangeListener(null) // 再利用時の意図しない発火を防止
            binding.cbSelect.isChecked = item.isSelected

            // マスター未登録時の警告表示
            if (!item.isInMaster) {
                binding.root.setCardBackgroundColor(Color.parseColor("#FFF4E5")) // 薄いオレンジ
                binding.tvWarning.visibility = View.VISIBLE
                binding.tvWarning.text = "⚠️ マスター未登録！"
            } else {
                binding.root.setCardBackgroundColor(Color.WHITE)
                binding.tvWarning.visibility = View.GONE
            }

            val cropPath = item.cropImagePath
            if (cropPath != null) {
                val bmp = BitmapFactory.decodeFile(cropPath)
                if (bmp != null) {
                    binding.ivCrop.setImageBitmap(bmp)
                    binding.ivCrop.visibility = View.VISIBLE
                } else {
                    binding.ivCrop.visibility = View.GONE
                }
            } else {
                binding.ivCrop.visibility = View.GONE
            }

            binding.cbSelect.setOnCheckedChangeListener { _, isChecked ->
                val pos = adapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    items[pos] = items[pos].copy(isSelected = isChecked)
                    onSelectionChanged(pos, isChecked)
                }
            }

            binding.btnEdit.setOnClickListener {
                val pos = adapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    onEditClick(pos)
                }
            }
            binding.btnDelete.setOnClickListener {
                val pos = adapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    onDeleteClick(pos)
                }
            }
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
    val cropImagePath: String? = null,
    val isInMaster: Boolean = true
)
