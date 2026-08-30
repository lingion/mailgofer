package com.lingion.mailgofer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lingion.mailgofer.data.CachedMessage

/**
 * 独立归档页: 读 vm.archiveMessages(openArchive(address) 收集的 Room state=ARCHIVED Flow)。
 * 侧滑方向与收件箱互换: 左滑=取消归档(Unarchive, primary)、右滑=删除(同一套二选确认框)。
 * 长按菜单同收件箱(取消归档/删除)。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchiveScreen(
    vm: AppViewModel,
    address: String,
    onBack: () -> Unit,
    onOpenMessage: (CachedMessage) -> Unit,
) {
    val messages by vm.archiveMessages.collectAsState()
    val toast by vm.toast.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    // 长按菜单挂靠的邮件 / 待确认删除的邮件(与收件箱同一套语义)
    var menuFor by remember { mutableStateOf<CachedMessage?>(null) }
    var pendingDelete by remember { mutableStateOf<CachedMessage?>(null) }

    LaunchedEffect(toast) {
        toast?.let {
            snackbar.showSnackbar(it)
            vm.consumeToast()
        }
    }
    // archiveMessages 是单邮箱作用域(vm.openArchive 切换收集目标);列表页不消费它,离开无须恢复
    LaunchedEffect(address) { vm.openArchive(address) }
    DisposableEffect(Unit) { onDispose { vm.closeArchive() } }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
                },
                title = {
                    Text(
                        "$address 的归档",
                        style = MaterialTheme.typography.titleSmall,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
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
            if (messages.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "归档是空的,收件箱里右滑一封试试",
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
                                swapActions = true, // 归档页: 左滑=取消归档(Unarchive, primary)、右滑=删除
                                onArchive = { vm.unarchiveMessage(msg.messageKey) },
                                onDelete = { pendingDelete = msg },
                                onClick = { onOpenMessage(msg) },
                                onLongClick = { menuFor = msg },
                            ) {
                                MessageRow(msg, snackbar)
                            }
                            DropdownMenuForMessage(
                                menuFor = menuFor,
                                msg = msg,
                                onUnarchive = {
                                    menuFor?.let { vm.unarchiveMessage(it.messageKey) }
                                    menuFor = null
                                },
                                onDelete = {
                                    pendingDelete = menuFor
                                    menuFor = null
                                },
                                onDismiss = { menuFor = null },
                            )
                        }
                    }
                }
            }
        }
    }

    pendingDelete?.let { msg ->
        MessageDeleteDialog(vm = vm, msg = msg, onDismiss = { pendingDelete = null })
    }
}

/** 归档页长按菜单: 取消归档(回收件箱) / 删除(二选确认框) */
@Composable
private fun DropdownMenuForMessage(
    menuFor: CachedMessage?,
    msg: CachedMessage,
    onUnarchive: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    DropdownMenu(
        expanded = menuFor == msg,
        onDismissRequest = onDismiss
    ) {
        DropdownMenuItem(
            text = { Text("取消归档") },
            onClick = onUnarchive
        )
        DropdownMenuItem(
            text = { Text("删除") },
            onClick = onDelete
        )
    }
}
