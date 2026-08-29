package com.lingion.mailgofer.format

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 验证码提取: 关键词正则 + 4-8 位数字/字母 OTP 模式。
 * 0 OCR: 99% 服务商纯文本发码,模式匹配足够。
 */
class OtpExtractorTest {

    @Test
    fun `Instagram_G_style_前缀_提取数字部分`() {
        // Instagram G-123456 用户实际输入的是数字部分
        val src = "Your Instagram code: G-123456"
        assertEquals("123456", OtpExtractor.extract(src))
    }

    @Test
    fun `Google_纯6位数字_含关键词`() {
        val src = "您的 Google 验证码为 742931，请在 10 分钟内使用。"
        assertEquals("742931", OtpExtractor.extract(src))
    }

    @Test
    fun `Microsoft_8位_含关键词`() {
        val src = "Use code 1234ABCD to verify your Microsoft account."
        assertEquals("1234ABCD", OtpExtractor.extract(src))
    }

    @Test
    fun `Discord_6位_下划线context`() {
        val src = "Your Discord verification code is: 987654. Don't share this code."
        assertEquals("987654", OtpExtractor.extract(src))
    }

    @Test
    fun `Apple_Your_code_is_短语`() {
        val src = "Your Apple ID code is: 555444. Don't share it."
        assertEquals("555444", OtpExtractor.extract(src))
    }

    @Test
    fun `无关键词_纯数字_不返回`() {
        // 缺少 verification/code/OTP/验证码 关键词, 不应误报
        assertNull(OtpExtractor.extract("您的订单号 123456 已发货。"))
    }

    @Test
    fun `过长_9位_不返回`() {
        // 9 位超出 4-8 范围, 排除手机号/订单号误识
        assertNull(OtpExtractor.extract("Verification code: 123456789012"))
    }

    @Test
    fun `纯3位_过短_不返回`() {
        assertNull(OtpExtractor.extract("code: 123 ok"))
    }

    @Test
    fun `HTML邮件_标签干扰_能识别`() {
        val src = "<p>Your code: <strong>123456</strong></p>\n<p>Don't share it.</p>"
        assertEquals("123456", OtpExtractor.extract(src))
    }

    @Test
    fun `null_or_blank_返null`() {
        assertNull(OtpExtractor.extract(null))
        assertNull(OtpExtractor.extract(""))
    }
}