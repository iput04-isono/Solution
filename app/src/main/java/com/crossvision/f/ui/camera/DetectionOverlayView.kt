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

    /** 前処理画像座標系のポリゴンリスト */
    private var polygons: List<FloatArray> = emptyList()

    /** 前処理画像の幅（座標変換に使用） */
    private var imageWidth: Int = 1
    /** 前処理画像の高さ（座標変換に使用） */
    private var imageHeight: Int = 1

    // ── ペイント ──────────────────────────────────────────────────────────

    private val outerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 6f
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    private val innerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0, 230, 118)   // マテリアルグリーン A400
        style = Paint.Style.STROKE
        strokeWidth = 5f
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        // 半透明の緑塗り
        color = Color.argb(40, 0, 230, 118)
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
    fun updatePolygons(
        polygons: List<FloatArray>,
        imageWidth: Int,
        imageHeight: Int
    ) {
        this.polygons = polygons
        this.imageWidth = imageWidth.coerceAtLeast(1)
        this.imageHeight = imageHeight.coerceAtLeast(1)
        postInvalidate()
    }

    /** 全ポリゴンをクリアする */
    fun clearPolygons() {
        polygons = emptyList()
        postInvalidate()
    }

    // ── 描画 ─────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (polygons.isEmpty()) return

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

        for (poly in polygons) {
            if (poly.size < 8) continue

            val path = Path()
            path.moveTo(poly[0] * scale + offsetX, poly[1] * scale + offsetY)
            for (i in 1 until poly.size / 2) {
                path.lineTo(poly[i * 2] * scale + offsetX, poly[i * 2 + 1] * scale + offsetY)
            }
            path.close()

            canvas.drawPath(path, fillPaint)   // 半透明塗り
            canvas.drawPath(path, outerPaint)  // 白縁取り
            canvas.drawPath(path, innerPaint)  // 緑枠
        }
    }
}
