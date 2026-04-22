package com.example.android_2

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.widget.*
import kotlinx.coroutines.*

class MainActivity : Activity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvResult: TextView
    private lateinit var tvAccuracy: TextView
    private lateinit var tvSelectedColor: TextView

    private lateinit var ivOriginal: ImageView
    private lateinit var detectionListContainer: LinearLayout

    private lateinit var imageListView: ListView
    private lateinit var imageAdapter: ArrayAdapter<String>

    private lateinit var paletteGrid: GridLayout
    private lateinit var paletteWrapper: LinearLayout
    private lateinit var btnColorMode: Button
    private lateinit var btnRunOCR: Button
    private lateinit var btnRotateLeft: Button
    private lateinit var btnRotateRight: Button

    private var imageIndex = 0
    private var imageFiles = listOf<String>()

    private var originalBitmap: Bitmap? = null
    private var previewBitmap: Bitmap? = null
    private var displayedPreviewBitmap: Bitmap? = null

    private var selectedColorMode: OcrEngine.ColorMode = OcrEngine.ColorMode.NONE
    private var isPaletteVisible = false
    private var manualRotationDegrees = 0f

    private var ocrEngine: OcrEngine? = null
    private var productCodeMatcher: ProductCodeMatcher? = null
    private val mainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }

        tvStatus = TextView(this).apply {
            textSize = 14f
            text = "初期化中..."
        }
        root.addView(tvStatus)

        root.addView(makeSectionLabel("画像一覧"))
        imageListView = ListView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                300
            )
            choiceMode = ListView.CHOICE_MODE_SINGLE
        }
        root.addView(imageListView)

        btnColorMode = Button(this).apply {
            text = "色モード変更"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener {
                isPaletteVisible = !isPaletteVisible
                paletteWrapper.visibility = if (isPaletteVisible) View.VISIBLE else View.GONE
                text = if (isPaletteVisible) "色モードを閉じる" else "色モード変更"
            }
        }
        root.addView(btnColorMode)

        paletteWrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }

        val colorTitle = TextView(this).apply {
            text = "文字色を選択"
            textSize = 16f
            setPadding(0, 8, 0, 8)
        }
        paletteWrapper.addView(colorTitle)

        paletteGrid = GridLayout(this).apply {
            columnCount = 5
            rowCount = 2
        }
        paletteWrapper.addView(paletteGrid)

        tvSelectedColor = TextView(this).apply {
            text = "選択中: 指定なし"
            textSize = 14f
            setPadding(0, 8, 0, 12)
        }
        paletteWrapper.addView(tvSelectedColor)

        root.addView(paletteWrapper)
        setupColorPalette()

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        content.addView(makeSectionLabel("選択中の画像"))
        ivOriginal = makePreviewImageView()
        content.addView(ivOriginal)

        val rotateRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 4, 0, 12)
        }

        btnRotateLeft = Button(this).apply {
            text = "↺ 45°"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { rotateOriginalDisplay(-45f) }
        }
        rotateRow.addView(btnRotateLeft)

        btnRotateRight = Button(this).apply {
            text = "45° ↻"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { rotateOriginalDisplay(45f) }
        }
        rotateRow.addView(btnRotateRight)

        content.addView(rotateRow)

        tvResult = TextView(this).apply {
            text = "認識結果: (準備中)"
            textSize = 18f
            setPadding(0, 16, 0, 8)
        }
        content.addView(tvResult)

        tvAccuracy = TextView(this).apply {
            text = "詳細: --"
            textSize = 14f
            setPadding(0, 0, 0, 16)
        }
        content.addView(tvAccuracy)

        content.addView(makeSectionLabel("検出結果一覧"))
        detectionListContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        content.addView(detectionListContainer)

        scroll.addView(content)
        root.addView(scroll)

        val bottomButtons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val btnClearColor = Button(this).apply {
            text = "色指定解除"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                selectedColorMode = OcrEngine.ColorMode.NONE
                tvSelectedColor.text = "選択中: 指定なし"
                refreshColorModeSelection()
                isPaletteVisible = false
                paletteWrapper.visibility = View.GONE
                btnColorMode.text = "色モード変更"
            }
        }
        bottomButtons.addView(btnClearColor)

        btnRunOCR = Button(this).apply {
            text = "OCR実行"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { runOCR() }
        }
        bottomButtons.addView(btnRunOCR)

        root.addView(bottomButtons)
        setContentView(root)

        mainScope.launch {
            ocrEngine = withContext(Dispatchers.IO) { OcrEngine(this@MainActivity) }

            productCodeMatcher = withContext(Dispatchers.IO) {
                try {
                    ProductCodeMatcher(this@MainActivity)
                } catch (e: Exception) {
                    e.printStackTrace()
                    null
                }
            }

            loadImageList()

            tvStatus.text = if (productCodeMatcher == null) {
                "ProductCodeMatcher 初期化失敗"
            } else {
                "ProductCodeMatcher 初期化成功 / 件数: ${productCodeMatcher?.getProductCodeCount()}"
            }

            tvResult.text = "認識結果: (準備完了)"
        }
    }

    private fun setupColorPalette() {
        paletteGrid.removeAllViews()

        val items = listOf(
            OcrEngine.ColorMode.NONE to Color.LTGRAY,
            OcrEngine.ColorMode.YELLOW to Color.YELLOW,
            OcrEngine.ColorMode.GREEN to Color.GREEN,
            OcrEngine.ColorMode.BLUE to Color.BLUE,
            OcrEngine.ColorMode.RED to Color.RED,
            OcrEngine.ColorMode.PINK to Color.rgb(255, 80, 180),
            OcrEngine.ColorMode.CYAN to Color.CYAN,
            OcrEngine.ColorMode.WHITE to Color.WHITE,
            OcrEngine.ColorMode.BLACK to Color.BLACK
        )

        items.forEach { (mode, color) ->
            paletteGrid.addView(makeColorModeSwatch(mode, color))
        }
    }

    private fun makeColorModeSwatch(mode: OcrEngine.ColorMode, color: Int): View {
        return TextView(this).apply {
            width = 96
            height = 96
            layoutParams = LinearLayout.LayoutParams(96, 96).apply {
                rightMargin = 8
                bottomMargin = 8
            }
            background = makePaletteBackground(color, mode == selectedColorMode)
            setOnClickListener {
                selectedColorMode = mode
                refreshColorModeSelection()
                tvSelectedColor.text = "選択中: ${mode.label}"
                isPaletteVisible = false
                paletteWrapper.visibility = View.GONE
                btnColorMode.text = "色モード変更"
            }
        }
    }

    private fun refreshColorModeSelection() {
        val items = listOf(
            OcrEngine.ColorMode.NONE,
            OcrEngine.ColorMode.YELLOW,
            OcrEngine.ColorMode.GREEN,
            OcrEngine.ColorMode.BLUE,
            OcrEngine.ColorMode.RED,
            OcrEngine.ColorMode.PINK,
            OcrEngine.ColorMode.CYAN,
            OcrEngine.ColorMode.WHITE,
            OcrEngine.ColorMode.BLACK
        )

        for (i in 0 until paletteGrid.childCount) {
            val child = paletteGrid.getChildAt(i)
            val bg = child.background as? GradientDrawable ?: continue
            val fillColor = bg.color?.defaultColor ?: Color.LTGRAY
            child.background = makePaletteBackground(fillColor, items.getOrNull(i) == selectedColorMode)
        }
    }

    private fun makePaletteBackground(fillColor: Int, selected: Boolean): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 10f
            setColor(fillColor)
            val strokeColor = if (fillColor == Color.WHITE) Color.DKGRAY else Color.BLACK
            setStroke(if (selected) 5 else 2, strokeColor)
        }
    }

    private fun makeSectionLabel(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 16f
            setPadding(0, 12, 0, 8)
        }
    }

    private fun makePreviewImageView(): ImageView {
        return ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                650
            )
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(0xFFEEEEEE.toInt())
            adjustViewBounds = true
            setPadding(0, 0, 0, 12)
        }
    }

    private fun loadImageList() {
        imageFiles = assets.list("Images")
            ?.filter { it.lowercase().endsWith(".jpg") || it.lowercase().endsWith(".png") }
            ?.sorted()
            ?: emptyList()

        imageAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_activated_1, imageFiles)
        imageListView.adapter = imageAdapter

        imageListView.setOnItemClickListener { _, _, position, _ ->
            imageIndex = position
            showImage(position)
            imageListView.setItemChecked(position, true)
        }

        if (imageFiles.isNotEmpty()) {
            showImage(0)
            imageListView.setItemChecked(0, true)
        } else {
            tvStatus.text = "Images フォルダに画像がありません"
        }
    }

    private fun showImage(index: Int) {
        if (index !in imageFiles.indices) return

        val fileName = imageFiles[index]
        try {
            assets.open("Images/$fileName").use { inputStream ->
                val loaded = BitmapFactory.decodeStream(inputStream)

                previewBitmap?.recycleSafely(except = loaded)
                displayedPreviewBitmap?.recycleSafely(except = loaded)

                originalBitmap = loaded
                previewBitmap = createPreviewBitmap(loaded, 1600)
                displayedPreviewBitmap = previewBitmap
                manualRotationDegrees = 0f

                ivOriginal.setImageBitmap(displayedPreviewBitmap)
                detectionListContainer.removeAllViews()
                tvStatus.text = "画像: $fileName / 回転: 0°"
                tvResult.text = "認識結果: (待機中)"
                tvAccuracy.text = "詳細: --"
            }
        } catch (e: Exception) {
            tvStatus.text = "画像読み込みエラー: ${e.message}"
        }
    }

    private fun rotateOriginalDisplay(deltaDegrees: Float) {
        val srcPreview = previewBitmap ?: return

        manualRotationDegrees = (manualRotationDegrees + deltaDegrees) % 360f
        if (manualRotationDegrees < 0f) manualRotationDegrees += 360f

        val rotatedPreview = rotateBitmap(srcPreview, manualRotationDegrees)
        val trimmedPreview = trimTransparentMargins(rotatedPreview)

        if (displayedPreviewBitmap != previewBitmap) {
            displayedPreviewBitmap?.recycleSafely(except = previewBitmap)
        }

        displayedPreviewBitmap = trimmedPreview
        ivOriginal.setImageBitmap(displayedPreviewBitmap)

        tvStatus.text = "画像: ${imageFiles.getOrNull(imageIndex) ?: ""} / 回転: ${manualRotationDegrees.toInt()}°"
    }

    private fun runOCR() {
        val original = originalBitmap ?: return
        val engine = ocrEngine ?: return
        val matcher = productCodeMatcher

        btnRunOCR.isEnabled = false
        btnRotateLeft.isEnabled = false
        btnRotateRight.isEnabled = false

        detectionListContainer.removeAllViews()
        tvResult.text = "解析中..."
        tvAccuracy.text = "モード: 多角形 / 色: ${selectedColorMode.label} / 回転: ${manualRotationDegrees.toInt()}°"

        mainScope.launch {
            try {
                val rotatedInput = withContext(Dispatchers.Default) {
                    rotateBitmap(original, manualRotationDegrees)
                }

                val ocrOutput = withContext(Dispatchers.Default) {
                    engine.runFullOcr(
                        originalBitmap = rotatedInput,
                        colorMode = selectedColorMode
                    )
                }

                ivOriginal.setImageBitmap(displayedPreviewBitmap)

                if (ocrOutput.items.isNotEmpty()) {
                    val matcher = productCodeMatcher

                    val enrichedItems = if (matcher != null) {
                        ocrOutput.items.map { item ->
                            val normalText = matcher.normalizeCode(item.normalResult.text ?: "")
                            val rotatedText = matcher.normalizeCode(item.rotated180Result.text ?: "")

                            item.copy(
                                normalSuggestions = matcher.findTopCandidates(normalText, 3),
                                rotated180Suggestions = matcher.findTopCandidates(rotatedText, 3)
                            )
                        }
                    } else {
                        ocrOutput.items
                    }
                    tvResult.text = "認識結果:"
                    tvAccuracy.text = "検出数: ${enrichedItems.size} / モード: 多角形 / 回転: ${manualRotationDegrees.toInt()}°"
                    enrichedItems.forEach { addDetectionItemView(it) }
                } else {
                    tvResult.text = "認識結果: 文字が見つかりませんでした"
                    tvAccuracy.text = "モード: 多角形 / 色: ${selectedColorMode.label} / 回転: ${manualRotationDegrees.toInt()}°"
                }
            } catch (e: Exception) {
                tvResult.text = "エラー: ${e.message}"
                tvAccuracy.text = ""
            } finally {
                btnRunOCR.isEnabled = true
                btnRotateLeft.isEnabled = true
                btnRotateRight.isEnabled = true
            }
        }
    }

    private fun addDetectionItemView(item: OcrEngine.OcrDetectionItem) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 20, 20, 20)
            background = GradientDrawable().apply {
                cornerRadius = 18f
                setColor(Color.WHITE)
                setStroke(2, 0x22000000)
            }
        }

        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = 18
        }
        card.layoutParams = params

        val title = TextView(this).apply {
            text = "No.${item.index}"
            textSize = 17f
            setPadding(0, 0, 0, 8)
        }
        card.addView(title)

        val image = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                260
            )
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(0xFFEEEEEE.toInt())
            setImageBitmap(item.croppedBitmap)
        }
        card.addView(image)

        val areaInfo = "領域: 多角形検出"

        fun formatSuggestions(
            title: String,
            ocrText: String,
            suggestions: List<ProductCodeMatcher.MatchedCode>
        ): String {
            return buildString {
                append(title)
                append("\nOCR結果: $ocrText\n")
                if (suggestions.isEmpty()) {
                    append("候補: なし\n")
                } else {
                    append("候補:\n")
                    suggestions.forEachIndexed { index, s ->
                        append("${index + 1}. ${s.originalCode} ")
                        append("(類似度: ${String.format("%.1f", s.similarity * 100)}%, 距離: ${s.distance})\n")
                    }
                }
            }
        }

        val resultText = TextView(this).apply {
            text = buildString {
                append(areaInfo)
                append("\n\n")
                append(formatSuggestions("通常向きOCR", item.normalResult.text, item.normalSuggestions))
                append("\n")
                append(formatSuggestions("180°回転後OCR", item.rotated180Result.text, item.rotated180Suggestions))
            }
            textSize = 14f
            setPadding(0, 10, 0, 0)
        }
        card.addView(resultText)

        detectionListContainer.addView(card)
    }

    private fun createPreviewBitmap(src: Bitmap, maxSize: Int): Bitmap {
        val w = src.width
        val h = src.height
        val longSide = maxOf(w, h)

        if (longSide <= maxSize) {
            return src.copy(Bitmap.Config.ARGB_8888, false)
        }

        val scale = maxSize.toFloat() / longSide.toFloat()
        val newW = (w * scale).toInt().coerceAtLeast(1)
        val newH = (h * scale).toInt().coerceAtLeast(1)

        return Bitmap.createScaledBitmap(src, newW, newH, true)
    }

    private fun rotateBitmap(src: Bitmap, degrees: Float): Bitmap {
        val normalized = ((degrees % 360f) + 360f) % 360f
        if (normalized < 0.01f || normalized > 359.99f) {
            return src.copy(Bitmap.Config.ARGB_8888, false)
        }

        val matrix = Matrix().apply { postRotate(normalized) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
    }

    private fun trimTransparentMargins(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height

        var minX = width
        var minY = height
        var maxX = -1
        var maxY = -1

        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        for (y in 0 until height) {
            val rowOffset = y * width
            for (x in 0 until width) {
                val alpha = Color.alpha(pixels[rowOffset + x])
                if (alpha > 0) {
                    if (x < minX) minX = x
                    if (y < minY) minY = y
                    if (x > maxX) maxX = x
                    if (y > maxY) maxY = y
                }
            }
        }

        if (maxX < minX || maxY < minY) return bitmap

        return Bitmap.createBitmap(bitmap, minX, minY, maxX - minX + 1, maxY - minY + 1)
    }

    private fun Bitmap?.recycleSafely(except: Bitmap? = null) {
        if (this != null && this != except && !this.isRecycled) {
            this.recycle()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        previewBitmap?.recycleSafely(except = originalBitmap)
        displayedPreviewBitmap?.recycleSafely(except = previewBitmap)
        ocrEngine?.close()
        mainScope.cancel()
    }
}