package com.lingion.mailgofer.format

/**
 * 把服务端修正前入库的"原始 MIME 当成纯文本塞 content"切成干净 text/html 段。
 * 脏数据形状: 无 boundary 声明头(顶层头被剥),首行直接是 --xxxxxxxx 分隔线,
 * 各 part 自带 Content-Type 头。正常邮件(content 是干净 text)原样回退。
 */
object MimeSanitizer {
    private val BOUNDARY_RE = Regex("""boundary\s*=\s*"?([^"\r\n;]+)"?""", RegexOption.IGNORE_CASE)
    private val CT_RE = Regex("""content-type\s*:\s*([^\r\n;]+)""", RegexOption.IGNORE_CASE)

    data class Bodies(val text: String, val html: String)

    fun sanitize(content: String?): Bodies {
        if (content.isNullOrBlank()) return Bodies("", "")

        // marker 优先取 boundary= 声明;脏数据没有声明 → 用首行 --xxx 推断
        val declared = BOUNDARY_RE.find(content)?.groupValues?.get(1)
        val marker: String = when {
            !declared.isNullOrBlank() -> "--$declared"
            else -> content.lineSequence().firstOrNull()
                ?.takeIf { it.startsWith("--") && it.length > 4 } ?: ""
        }
        if (marker.isBlank()) return Bodies(content.trim(), "")
        // marker 必须出现 ≥2 次才可信是 multipart(防普通文本以 -- 开头误判)
        val first = content.indexOf(marker)
        if (first < 0 || content.indexOf(marker, first + marker.length) < 0) {
            return Bodies(content.trim(), "")
        }

        var text = ""; var html = ""
        val parts = content.split(marker).drop(1).filter { !it.startsWith("--") }
        for (part in parts) {
            // part 头与正文以空行分隔,CRLF / LF 都接受
            val idxCrlf = part.indexOf("\r\n\r\n")
            val idxLf = part.indexOf("\n\n")
            val (sepIdx, sepLen) = when {
                idxCrlf >= 0 && (idxLf < 0 || idxCrlf <= idxLf) -> idxCrlf to 4
                idxLf >= 0 -> idxLf to 2
                else -> -1 to 0
            }
            if (sepIdx < 0) continue
            val headerBlock = part.substring(0, sepIdx)
            val body = part.substring(sepIdx + sepLen).trimEnd('\r', '\n', ' ', '\t')
            val pCt = (CT_RE.find(headerBlock)?.groupValues?.get(1) ?: "").lowercase()
            when {
                pCt.startsWith("text/plain") && text.isEmpty() -> text = body
                pCt.startsWith("text/html") && html.isEmpty() -> html = body
            }
        }
        return Bodies(text.trim(), html.trim())
    }
}