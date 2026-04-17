package com.crossvision.f.ocr

import org.junit.Assert.*
import org.junit.Test

/**
 * 製品コードバリデーションのユニットテスト
 * テスト仕様書に基づくテストケース
 */
class ProductCodeValidatorTest {

    // ===== 正常系テスト =====

    @Test
    fun `正規の製品コードがバリデーションを通過する`() {
        val validCodes = listOf(
            "1C-X1Y1",
            "2C-X3Y3",
            "1ELP75-1",
            "2EP175-5",
            "3SB40-8",
            "3SCB35-1",
            "B1SCB100-30",
            "B2SCB40-10",
            "PHRHV",
            "RV11"
        )

        validCodes.forEach { code ->
            val result = ProductCodeValidator.validate(code)
            assertTrue("$code は有効であるべき: ${result.message}", result.isValid)
        }
    }

    @Test
    fun `使用可能文字のみで構成されたコードが通過する`() {
        val result = ProductCodeValidator.validate("ABC-123/DEF.456_GHI+JKL")
        assertTrue(result.isValid)
    }

    @Test
    fun `英小文字を含むコードが通過する`() {
        val result = ProductCodeValidator.validate("abc-123")
        assertTrue(result.isValid)
    }

    @Test
    fun `スペースを含むコードが通過する`() {
        val result = ProductCodeValidator.validate("ABC 123")
        assertTrue(result.isValid)
    }

    // ===== 異常系テスト =====

    @Test
    fun `空文字列が拒否される`() {
        val result = ProductCodeValidator.validate("")
        assertFalse(result.isValid)
    }

    @Test
    fun `ブランク文字列が拒否される`() {
        val result = ProductCodeValidator.validate("   ")
        assertFalse(result.isValid)
    }

    @Test
    fun `1文字のみのコードが拒否される`() {
        val result = ProductCodeValidator.validate("A")
        assertFalse(result.isValid)
    }

    @Test
    fun `使用不可文字を含むコードが拒否される`() {
        val invalidCodes = listOf(
            "ABC!123",   // 感嘆符
            "ABC?123",   // 疑問符
            "ABC#123",   // シャープ
            "ABC$123",   // ドル
            "ABC%123",   // パーセント
            "ABC&123",   // アンパサンド
            "ABC*123",   // アスタリスク
            "ABC@123",   // アットマーク
            "ABC=123",   // イコール
            "ABC;123",   // セミコロン
            "ABC:123",   // コロン
            "ABC,123",   // カンマ
        )

        invalidCodes.forEach { code ->
            val result = ProductCodeValidator.validate(code)
            assertFalse("$code は無効であるべき", result.isValid)
        }
    }

    // ===== 先頭識別子の除去テスト =====

    @Test
    fun `先頭識別子Xa1が除去される`() {
        val cleaned = ProductCodeValidator.cleanProductCode("Xa1 1C-X1Y1")
        assertEquals("1C-X1Y1", cleaned)
    }

    @Test
    fun `先頭識別子Ya1が除去される`() {
        val cleaned = ProductCodeValidator.cleanProductCode("Ya1 2EP175-5")
        assertEquals("2EP175-5", cleaned)
    }

    @Test
    fun `先頭識別子のないコードはそのまま返される`() {
        val cleaned = ProductCodeValidator.cleanProductCode("1C-X1Y1")
        assertEquals("1C-X1Y1", cleaned)
    }

    @Test
    fun `前後の空白が除去される`() {
        val cleaned = ProductCodeValidator.cleanProductCode("  1C-X1Y1  ")
        assertEquals("1C-X1Y1", cleaned)
    }

    // ===== OCR結果からの有効コード抽出テスト =====

    @Test
    fun `有効なコードのみが抽出される`() {
        val ocrLines = listOf(
            "1C-X1Y1",
            "A",           // 1文字 → 除外
            "",            // 空 → 除外
            "2EP175-5",
            "###",         // 無効文字 → 除外
            "3SB40-8"
        )

        val validCodes = ProductCodeValidator.extractValidCodes(ocrLines)
        assertEquals(3, validCodes.size)
        assertTrue(validCodes.contains("1C-X1Y1"))
        assertTrue(validCodes.contains("2EP175-5"))
        assertTrue(validCodes.contains("3SB40-8"))
    }

    @Test
    fun `重複する認識結果が除外される`() {
        val ocrLines = listOf("1C-X1Y1", "1C-X1Y1", "2EP175-5")
        val validCodes = ProductCodeValidator.extractValidCodes(ocrLines)
        assertEquals(2, validCodes.size)
    }

    // ===== 正規化テスト =====

    @Test
    fun `コードが正規化される`() {
        val normalized = ProductCodeValidator.normalizeCode("  Xa1 1C-X1Y1  ")
        assertEquals("1C-X1Y1", normalized)
    }
}
