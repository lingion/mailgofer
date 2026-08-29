package com.lingion.mailgofer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lingion.mailgofer.data.MailboxLogic
import com.lingion.mailgofer.format.MimeSanitizer
import com.lingion.mailgofer.format.OtpExtractor
import com.lingion.mailgofer.format.Rfc2047
import com.lingion.mailgofer.model.Message

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MailboxInboxScreen(
    vm: AppViewModel,
    address: String,
    onBack: () -> Unit,
    onOpenMessage: (Message) -> Unit,
) {
    val mailbox = vm.mailboxes.collectAsState().value.firstOrNull { it.address == address }
    val messages by vm.messages.collectAsState()
    val busy by vm.busy.collectAsState()
    val toast by vm.toast.collectAsState()
    val autoRefresh by vm.autoRefresh.collectAsState()

    val clipboard = LocalClipboardManager.current
    val snackbar = remember { SnackbarHostState() }
    var showRefreshConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(toast) {
        toast?.let {
            snackbar.showSnackbar(it)
            vm.consumeToast()
        }
    }
    LaunchedEffect(address) { vm.openInbox(address) }
    DisposableEffect(address) { onDispose { vm.closeInbox() } }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
                },
                title = {
                    Text(
                        address,
                        style = MaterialTheme.typography.titleSmall,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                actions = {
                    IconButton(onClick = { vm.refreshActive() }) {
                        Icon(Icons.Default.Refresh, "刷新")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(8.dp))
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val expired = mailbox != null && MailboxLogic.isExpired(mailbox)
                    val inactive = mailbox != null && !mailbox.active
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            when {
                                expired || inactive -> "★ 已过期 · 收信已停,点下方「刷新邮箱」恢复"
                                else -> buildString {
                                    append(MailboxLogic.formatExpiry(mailbox?.expiresAt))
                                    val max = mailbox?.maxMessages ?: 0
                                    append(if (max > 0) " · 收满${max}封自动清空" else " · 无限收信")
                                }
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (expired || inactive) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = autoRefresh, onCheckedChange = { vm.autoRefresh.value = it })
                            Text("10s自动", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (!mailbox?.token.isNullOrBlank()) {
                        Text(
                            "mailbox token(供脚本用,读信仍需 API Token): ${mailbox!!.token.take(8)}…",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Row {
                        TextButton(onClick = { clipboard.setText(AnnotatedString(address)) }) {
                            Text("复制地址")
                        }
                        TextButton(onClick = { showRefreshConfirm = true }) {
                            Text(if (expired || inactive) "刷新邮箱(恢复收信)" else "刷新邮箱")
                        }
                        TextButton(onClick = {
                            vm.removeMailbox(address)
                            onBack()
                        }) { Text("放弃此邮箱") }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))

            if (messages.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (busy) "加载中…" else "暂无邮件,等一封试试",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(messages, key = { it.idString ?: "idx-${it.hashCode()}" }) { msg ->
                        // 列表行渲染管线与详情页一致: 主题解码 + 脏 MIME 清洗 + OTP 识别
                        val displaySubject = Rfc2047.decode(msg.subject)
                        val bodies = MimeSanitizer.sanitize(msg.content)
                        val plain = bodies.text.ifBlank {
                            msg.content?.takeIf { it.isNotBlank() && !it.contains("--") }
                                ?: bodies.html.replace(Regex("<[^>]+>"), " ")
                        }
                        val otp = OtpExtractor.extract(plain) ?: OtpExtractor.extract(bodies.html)
                        Card(
                            onClick = { onOpenMessage(msg) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        msg.fromAddress ?: "(未知发件人)",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (otp != null) {
                                        Spacer(Modifier.width(8.dp))
                                        // 预览页直接复制验证码,无需进详情页
                                        OtpChip(otp, compact = true, hostState = snackbar)
                                    }
                                }
                                Text(
                                    displaySubject ?: "(无主题)",
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    plain.lineSequence().firstOrNull().orEmpty(),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    msg.createdAt ?: "",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showRefreshConfirm) {
        AlertDialog(
            onDismissRequest = { showRefreshConfirm = false },
            title = { Text("刷新此邮箱?") },
            text = { Text("「$address」的全部旧邮件会被清空并重新开始计数,确定继续?") },
            confirmButton = {
                TextButton(onClick = {
                    showRefreshConfirm = false
                    vm.refreshMailbox(address)
                }) { Text("刷新(清空旧邮件)") }
            },
            dismissButton = {
                TextButton(onClick = { showRefreshConfirm = false }) { Text("取消") }
            }
        )
    }
}
