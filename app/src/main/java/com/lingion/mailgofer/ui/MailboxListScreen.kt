package com.lingion.mailgofer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lingion.mailgofer.data.MailboxLogic
import com.lingion.mailgofer.data.StoredMailbox

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MailboxListScreen(
    vm: AppViewModel,
    onOpenSettings: () -> Unit,
    onOpenInbox: (String) -> Unit,
) {
    val mailboxes by vm.mailboxes.collectAsState()
    val unreadCounts by vm.unreadCounts.collectAsState()
    val toast by vm.toast.collectAsState()
    var showCreate by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }

    // 弹层开着时主屏不消费 toast(弹层内 SnackbarHost 独享,避免两处并发消费竞态:
    // 主屏 snackbar 被 scrim 盖住看不见,却抢先 consumeToast 让弹层那条被取消=零反馈)
    LaunchedEffect(toast, showCreate) {
        if (showCreate) return@LaunchedEffect
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
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, "设置")
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showCreate = true },
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text("新建邮箱") }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { pad ->
        if (mailboxes.isEmpty()) {
            Column(
                Modifier
                    .padding(pad)
                    .fillMaxSize()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("还没有邮箱", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text(
                    "点右下角「新建邮箱」创建一个,或一次性批量建一批",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                Modifier
                    .padding(pad)
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item { Spacer(Modifier.height(4.dp)) }
                items(mailboxes, key = { it.address }) { mb ->
                    MailboxCard(
                        mailbox = mb,
                        unread = unreadCounts[mb.address] ?: 0, // 未读真值在本地 DB,不再用 StoredMailbox.unread
                        expired = MailboxLogic.isExpired(mb),
                        onClick = { onOpenInbox(mb.address) },
                        onRemove = { vm.removeMailbox(mb.address) },
                        onRefresh = { vm.refreshMailbox(mb.address) }
                    )
                }
                item { Spacer(Modifier.height(72.dp)) } // 给 FAB 留空间
            }
        }
    }

    if (showCreate) CreateMailboxSheet(vm, onDismiss = { showCreate = false })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MailboxCard(
    mailbox: StoredMailbox,
    unread: Int,
    expired: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    onRefresh: () -> Unit,
) {
    var confirmRefresh by remember { mutableStateOf(false) }
    ElevatedCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (expired || !mailbox.active) {
                        // 过期/失效红星警告(用户要求:过期标红星或警告符号)
                        Text(
                            "★",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(
                        mailbox.address,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    buildString {
                        append(if (expired || !mailbox.active) "已过期" else "★")
                        // 约束语义描述:两项都可能不限
                        if (!expired && mailbox.active) {
                            append(MailboxLogic.formatExpiry(mailbox.expiresAt))
                            if (mailbox.maxMessages > 0) append(" · 收满${mailbox.maxMessages}封清空") else append(" · 无限收信")
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (expired || !mailbox.active) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (unread > 0) {
                Box(
                    Modifier
                        .size(28.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (unread > 99) "99+" else "$unread",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }
            IconButton(onClick = { confirmRefresh = true }) {
                Icon(Icons.Default.Refresh, "刷新邮箱", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onRemove) {
                Icon(Icons.Default.Close, "移除", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (confirmRefresh) {
        AlertDialog(
            onDismissRequest = { confirmRefresh = false },
            title = { Text("刷新此邮箱?") },
            text = { Text("「${mailbox.address}」的全部旧邮件会被清空并重新开始计数,确定继续?") },
            confirmButton = {
                TextButton(onClick = {
                    confirmRefresh = false
                    onRefresh()
                }) { Text("刷新(清空旧邮件)") }
            },
            dismissButton = {
                TextButton(onClick = { confirmRefresh = false }) { Text("取消") }
            }
        )
    }
}
