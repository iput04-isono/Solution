package com.crossvision.f.ocr

/**
 * 製品コードバリデーション
 * PDF資料「製品コード使用可能文字」に基づくルール
 *
 * 使用可能文字:
 * - 英大文字: A-Z
 * - 英小文字: a-z
 * - 数字: 0-9
 * - 記号: + - . / _ スペース
 *
 * 先頭識別子（Xa1, Ya1等）は読み取り不要のため自動除去
 */
object ProductCodeValidator {

    // 使用可能文字の正規表現パターン（角括弧 [ ] を追加）
    private val VALID_CHARS_PATTERN = Regex("^[A-Za-z0-9+\\-./_ \\[\\]]+$")

    // 先頭識別子の除去パターン（Xa1, Ya1 等）
    private val PREFIX_PATTERN = Regex("^[XY][a-z][0-9]\\s*")

    // 製品コードの最小文字数
    private const val MIN_CODE_LENGTH = 2

    // 製品コードの最大文字数
    private const val MAX_CODE_LENGTH = 50

    /**
     * 製品コードのバリデーション
     * @param code チェック対象の文字列
     * @return バリデーション結果
     */
    fun validate(code: String): ValidationResult {
        if (code.isBlank()) {
            return ValidationResult(false, "製品コードが空です")
        }

        val trimmedCode = code.trim()

        if (trimmedCode.length < MIN_CODE_LENGTH) {
            return ValidationResult(false, "製品コードが短すぎます（${MIN_CODE_LENGTH}文字以上）")
        }

        if (trimmedCode.length > MAX_CODE_LENGTH) {
            return ValidationResult(false, "製品コードが長すぎます（${MAX_CODE_LENGTH}文字以下）")
        }

        if (!VALID_CHARS_PATTERN.matches(trimmedCode)) {
            val invalidChars = trimmedCode.filter { char ->
                !char.toString().matches(Regex("[A-Za-z0-9+\\-./_ \\[\\]]"))
            }.toSet()
            return ValidationResult(
                false,
                "使用できない文字が含まれています: ${invalidChars.joinToString(", ")}"
            )
        }

        return ValidationResult(true, "OK")
    }

    /**
     * 先頭識別子を除去してクリーンな製品コードを返す
     * 例: "Xa1 1C-X1Y1" → "1C-X1Y1"
     */
    fun cleanProductCode(rawCode: String): String {
        return rawCode.trim().replace(PREFIX_PATTERN, "").trim()
    }

    /**
     * OCR結果から製品コードとして妥当な文字列を抽出
     * ノイズ（単一文字、記号のみ等）を除去
     */
    fun extractValidCodes(ocrLines: List<String>): List<String> {
        return ocrLines
            .map { cleanProductCode(it) }
            .filter { it.length >= MIN_CODE_LENGTH }
            .filter { VALID_CHARS_PATTERN.matches(it) }
            .distinct()
    }

    /**
     * 製品コードの正規化（大文字統一等）
     * 要件に応じて大文字/小文字の区別を調整
     */
    fun normalizeCode(code: String): String {
        return cleanProductCode(code).trim()
    }
}

/**
 * バリデーション結果
 */
data class ValidationResult(
    val isValid: Boolean,
    val message: String
)
