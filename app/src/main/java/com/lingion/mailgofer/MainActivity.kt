package com.lingion.mailgofer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.lingion.mailgofer.data.CachedMessage
import com.lingion.mailgofer.ui.AppViewModel
import com.lingion.mailgofer.ui.ArchiveScreen
import com.lingion.mailgofer.ui.MailboxListScreen
import com.lingion.mailgofer.ui.MailboxInboxScreen
import com.lingion.mailgofer.ui.MessageScreen
import com.lingion.mailgofer.ui.SettingsScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppNav()
                }
            }
        }
    }
}

@Composable
fun AppNav() {
    val vm: AppViewModel = viewModel()
    val nav = rememberNavController()
    // 进程内 holder:详情页/收件箱参数用状态传递(邮件体太大不走 route)
    var selectedMessage = remember { mutableStateOf<CachedMessage?>(null) }
    var selectedAddress = remember { mutableStateOf<String?>(null) }

    NavHost(navController = nav, startDestination = "main") {
        composable("main") {
            MailboxListScreen(
                vm = vm,
                onOpenSettings = { nav.navigate("settings") },
                onOpenInbox = { address ->
                    selectedAddress.value = address
                    nav.navigate("inbox")
                },
                onOpenArchive = { address ->
                    selectedAddress.value = address
                    nav.navigate("inbox/archive")
                }
            )
        }
        composable("settings") {
            SettingsScreen(vm = vm, onBack = { nav.popBackStack() })
        }
        composable("inbox") {
            MailboxInboxScreen(
                vm = vm,
                address = selectedAddress.value ?: "",
                onBack = { nav.popBackStack() },
                onOpenMessage = { msg ->
                    selectedMessage.value = msg
                    nav.navigate("message")
                },
                onOpenArchive = { nav.navigate("inbox/archive") }
            )
        }
        // 归档页路由(brief 里的 inbox/{address}/archive):地址走进程内 holder 传递,与 inbox 同模式
        composable("inbox/archive") {
            ArchiveScreen(
                vm = vm,
                address = selectedAddress.value ?: "",
                onBack = { nav.popBackStack() },
                onOpenMessage = { msg ->
                    selectedMessage.value = msg
                    nav.navigate("message")
                }
            )
        }
        composable("message") {
            MessageScreen(
                message = selectedMessage.value ?: CachedMessage(messageKey = "", mailboxAddress = ""),
                onBack = { nav.popBackStack() },
                onMarkRead = { vm.markMessageRead(it) },
            )
        }
    }
}
