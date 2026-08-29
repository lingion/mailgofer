package com.lingion.mailgofer.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import com.lingion.mailgofer.data.ServerConfig

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: AppViewModel, onBack: () -> Unit) {
    val config by vm.config.collectAsState()
    val toast by vm.toast.collectAsState()

    var host by remember(config) { mutableStateOf(config.host) }
    var port by remember(config) { mutableStateOf(config.port) }
    var token by remember(config) { mutableStateOf(config.apiToken) }
    var domain by remember(config) { mutableStateOf(config.domain) }

    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(toast) {
        toast?.let {
            testResult = it
            vm.consumeToast()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
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
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Spacer(Modifier.height(4.dp))
            OutlinedTextField(
                value = host,
                onValueChange = { host = it },
                label = { Text("服务地址") },
                placeholder = { Text("api.example.com(可含 https://)") },
                supportingText = { Text("不带 scheme 时默认 https") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = port,
                onValueChange = { port = it.filter { c -> c.isDigit() } },
                label = { Text("端口(可选)") },
                placeholder = { Text("默认 443") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                label = { Text("API Token") },
                supportingText = { Text("worker 的 API_TOKEN secret,全部接口鉴权用") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = domain,
                onValueChange = { domain = it },
                label = { Text("默认邮箱域名(可选)") },
                placeholder = { Text("mail.example.com") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = {
                        val c = ServerConfig(host, port, token, domain)
                        if (c.host.isBlank()) {
                            testResult = "先填服务地址"
                            return@OutlinedButton
                        }
                        testing = true
                        testResult = null
                        // 用临时配置直接测 /health(免鉴权)
                        val api = com.lingion.mailgofer.api.MailGoferApi(c.baseUrl(), token.ifBlank { "-" })
                        scope.launch {
                            val h = api.health()
                            testing = false
                            testResult = if (h.ok) "连接 OK (HTTP ${h.httpCode})" else "连不上 (HTTP ${h.httpCode})"
                        }
                    },
                    enabled = !testing
                ) {
                    if (testing) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("测试连接")
                }
                Button(
                    onClick = { vm.saveConfig(ServerConfig(host, port, token, domain)) { onBack() } }
                ) { Text("保存") }
            }

            testResult?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            }

            Text(
                "MailGofer 是部署在你自己 Cloudflare 账号上的一次性邮箱服务。" +
                    "服务地址即 worker 域名(如 api.xxx.workers.dev 或自定义域)。" +
                    "API Token 仅存在本机 DataStore,不会外发到任何第三方。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}
