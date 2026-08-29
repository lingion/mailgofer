package com.lingion.mailgofer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** 复制到剪贴板 + Snackbar 反馈的共享动作 */
@Composable
private fun rememberCopyOtp(code: String, hostState: SnackbarHostState?): () -> Unit {
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    return {
        clipboard.setText(AnnotatedString(code))
        hostState?.let { host ->
            scope.launch { host.showSnackbar("验证码 $code 已复制") }
        }
    }
}

/**
 * 验证码胶囊 + 复制按钮。收件箱预览行右侧用紧凑版(code + copy icon),
 * 详情页顶部用完整版(带"验证码"标签)。复制后 Snackbar 反馈。
 * 紧凑版不自带 SnackbarHost,由使用方提供 hostState 才有反馈。
 */
@Composable
fun OtpChip(code: String, compact: Boolean = false, hostState: SnackbarHostState? = null) {
    val copy = rememberCopyOtp(code, hostState)
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.primaryContainer
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier
                    .padding(start = 10.dp)
                    .semantics { contentDescription = "验证码 $code" }
        ) {
            if (!compact) {
                Text(
                    "验证码",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(
                code,
                style = if (compact) MaterialTheme.typography.labelLarge else MaterialTheme.typography.titleMedium,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            // 48dp 触摸热区:IconButton 默认 48dp,去掉多余 padding 保证点得中
            IconButton(onClick = copy, modifier = Modifier.size(40.dp)) {
                Icon(
                    Icons.Default.ContentCopy,
                    contentDescription = "复制验证码 $code",
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.height(if (compact) 14.dp else 18.dp)
                )
            }
        }
    }
}

/** 详情页顶部验证码条: 一行说明 + 胶囊 */
@Composable
fun OtpBanner(code: String) {
    val hostState = remember { SnackbarHostState() }
    val copy = rememberCopyOtp(code, hostState)
    Box {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Column {
                    Text(
                        "检测到验证码",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        code,
                        style = MaterialTheme.typography.headlineSmall,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                IconButton(onClick = copy) {
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = "复制验证码 $code",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }
        SnackbarHost(hostState, Modifier.align(Alignment.BottomCenter))
    }
}
