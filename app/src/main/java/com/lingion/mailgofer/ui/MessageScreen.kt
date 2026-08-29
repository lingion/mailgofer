package com.lingion.mailgofer.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.lingion.mailgofer.format.MimeSanitizer
import com.lingion.mailgofer.format.OtpExtractor
import com.lingion.mailgofer.format.Rfc2047
import com.lingion.mailgofer.model.Message

/**
 * 邮件详情 — 渲染管线:
 *   1. 主题 RFC 2047 解码
 *   2. 正文: 服务端脏数据(原始 MIME 塞 content)→ sanitize 切分;干净 text 直接用
 *   3. 有 html 段 → WebView 渲染(图片/链接可看);否则纯文本
 *   4. 识别出验证码 → 顶部 OtpBanner + 复制
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageScreen(message: Message, onBack: () -> Unit) {
    val subject = Rfc2047.decode(message.subject)

    // 正文管线: 优先 content;若 content 是原始 MIME 脏数据则切分出 text/html
    val cleaned = run {
        val direct = message.content?.takeIf { it.isNotBlank() }
        if (direct != null) {
            val parsed = MimeSanitizer.sanitize(direct)
            if (parsed.text.isNotBlank() || parsed.html.isNotBlank()) parsed
            else MimeSanitizer.Bodies(
                message.htmlContent?.takeIf { it.isNotBlank() } ?: "",
                ""
            )
        } else {
            MimeSanitizer.Bodies(
                "",
                message.htmlContent?.takeIf { it.isNotBlank() } ?: ""
            )
        }
    }
    val body = cleaned.text.takeIf { it.isNotBlank() }
        ?: cleaned.html.takeIf { it.isNotBlank() }
        ?: "(空邮件)"

    val otp = OtpExtractor.extract(cleaned.text)
        ?: OtpExtractor.extract(cleaned.html)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        subject ?: "(无主题)",
                        maxLines = 1
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                }
            )
        }
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            otp?.let {
                OtpBanner(it)
            }
            Text(
                "发件人: ${message.fromAddress ?: "?"}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                "收件人: ${message.emailAddress ?: "?"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                message.createdAt ?: "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
            HorizontalDivider()
            if (cleaned.html.isNotBlank() && cleaned.text.isBlank()) {
                // 纯 HTML 邮件 → WebView 渲染(图片/链接可看可点)
                HtmlEmailView(cleaned.html)
            } else {
                SelectionContainer {
                    Text(
                        body,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}