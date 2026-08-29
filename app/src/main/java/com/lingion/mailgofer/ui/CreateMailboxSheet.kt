package com.lingion.mailgofer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imeNestedScroll
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.dialog
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * 创建邮箱底部弹层:mode 0=单个 · 1=批量(前缀-N)
 *
 * 容器机制(主窗口 overlay,不走 Dialog/M3 ModalBottomSheet 任何路线):
 *  - 本 Composable 是普通函数,被 MailboxListScreen 用 `if (showCreate) CreateMailboxSheet(...)`
 *    直接放进主窗口的 Compose 树里,所以它就是主窗口的子节点——没有独立 Window、没有 decorFits、
 *    没有 setSoftInputMode 钩子可挂。
 *  - MainActivity 已 `enableEdgeToEdge()` + AndroidManifest
 *    `android:windowSoftInputMode="adjustResize"`。edge-to-edge 让系统把 ime insets 通过
 *    Compose 的 WindowInsets 树广播到所有子 Composable;adjustResize 让主 Activity 的内容
 *    实际尺寸随键盘弹出/收起而缩放。
 *  - 因此 `WindowInsets.ime` 是实时更新的 Compose insets;Surface 外面套
 *    `Modifier.imePadding()` 会自动响应:键盘弹出时 imePadding 把整个 Surface 顶到键盘正上方,
 *    键盘收起时 imePadding=0,Surface 回到 BottomCenter;`navigationBarsPadding()` 保证
 *    键盘没弹出时按钮也不会被底部手势条遮挡。
 *  - 不依赖任何 Dialog/WindowManager:Dialog 子窗口的 WindowInsets.ime 经常传不进 Compose 内容
 *    (上一版踩过),M3 ModalBottomSheet 1.3.0 强制写 SOFT_INPUT_ADJUST_NOTHING(参见 b/270581191),
 *    两条路线都坏。直接走主窗口 = insets 路径最短、最稳。
 *
 * 结构:中间滚动区(weight(1f, fill=false),键盘收起时弹层贴内容高度)+ 固定底栏。
 * 按钮在 verticalScroll 列外:Surface 被 imePadding 顶上去时,滚动区自己滚,按钮始终在 Surface
 * 底部=键盘正上方。
 *
 * 加固点:
 *  - BackHandler:弹层开着时系统返回键关弹层。与系统键盘收起的先后次序由 OnBackPressedDispatcher
 *    分发顺序决定,不同系统实现可能有差异,不在此处硬编码假设;随组合生命周期自动注销,弹层收起即失效。
 *  - Surface 挂 `semantics { dialog() }`:无障碍树里标记为对话框(TalkBack 可识别上下文)。
 *    用 dialog()(IsDialog)而非 Role.Dialog——compose-ui 1.7.4 的 Role 枚举没有 Dialog 值,
 *    Compose 官方 Dialog 同样走 IsDialog。
 *  - 滚动列挂 imeNestedScroll():表单滚到底继续上滑联动收键盘。no-op 边界在 SDK_INT<30——
 *    IME 动画联动协议(WindowInsetsAnimation)是 API 30 才有的能力,与 ime inset 数值无关
 *    (API 26-29 一样有 ime insets,只是没有这套动画协议)。
 */
@OptIn(ExperimentalLayoutApi::class) // imeNestedScroll 在 foundation 1.7.4 仍停实验期
@Composable
fun CreateMailboxSheet(vm: AppViewModel, onDismiss: () -> Unit) {
    var mode by remember { mutableIntStateOf(0) }
    val name by vm.name.collectAsState()
    val batchPrefix by vm.batchPrefix.collectAsState()
    val batchCount by vm.batchCount.collectAsState()
    val domain by vm.domain.collectAsState()
    val ttlHours by vm.ttlHours.collectAsState()
    val maxMessages by vm.maxMessages.collectAsState()
    val busy by vm.busy.collectAsState()
    val batchProgress by vm.batchProgress.collectAsState()
    val toast by vm.toast.collectAsState()
    val created by vm.createdCount.collectAsState()

    val sheetSnackbar = remember { SnackbarHostState() }
    LaunchedEffect(toast) {
        toast?.let {
            sheetSnackbar.showSnackbar(it)
            vm.consumeToast()
        }
    }

    // 打开时的基线:创建成功(createdCount 增长)→ 自动收起
    val baseline = remember { created }
    LaunchedEffect(created) { if (created > baseline) onDismiss() }

    // 返回键关弹层:随本组合注册,收起即注销;键盘开着时 back 先归系统收键盘
    BackHandler { onDismiss() }

    // 约束二选一:至少填一项;留空的那个 = 不限(永不过期 / 无限收信)——按钮区也要读,放函数级作用域
    val constraintsOk = com.lingion.mailgofer.data.MailboxLogic.validateConstraints(ttlHours, maxMessages)

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.4f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onDismiss() },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Surface(
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { dialog() } // 无障碍:标记为对话框上下文(IsDialog)
                .imePadding()
                .navigationBarsPadding()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { /* 拦截点击,不让关掉弹层 */ },
        ) {
            Column(Modifier.fillMaxWidth()) {
                // 中间滚动区:weight(1f, fill=false) — 键盘收起时弹层贴内容高度
                Column(
                    Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                        .imeNestedScroll() // 滚到底继续上滑联动收键盘;SDK<30 无 IME 动画协议,自动 no-op
                        .padding(horizontal = 16.dp)
                        .padding(top = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = mode == 0, onClick = { mode = 0 }, label = { Text("单个") })
                        FilterChip(selected = mode == 1, onClick = { mode = 1 }, label = { Text("批量创建") })
                    }

                    if (mode == 0) {
                        OutlinedTextField(
                            value = name,
                            onValueChange = { vm.name.value = it },
                            label = { Text("邮箱名(留空自动生成)") },
                            supportingText = { Text("规则: ^[a-z0-9_-]{6,40}$") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        OutlinedTextField(
                            value = batchPrefix,
                            onValueChange = { vm.batchPrefix.value = it.lowercase() },
                            label = { Text("名称前缀") },
                            supportingText = { Text("将创建 前缀-1 … 前缀-N,整体须满足 6~40 位") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = batchCount,
                            onValueChange = { vm.batchCount.value = it.filter(Char::isDigit).take(2) },
                            label = { Text("数量(1~30)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        batchProgress?.let { (done, total) ->
                            Text("进度: $done / $total", style = MaterialTheme.typography.bodySmall)
                        }
                    }

                    OutlinedTextField(
                        value = domain,
                        onValueChange = { vm.domain.value = it },
                        label = { Text("域名(须为服务端根域或其子域)") },
                        placeholder = { Text("mail.example.com") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    // 约束二选一:至少填一项;留空的那个 = 不限(永不过期 / 无限收信)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(
                            value = ttlHours,
                            onValueChange = { vm.ttlHours.value = it.filter(Char::isDigit) },
                            label = { Text("有效期(小时)") },
                            supportingText = { Text("留空=永不过期") },
                            isError = !constraintsOk,
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = maxMessages,
                            onValueChange = { vm.maxMessages.value = it.filter(Char::isDigit) },
                            label = { Text("最大邮件数") },
                            supportingText = { Text("留空=无限收信") },
                            isError = !constraintsOk,
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    if (!constraintsOk) {
                        Text(
                            "两项至少填一项(0 或留空 = 该项不限)",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                // 底部固定区:在 verticalScroll 列外,Surface 被 imePadding 顶上去时钉在 Surface 底=键盘正上方
                HorizontalDivider()
                Box(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Button(
                        onClick = { if (mode == 0) vm.createSingle() else vm.createBatch() },
                        enabled = !busy && constraintsOk,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (busy) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(if (busy) "创建中…" else if (mode == 0) "创建临时邮箱" else "批量创建")
                    }
                }
            }
        }
        // 弹层内自渲染 toast:主屏 Scaffold 的 snackbarHost 在全屏 scrim 之下,会被遮罩盖死,
        // 用户在弹层内点了「创建」(配置不全)零反馈;这里独立消费 toas,保证用户任何时候都看得到
        SnackbarHost(sheetSnackbar, modifier = Modifier.imePadding())
    }
}
