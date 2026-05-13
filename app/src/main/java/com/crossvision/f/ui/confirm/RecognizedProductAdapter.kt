package com.crossvision.f.ui.confirm

import android.graphics.BitmapFactory
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

    fun updateItem(position: Int, code: String) {
        if (position in items.indices) {
            items[position] = items[position].copy(productCode = code, isEdited = true)
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
            binding.cbSelect.isChecked = item.isSelected

            val cropPath = item.cropImagePath
            if (cropPath != null) {
                // クロップ画像も念のためリサイズして読み込む（メモリ節約）
                val options = BitmapFactory.Options().apply {
                    inSampleSize = 1 // クロップ画像は通常小さいので1で良いが、念のためオプションを指定
                }
                val bmp = BitmapFactory.decodeFile(cropPath, options)
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
                items[position] = items[position].copy(isSelected = isChecked)
                onSelectionChanged(position, isChecked)
            }

            binding.btnEdit.setOnClickListener { onEditClick(position) }
            binding.btnDelete.setOnClickListener { onDeleteClick(position) }
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
    val cropImagePath: String? = null
)
