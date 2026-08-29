package com.lingion.mailgofer.format

/**
 * 验证码提取。99% 服务商以纯文本发 OTP,不嵌 OCR(Tesseract 膨胀 APK ~20MB)。
 * 策略: 剥 HTML 标签 → 找关键词(verification/code/验证码/...)→
 *       关键词后 80 字符窗口内取首个"完整 token"的 4-8 位含数字字母数字串。
 *       完整 token = 前后都不是字母数字(订单号 123456789012 整体超长 → 不匹配)。
 *       含数字要求排除 "shown/below" 这类普通单词误报。
 */
object OtpExtractor {
    private val HTML_TAG = Regex("""<[^>]+>""")
    private val KEYWORD = Regex(
        """(?i)(?:verification\s*code|verify[ -]?code|your\s+code(?:\s+is)?|one[- ]time\s+password|\botp\b|验证码|校验码|动态码|授权码|\bcode\b|login\s+code|security\s+code)"""
    )
    // 完整 token 边界 + 至少一个数字
    private val OTP = Regex("""(?<![A-Za-z0-9])(?=[A-Za-z]*\d)[A-Za-z0-9]{4,8}(?![A-Za-z0-9])""")
    private const val WINDOW = 80

    fun extract(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val plain = HTML_TAG.replace(text, " ")
        val keyword = KEYWORD.find(plain) ?: return null
        val tail = plain.substring(keyword.range.last + 1, minOf(plain.length, keyword.range.last + 1 + WINDOW))
        return OTP.find(tail)?.value
    }
}