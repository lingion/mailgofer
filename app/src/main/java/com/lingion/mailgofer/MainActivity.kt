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
import com.lingion.mailgofer.model.Message
import com.lingion.mailgofer.ui.AppViewModel
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
    var selectedMessage = remember { mutableStateOf<Message?>(null) }
    var selectedAddress = remember { mutableStateOf<String?>(null) }

    NavHost(navController = nav, startDestination = "main") {
        composable("main") {
            MailboxListScreen(
                vm = vm,
                onOpenSettings = { nav.navigate("settings") },
                onOpenInbox = { address ->
                    selectedAddress.value = address
                    nav.navigate("inbox")
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
                }
            )
        }
        composable("message") {
            MessageScreen(message = selectedMessage.value ?: Message(), onBack = { nav.popBackStack() })
        }
    }
}
