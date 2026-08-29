package com.lingion.mailgofer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** 创建邮箱底部弹层:mode 0=单个 · 1=批量(前缀-N) */
@OptIn(ExperimentalMaterial3Api::class)
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
    val created by vm.createdCount.collectAsState()

    // 打开时的基线:创建成功(createdCount 增长)→ 自动收起
    val baseline = remember { created }
    LaunchedEffect(created) { if (created > baseline) onDismiss() }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
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
                onClick = { if (mode == 0) vm.createSingle() else vm.createBatch() },
                enabled = !busy,
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
