package com.lingion.mailgofer.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lingion.mailgofer.api.MailGoferApi
import com.lingion.mailgofer.data.MailboxSession
import com.lingion.mailgofer.data.ServerConfig
import com.lingion.mailgofer.data.SessionStore
import com.lingion.mailgofer.data.SettingsStore
import com.lingion.mailgofer.model.CreateMailboxRequest
import com.lingion.mailgofer.model.Message
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val settingsStore = SettingsStore(app)
    private val sessionStore = SessionStore(app)

    val config: StateFlow<ServerConfig> = settingsStore.config
        .stateIn(viewModelScope, SharingStarted.Eagerly, ServerConfig())

    val session: StateFlow<MailboxSession> = sessionStore.session
        .stateIn(viewModelScope, SharingStarted.Eagerly, MailboxSession())

    // ── UI state ──────────────────────────────────────────────────────────
    val name = MutableStateFlow("")
    val domain = MutableStateFlow("")
    val ttlHours = MutableStateFlow("1")
    val maxMessages = MutableStateFlow("5")

    val messages = MutableStateFlow<List<Message>>(emptyList())
    val busy = MutableStateFlow(false)
    val toast = MutableStateFlow<String?>(null)
    val autoRefresh = MutableStateFlow(true)

    private var pollJob: Job? = null

    init {
        // 个人定制版首启预填(BuildConfig → DataStore 一次)
        viewModelScope.launch { settingsStore.applyPresetsIfNeeded() }
        // 配置里的默认域名回填
        viewModelScope.launch {
            config.collect { domain.value = domain.value.ifBlank { it.domain } }
        }
        // session 有邮箱 → 开轮询;没邮箱 → 停
        viewModelScope.launch {
            session.collect { s ->
                if (s.isValid()) startPolling(s.email) else stopPolling()
            }
        }
    }

    private fun api(): MailGoferApi? {
        val c = config.value
        if (!c.isComplete()) {
            toast.value = "先到设置页填好地址和 API Token"
            return null
        }
        return MailGoferApi(c.baseUrl(), c.apiToken)
    }

    fun saveConfig(c: ServerConfig, onDone: () -> Unit) {
        viewModelScope.launch {
            settingsStore.save(c)
            toast.value = "配置已保存"
            onDone()
        }
    }

    /** 创建邮箱(名字留空 → 服务端自动生成 mbx_xxx) */
    fun createMailbox() {
        val api = api() ?: return
        val ttlH = ttlHours.value.toLongOrNull()?.coerceIn(1, 72) ?: 1L
        val max = maxMessages.value.toIntOrNull()?.coerceIn(1, 100) ?: 5
        viewModelScope.launch {
            busy.value = true
            try {
                val mailbox = api.createMailbox(
                    CreateMailboxRequest(
                        name = name.value.trim().lowercase().ifBlank { null },
                        domain = domain.value.trim().lowercase().ifBlank { null },
                        ttlHours = ttlH.toInt(),
                        maxMessages = max,
                    )
                )
                sessionStore.save(mailbox)
                messages.value = emptyList()
                toast.value = "邮箱已就绪: ${mailbox.email}"
            } catch (e: Exception) {
                val msg = e.message ?: e.javaClass.simpleName
                toast.value = "创建失败: $msg"
            } finally {
                busy.value = false
            }
        }
    }

    fun refresh() {
        val s = session.value
        if (!s.isValid()) return
        val api = api() ?: return
        viewModelScope.launch {
            busy.value = true
            try {
                val list = api.mailboxMessages(s.email)
                messages.value = list.messages
            } catch (e: Exception) {
                toast.value = "拉取失败: ${e.message}"
            } finally {
                busy.value = false
            }
        }
    }

    /** 10s 轮询当前邮箱 */
    private fun startPolling(email: String) {
        stopPolling()
        pollJob = viewModelScope.launch {
            while (isActive) {
                if (autoRefresh.value) {
                    try {
                        val c = config.value
                        if (c.isComplete()) {
                            val api = MailGoferApi(c.baseUrl(), c.apiToken)
                            messages.value = api.mailboxMessages(email).messages
                        }
                    } catch (_: Exception) { /* 静默,下轮重试 */ }
                }
                delay(10_000)
            }
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
        messages.value = emptyList()
    }

    fun abandonMailbox() {
        viewModelScope.launch {
            sessionStore.clear()
            toast.value = "已放弃当前邮箱(服务端到期自动清理)"
        }
    }

    fun consumeToast() {
        toast.value = null
    }
}
