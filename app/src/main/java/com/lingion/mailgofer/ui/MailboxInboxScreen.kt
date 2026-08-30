package com.lingion.mailgofer.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
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
import com.lingion.mailgofer.data.CachedMessage
import com.lingion.mailgofer.data.MailboxLogic
import com.lingion.mailgofer.format.MimeSanitizer
import com.lingion.mailgofer.format.OtpExtractor
import com.lingion.mailgofer.format.Rfc2047

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MailboxInboxScreen(
    vm: AppViewModel,
    address: String,
    onBack: () -> Unit,
    onOpenMessage: (CachedMessage) -> Unit,
    onOpenArchive: () -> Unit,
) {
    val mailbox = vm.mailboxes.collectAsState().value.firstOrNull { it.address == address }
    val messages by vm.inboxMessages.collectAsState()
    val busy by vm.busy.collectAsState()
    val toast by vm.toast.collectAsState()
    val autoRefresh by vm.autoRefresh.collectAsState()

    val clipboard = LocalClipboardManager.current
    val snackbar = remember { SnackbarHostState() }
    var showRefreshConfirm by remember { mutableStateOf(false) }
    // 长按菜单挂靠的邮件 / 待确认删除的邮件(侧滑到位与长按菜单「删除」共用同一个确认框)
    var menuFor by remember { mutableStateOf<CachedMessage?>(null) }
    var pendingDelete by remember { mutableStateOf<CachedMessage?>(null) }

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
                    IconButton(onClick = onOpenArchive) {
                        Icon(Icons.Default.Archive, "归档")
                    }
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
                    items(messages, key = { it.messageKey }) { msg ->
                        // 长按菜单锚定在本行(SwipeToDismissBox 外包一层 Box 作锚点)
                        Box {
                            SwipeableMessageRow(
                                msg = msg,
                                onArchive = { vm.archiveMessage(msg.messageKey) },
                                onDelete = { pendingDelete = msg },
                                // 标读统一走详情页 LaunchedEffect(MessageScreen.kt),此处不重复标
                                onClick = { onOpenMessage(msg) },
                                onLongClick = { menuFor = msg },
                            ) {
                                MessageRow(msg, snackbar)
                            }
                            DropdownMenu(
                                expanded = menuFor == msg,
                                onDismissRequest = { menuFor = null }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("归档") },
                                    onClick = {
                                        menuFor?.let { vm.archiveMessage(it.messageKey) }
                                        menuFor = null
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("删除") },
                                    onClick = {
                                        pendingDelete = menuFor
                                        menuFor = null
                                    }
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
            text = { Text("「$address」的云端旧邮件会被清空并重新开始计数;手机本地缓存的邮件会保留。确定继续?") },
            confirmButton = {
                TextButton(onClick = {
                    showRefreshConfirm = false
                    vm.refreshMailbox(address)
                }) { Text("刷新(云端清空)") }
            },
            dismissButton = {
                TextButton(onClick = { showRefreshConfirm = false }) { Text("取消") }
            }
        )
    }

    // 删除二选确认框: 侧滑到位与长按菜单「删除」都汇到这里,确认前不真删
    pendingDelete?.let { msg ->
        MessageDeleteDialog(vm = vm, msg = msg, onDismiss = { pendingDelete = null })
    }
}

/** 删除二选确认框(收件箱/归档页共用): 仅本地删(软删 tombstone) / 本地+云端都删 / 取消 */
@Composable
fun MessageDeleteDialog(vm: AppViewModel, msg: CachedMessage, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("删除这封邮件?") },
        text = { Text(Rfc2047.decode(msg.subject) ?: "(无主题)") },
        confirmButton = {
            Row {
                TextButton(onClick = {
                    vm.deleteMessageLocal(msg.messageKey)
                    onDismiss()
                }) { Text("仅本地删") }
                TextButton(onClick = {
                    vm.deleteMessageEverywhere(msg)
                    onDismiss()
                }) { Text("本地+云端都删") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/** 邮件列表行(收件箱/归档页共用): 与详情页同一渲染管线(主题解码 + 脏 MIME 清洗 + OTP 识别); 不可点 — 点击/长按由 SwipeableMessageRow 统一接管 */
@Composable
fun MessageRow(msg: CachedMessage, snackbar: SnackbarHostState) {
    val displaySubject = Rfc2047.decode(msg.subject)
    val bodies = MimeSanitizer.sanitize(msg.content)
    val plain = bodies.text.ifBlank {
        msg.content?.takeIf { it.isNotBlank() && !it.contains("--") }
            ?: bodies.html.replace(Regex("<[^>]+>"), " ")
    }
    val otp = OtpExtractor.extract(plain) ?: OtpExtractor.extract(bodies.html)
    ElevatedCard(Modifier.fillMaxWidth()) {
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

/**
 * 可侧滑的邮件行(收件箱/归档页共用)。
 * [swapActions] = false(收件箱): 右滑(StartToEnd)=归档(Archive, primary)、左滑(EndToStart)=删除;
 * [swapActions] = true(归档页, 方向-动作绑定整条互换):
 * 左滑(EndToStart)=取消归档(Unarchive, primary)、右滑(StartToEnd)=删除。
 *
 * 复位方案(实测选择):
 * - 归档/取消归档 confirmValueChange 返回 true → 行滑出 dismissed 位,Room Flow 把该 state 的行
 *   从对应 Flow 移除,列表自动收走该项,无需手动 reset;
 * - 删除 confirmValueChange 返回 false → 手势被否决,行自动弹回 Settled(确认框期间行保持原位,
 *   用户点「取消」也无残留),真删交由确认框回调,行同样随 Flow 消失。
 * 因此两个方向都不需要 snapTo/LaunchedEffect 复位管道。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SwipeableMessageRow(
    msg: CachedMessage,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    swapActions: Boolean = false,
    content: @Composable () -> Unit,
) {
    // 方向-动作绑定: 默认 primary(归档/取消归档)=右滑;归档页整条互换后 primary=左滑
    val primarySwipe =
        if (swapActions) SwipeToDismissBoxValue.EndToStart else SwipeToDismissBoxValue.StartToEnd
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { v ->
            when (v) {
                primarySwipe -> {
                    onArchive() // 归档页此回调即取消归档
                    true
                }
                SwipeToDismissBoxValue.Settled -> false
                // 删除方向: 不真删,只弹确认框;返回 false 让行弹回原位
                else -> {
                    onDelete()
                    false
                }
            }
        },
        positionalThreshold = { totalDistance -> totalDistance * 0.45f },
    )
    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            val isPrimary = dismissState.dismissDirection == primarySwipe
            val icon = when {
                isPrimary && swapActions -> Icons.Default.Unarchive
                isPrimary -> Icons.Default.Archive
                else -> Icons.Default.Delete
            }
            val bg =
                if (isPrimary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
            // 露出侧跟着几何走: 右滑(StartToEnd)露左侧、左滑(EndToStart)露右侧
            val align =
                if (dismissState.dismissDirection == SwipeToDismissBoxValue.StartToEnd)
                    Alignment.CenterStart
                else Alignment.CenterEnd
            Box(
                Modifier
                    .fillMaxSize()
                    .background(bg)
                    .padding(horizontal = 20.dp),
                contentAlignment = align
            ) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onError)
            }
        },
    ) {
        // 点击/长按只挂在卡片本体上(不覆盖侧滑背景区);MessageRow 本体已不可点,不会抢事件
        Box(
            Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
        ) { content() }
    }
}
