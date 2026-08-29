package com.lingion.mailgofer.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lingion.mailgofer.model.Message

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: AppViewModel, onOpenSettings: () -> Unit, onOpenMessage: (Message) -> Unit) {
    val session by vm.session.collectAsState()
    val messages by vm.messages.collectAsState()
    val busy by vm.busy.collectAsState()
    val toast by vm.toast.collectAsState()
    val autoRefresh by vm.autoRefresh.collectAsState()

    val name by vm.name.collectAsState()
    val domain by vm.domain.collectAsState()
    val ttlHours by vm.ttlHours.collectAsState()
    val maxMessages by vm.maxMessages.collectAsState()

    val clipboard = LocalClipboardManager.current
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(toast) {
        toast?.let {
            snackbar.showSnackbar(it)
            vm.consumeToast()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("MailGofer") },
                actions = {
                    IconButton(onClick = { vm.refresh() }, enabled = session.isValid()) {
                        Icon(Icons.Default.Refresh, "刷新")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, "设置")
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
            if (!session.isValid()) {
                // ── 创建邮箱表单 ──
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = name,
                        onValueChange = { vm.name.value = it },
                        label = { Text("邮箱名(留空自动生成)") },
                        supportingText = { Text("规则: ^[a-z0-9_-]{6,40}$") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = domain,
                        onValueChange = { vm.domain.value = it },
                        label = { Text("域名(须为服务端根域或其子域)") },
                        placeholder = { Text("mail.example.com") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(
                            value = ttlHours,
                            onValueChange = { vm.ttlHours.value = it.filter(Char::isDigit) },
                            label = { Text("有效期(小时)") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = maxMessages,
                            onValueChange = { vm.maxMessages.value = it.filter(Char::isDigit) },
                            label = { Text("最大邮件数") },
                            supportingText = { Text("收满自动清空") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Button(
                        onClick = { vm.createMailbox() },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (busy) {
                            CircularProgressIndicator(
                                Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(if (busy) "创建中…" else "创建临时邮箱")
                    }
                    Spacer(Modifier.height(24.dp))
                }
            } else {
                // ── 邮箱信息 + 邮件列表 ──
                Spacer(Modifier.height(8.dp))
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                session.email,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = {
                                clipboard.setText(AnnotatedString(session.email))
                            }) { Text("复制") }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "到期 ${session.expiresAt.ifBlank { "?" }} · 收满${session.maxMessages}封自动清空",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = autoRefresh, onCheckedChange = { vm.autoRefresh.value = it })
                                Text("10s自动", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        if (session.token.isNotBlank()) {
                            Text(
                                "mailbox token(供脚本用,读信仍需 API Token): ${session.token.take(8)}…",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        TextButton(onClick = { vm.abandonMailbox() }) {
                            Text("放弃此邮箱")
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
                        items(messages, key = { it.id ?: it.hashCode().toString() }) { msg ->
                            Card(
                                onClick = { onOpenMessage(msg) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(
                                        msg.fromAddress ?: "(未知发件人)",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        msg.subject ?: "(无主题)",
                                        style = MaterialTheme.typography.titleSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        msg.content.orEmpty().lineSequence().firstOrNull().orEmpty(),
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
    }
}
