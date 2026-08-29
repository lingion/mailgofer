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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lingion.mailgofer.model.Message

/**
 * 邮件详情 — 纯文本展示(content),html_content 只在无纯文本时降级去标签显示。
 * (WebView 内联 HTML 属于超纲,服务端 text_body 一般都有)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageScreen(message: Message, onBack: () -> Unit) {
    val body = message.content?.takeIf { it.isNotBlank() }
        ?: message.htmlContent?.replace(Regex("<[^>]+>"), " ")?.replace(Regex("\\s{2,}"), "\n")
        ?: "(空邮件)"

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        message.subject ?: "(无主题)",
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
