package com.lingion.mailgofer.format

/**
 * RFC 2047 encoded-word 主题解码。
 * 形如 =?charset?B?base64?=  /  =?charset?Q?quoted-printable?=
 * 多个 encoded-word 串联或与普通文本混排都能解, 解失败保留原文不破坏显示。
 */
object Rfc2047 {
    // 数据段字符: B=base64(A-Za-z0-9+/=) Q=QP(=XX 与 _),取并集
    private val ENCODED_WORD = Regex("""=\?[^\s?]+\?[BbQq]\?[A-Za-z0-9+/=_]+\?=""")

    fun decode(subject: String?): String? {
        if (subject.isNullOrEmpty()) return subject
        val re = ENCODED_WORD
        return re.replace(subject) { m ->
            val raw = m.value
            val body = raw.substring(2, raw.length - 2)
            val q1 = body.indexOf('?')
            val q2 = body.indexOf('?', q1 + 1)
            val charset = body.substring(0, q1)
            val enc = body.substring(q1 + 1, q2).uppercase()
            val data = body.substring(q2 + 1)
            try {
                val bytes = when (enc) {
                    // java.util.Base64 而非 android.util.Base64: 后者在本地 JVM 单测里
                    // "not mocked" 抛异常被 catch 吞掉 → 表现为"永远不解码"
                    "B" -> java.util.Base64.getMimeDecoder().decode(data)
                    "Q" -> decodeQuotedPrintable(data)
                    else -> return@replace raw
                }
                java.lang.String(bytes, java.nio.charset.Charset.forName(charset))
            } catch (_: Throwable) {
                raw
            }
        }
    }

    // RFC 2047 规定 Q 编码里: _ = 0x20 (空格); =XX = 字节; 其它原样
    private fun decodeQuotedPrintable(text: String): ByteArray {
        val out = ByteArray(text.length)
        var i = 0; var j = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '_' -> { out[j++] = 0x20; i++ }
                c == '=' && i + 2 < text.length &&
                    text[i + 1].isHexDigit() && text[i + 2].isHexDigit() -> {
                    out[j++] = ((text[i + 1].digitToInt(16) shl 4) or text[i + 2].digitToInt(16)).toByte()
                    i += 3
                }
                else -> {
                    out[j++] = c.code.toByte(); i++
                }
            }
        }
        return out.copyOf(j)
    }

    private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
}