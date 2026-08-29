package com.lingion.mailgofer.format

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 把服务端修正前入库的"原始 MIME 当成纯文本塞 content"切成干净 text/html 段。
 * 已知脏数据形状: rockbase 仓库的 GMAIL 邮件 (cf-mail-api commit 8ce0f9e 之前)
 */
class MimeSanitizerTest {

    private val gmailRawDirty = """
        --000000000000f924b2065a2a5b6b
        Content-Type: text/plain; charset="UTF-8"

        test

        --000000000000f924b2065a2a5b6b
        Content-Type: text/html; charset="UTF-8"

        <div>hi html</div>
        --000000000000f924b2065a2a5b6b--
    """.trimIndent()

    @Test
    fun `gmail原始MIME_切出text段`() {
        val out = MimeSanitizer.sanitize(gmailRawDirty)
        assertEquals("test", out.text)
    }

    @Test
    fun `gmail原始MIME_切出html段`() {
        val out = MimeSanitizer.sanitize(gmailRawDirty)
        assertTrue(out.html.contains("<div>hi html</div>"))
    }

    @Test
    fun `gmail原始MIME_html不再带ContentType头`() {
        val out = MimeSanitizer.sanitize(gmailRawDirty)
        assertFalse(out.html.contains("Content-Type:"))
        assertFalse(out.text.contains("--000000000000f924"))
    }

    @Test
    fun `纯文本_无boundary_原样返回`() {
        val out = MimeSanitizer.sanitize("Hello, plain body.")
        assertEquals("Hello, plain body.", out.text)
        assertEquals("", out.html)
    }

    @Test
    fun `null_or_blank_返回空`() {
        val empty = MimeSanitizer.sanitize(null)
        assertEquals("", empty.text)
        assertEquals("", empty.html)
        val blank = MimeSanitizer.sanitize("\n\n  \n")
        assertEquals("", blank.text)
    }

    @Test
    fun `正常邮件_text_nonBlank_不走sanitize`() {
        // 服务端 MIME 修复后, content 是干净 text — 必须保持不变
        val clean = "你好，这是一封正常邮件。\n第二行。"
        val out = MimeSanitizer.sanitize(clean)
        assertEquals(clean, out.text)
    }

    @Test
    fun `复杂嵌套_gmail多层boundary_提取最深的text`() {
        val src = """
            --000000000000abcdef
            Content-Type: multipart/alternative; boundary="000000000001abcdef"

            --000000000001abcdef
            Content-Type: text/plain; charset="UTF-8"

            inner plain

            --000000000001abcdef
            Content-Type: text/html; charset="UTF-8"

            <p>inner html</p>
            --000000000001abcdef--

            --000000000000abcdef--
        """.trimIndent()
        val out = MimeSanitizer.sanitize(src)
        assertTrue("text 应包含 'inner plain' 实际 = ${out.text}", out.text.contains("inner plain"))
        assertTrue(out.html.contains("<p>inner html</p>"))
    }
}