/**
 * CompareResultAdapter.kt
 *
 * 前処理条件 × OCR 比較結果を RecyclerView に表示するアダプター。
 * カテゴリが変わるタイミングでセクションヘッダーを挿入する。
 */
package com.example.imagepreprocessingtest

import android.graphics.Bitmap
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/** 1件の比較結果 */
data class CompareResult(
    val condition: ImagePreprocessor.PreprocessCondition,
    val bitmap: Bitmap?,           // 前処理済み画像（null = まだ未処理）
    val ocrText: String,           // 認識テキスト
    val confidence: Float,         // 信頼度 0〜1
    val ocrDetail: String,         // 詳細（ブロック単位）
    val preprocessMs: Long,        // 前処理時間
    val ocrMs: Long                // OCR推論時間
)

/** カテゴリ色マップ */
private val CATEGORY_COLORS = mapOf(
    "A: 二値化なし"        to "#607D8B",
    "B: 二値化手法"        to "#1976D2",
    "C: ブラー手法"        to "#388E3C",
    "D: コントラスト"      to "#F57C00",
    "E: 背景・影"          to "#7B1FA2",
    "F: カラーチャンネル"  to "#C62828",
    "G: 全改善"            to "#00796B"
)

class CompareResultAdapter(
    private var items: List<CompareResult>
) : RecyclerView.Adapter<CompareResultAdapter.ResultViewHolder>() {

    inner class ResultViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val textCategory: TextView     = view.findViewById(R.id.textCategory)
        val textName: TextView         = view.findViewById(R.id.textConditionName)
        val textTime: TextView         = view.findViewById(R.id.textProcessTime)
        val imageThumb: ImageView      = view.findViewById(R.id.imageThumb)
        val textOcrResult: TextView    = view.findViewById(R.id.textOcrResult)
        val textConfidence: TextView   = view.findViewById(R.id.textConfidence)
        val textDetail: TextView       = view.findViewById(R.id.textOcrDetail)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ResultViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_compare_result, parent, false)
        return ResultViewHolder(view)
    }

    override fun onBindViewHolder(holder: ResultViewHolder, position: Int) {
        val item = items[position]
        val cond = item.condition

        // カテゴリバッジの文字と色を設定
        val badgeLetter = cond.category.take(1)
        val color = CATEGORY_COLORS[cond.category] ?: "#757575"
        holder.textCategory.text = badgeLetter
        holder.textCategory.setBackgroundColor(Color.parseColor(color))

        holder.textName.text = cond.name

        // 処理時間（前処理 + OCR）
        val totalMs = item.preprocessMs + item.ocrMs
        holder.textTime.text = if (totalMs > 0) "${totalMs}ms" else "--"

        // サムネイル
        if (item.bitmap != null) {
            holder.imageThumb.setImageBitmap(item.bitmap)
        } else {
            holder.imageThumb.setImageResource(android.R.drawable.ic_menu_gallery)
        }

        // 白黒あり/なし バッジを条件名の後ろに付ける
        val binLabel = if (cond.binarized) " ⬛白黒" else " 🔘グレー"
        holder.textName.text = cond.name + binLabel

        // OCR テキスト
        holder.textOcrResult.text = item.ocrText.ifEmpty { "（未実行）" }

        // 信頼度
        if (item.confidence > 0f) {
            holder.textConfidence.text = OcrEngine.confidenceLabel(item.confidence)
            val confColor = when {
                item.confidence >= 0.85f -> "#388E3C"
                item.confidence >= 0.60f -> "#F57C00"
                else                     -> "#C62828"
            }
            holder.textConfidence.setTextColor(Color.parseColor(confColor))
        } else {
            holder.textConfidence.text = "信頼度: -"
        }

        // 詳細テキスト（非空のときのみ表示）
        if (item.ocrDetail.isNotEmpty()) {
            holder.textDetail.text   = item.ocrDetail
            holder.textDetail.visibility = View.VISIBLE
        } else {
            holder.textDetail.visibility = View.GONE
        }
    }

    override fun getItemCount(): Int = items.size

    /** 信頼度の高い順にソートして表示を更新する */
    fun sortByConfidence() {
        items = items.sortedByDescending { it.confidence }
        notifyDataSetChanged()
    }

    /** 元の条件順に戻す */
    fun sortByConditionOrder() {
        items = items.sortedBy { it.condition.id }
        notifyDataSetChanged()
    }

    /** 全件を新しいリストで置き換える */
    fun updateItems(newItems: List<CompareResult>) {
        items = newItems
        notifyDataSetChanged()
    }
}
