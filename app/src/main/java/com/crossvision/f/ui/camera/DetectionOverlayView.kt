package com.crossvision.f.ui.camera

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

/**
 * カメラプレビュー上にDBNetの検出多角形をリアルタイム描画するオーバーレイView。
 *
 * PreviewView の直上（FrameLayout内）に `match_parent × match_parent` / 透明背景で配置する。
 *
 * 【座標変換】
 * DBNet が返すポリゴンは「前処理後画像（最大1280px）座標系」のため、
 * PreviewView の実際の描画領域（FILL_CENTER スケール）に合わせて変換する。
 *
 * 使い方:
 *   overlayView.updatePolygons(polygons, imageWidth, imageHeight)
 */
class DetectionOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // ── 描画用データ ──────────────────────────────────────────────────────

    /** 認識結果リスト */
    private var results: List<com.crossvision.f.ocr.DomainOcrResult> = emptyList()

    /** 前処理画像の幅（座標変換に使用） */
    private var imageWidth: Int = 1
    /** 前処理画像の高さ（座標変換に使用） */
    private var imageHeight: Int = 1

    // ── ペイント ──────────────────────────────────────────────────────────

    private val matchOuterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 6f
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    private val matchInnerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0, 230, 118)   // マテリアルグリーン A400 (一致)
        style = Paint.Style.STROKE
        strokeWidth = 5f
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    private val unmatchInnerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 193, 7)   // アンバー (未一致)
        style = Paint.Style.STROKE
        strokeWidth = 5f
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    private val trackingInnerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#03A9F4")   // Cyan (追尾中)
        style = Paint.Style.STROKE
        strokeWidth = 5f
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(40, 0, 230, 118)
        style = Paint.Style.FILL
    }
    
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 36f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    
    private val textBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(180, 0, 0, 0)
        style = Paint.Style.FILL
    }

    // ── 公開API ───────────────────────────────────────────────────────────

    /**
     * 検出ポリゴンを更新して再描画する。
     * バックグラウンドスレッドから呼んでもOK（postInvalidate使用）。
     *
     * @param polygons    FloatArray(8) のリスト [x0,y0,x1,y1,x2,y2,x3,y3]（前処理画像座標系）
     * @param imageWidth  前処理画像の幅（px）
     * @param imageHeight 前処理画像の高さ（px）
     */
    fun updateResults(
        results: List<com.crossvision.f.ocr.DomainOcrResult>,
        imageWidth: Int,
        imageHeight: Int
    ) {
        this.results = results
        this.imageWidth = imageWidth.coerceAtLeast(1)
        this.imageHeight = imageHeight.coerceAtLeast(1)
        postInvalidate()
    }

    /** 全描画をクリアする */
    fun clearResults() {
        results = emptyList()
        postInvalidate()
    }

    // ── 描画 ─────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (results.isEmpty()) return

        val viewW = width.toFloat()
        val viewH = height.toFloat()
        if (viewW == 0f || viewH == 0f) return

        // PreviewView のデフォルトスケールタイプは FILL_CENTER（CENTER_CROP相当）。
        // 画像がビュー全体を埋めるように拡大し、はみ出た部分はクロップされる。
        // → scale = max(viewW/imgW, viewH/imgH) を使う必要がある
        //   （min では黒帯を想定した計算になり枠位置がズレる）
        val scaleX = viewW / imageWidth
        val scaleY = viewH / imageHeight
        val scale  = maxOf(scaleX, scaleY)

        val scaledW = imageWidth  * scale
        val scaledH = imageHeight * scale
        val offsetX = (viewW - scaledW) / 2f
        val offsetY = (viewH - scaledH) / 2f

        for (result in results) {
            val originalPoly = result.polygon ?: continue
            if (originalPoly.size < 8) continue

            // 枠が広すぎるのを防ぐため、重心基準で少し縮小（OcrEngine で 1.55 倍に拡大されているため、それを緩和）
            var cx = 0f; var cy = 0f
            for (i in 0 until 4) { cx += originalPoly[i * 2]; cy += originalPoly[i * 2 + 1] }
            cx /= 4f; cy /= 4f

            val shrinkScale = 0.7f // 1.55倍を元に戻す程度（約1.1倍相当）
            val poly = FloatArray(8)
            for (i in 0 until 4) {
                poly[i * 2] = cx + (originalPoly[i * 2] - cx) * shrinkScale
                poly[i * 2 + 1] = cy + (originalPoly[i * 2 + 1] - cy) * shrinkScale
            }

            val path = Path()
            path.moveTo(poly[0] * scale + offsetX, poly[1] * scale + offsetY)
            for (i in 1 until poly.size / 2) {
                path.lineTo(poly[i * 2] * scale + offsetX, poly[i * 2 + 1] * scale + offsetY)
            }
            path.close()

            val text = result.displayCode
            val isMatched = result.matchedLabel != null
            val isTrackingOnly = text.isBlank()

            // 色設定: 一致=緑, 追尾のみ=青(Cyan), 不一致テキストあり=黄
            fillPaint.color = when {
                isMatched -> Color.argb(40, 0, 230, 118)
                isTrackingOnly -> Color.argb(40, 3, 169, 244)
                else -> Color.argb(40, 255, 193, 7)
            }

            canvas.drawPath(path, fillPaint)   // 半透明塗り
            canvas.drawPath(path, matchOuterPaint)  // 白縁取り
            
            val innerPaint = when {
                isMatched -> matchInnerPaint
                isTrackingOnly -> trackingInnerPaint
                else -> unmatchInnerPaint
            }
            canvas.drawPath(path, innerPaint)  // 色枠
            
            // 枠の上辺の角度
            val dx = poly[2] * scale - poly[0] * scale
            val dy = poly[3] * scale - poly[1] * scale
            val angleDegrees = Math.toDegrees(kotlin.math.atan2(dy.toDouble(), dx.toDouble())).toFloat()

            if (text.isNotBlank()) {
                val textX = (poly[0] + poly[2]) / 2f * scale + offsetX
                val textY = (poly[1] + poly[3]) / 2f * scale + offsetY - 15f
                
                val textWidth = textPaint.measureText(text)
                
                canvas.save()
                // 上辺の中央を軸に回転
                canvas.rotate(angleDegrees, textX, textY)
                
                // 背景矩形（中央揃え）
                canvas.drawRect(textX - textWidth / 2f - 8f, textY - textPaint.textSize, textX + textWidth / 2f + 8f, textY + 8f, textBgPaint)
                
                // テキスト描画（中央揃えに変更）
                textPaint.textAlign = Paint.Align.CENTER
                canvas.drawText(text, textX, textY, textPaint)
                // ペイントを元に戻す
                textPaint.textAlign = Paint.Align.LEFT
                
                canvas.restore()
            }
        }
    }
}
