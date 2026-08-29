package com.lingion.mailgofer.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lingion.mailgofer.api.MailGoferApi
import com.lingion.mailgofer.data.MailboxLogic
import com.lingion.mailgofer.data.MailboxRepository
import com.lingion.mailgofer.data.ServerConfig
import com.lingion.mailgofer.data.SessionStore
import com.lingion.mailgofer.data.SettingsStore
import com.lingion.mailgofer.data.StoredMailbox
import com.lingion.mailgofer.model.CreateMailboxRequest
import com.lingion.mailgofer.model.Mailbox
import com.lingion.mailgofer.model.Message
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val settingsStore = SettingsStore(app)
    private val sessionStore = SessionStore(app)
    private val repo = MailboxRepository(app)

    val config: StateFlow<ServerConfig> = settingsStore.config
        .stateIn(viewModelScope, SharingStarted.Eagerly, ServerConfig())

    val mailboxes: StateFlow<List<StoredMailbox>> = repo.mailboxes
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // ── UI state ──────────────────────────────────────────────────────────
    val activeAddress = MutableStateFlow<String?>(null)
    val messages = MutableStateFlow<List<Message>>(emptyList())

    // 创建表单(单个/批量共用 domain/ttl/max)
    val name = MutableStateFlow("")
    val batchPrefix = MutableStateFlow("")
    val batchCount = MutableStateFlow("5")
    val domain = MutableStateFlow("")
    val ttlHours = MutableStateFlow("1")
    val maxMessages = MutableStateFlow("5")

    val busy = MutableStateFlow(false)
    val batchProgress = MutableStateFlow<Pair<Int, Int>?>(null) // (已完成, 总数)
    val createdCount = MutableStateFlow(0)
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
        // 旧单会话一次性迁移 → 清旧 key
        viewModelScope.launch {
            val legacy = sessionStore.session.first()
            if (legacy.email.isNotBlank()) {
                repo.migrateLegacyIfNeeded(legacy)
                sessionStore.clear()
            }
        }
        startGlobalPolling()
    }

    private fun api(): MailGoferApi? {
        val c = config.value
        if (!c.isComplete()) {
            toast.value = "先到设置页填好地址和 API Token"
            return null
        }
        return MailGoferApi(c.baseUrl(), c.apiToken)
    }

    private fun Mailbox.toStored(): StoredMailbox = StoredMailbox(
        address = email ?: address ?: "",
        mailboxId = mailboxId,
        token = token,
        label = label,
        createdAt = createdAt,
        expiresAt = expiresAt,
        maxMessages = maxMessages ?: 0,
        active = (active ?: 1) != 0,
    )

    private fun commonRequest(nameOrNull: String?): CreateMailboxRequest {
        val ttlH = ttlHours.value.toLongOrNull()?.coerceIn(1, 72) ?: 1L
        val max = maxMessages.value.toIntOrNull()?.coerceIn(1, 100) ?: 5
        return CreateMailboxRequest(
            name = nameOrNull,
            domain = domain.value.trim().lowercase().ifBlank { null },
            ttlHours = ttlH.toInt(),
            maxMessages = max,
        )
    }

    /** 单个创建(名字留空 → 服务端自动生成 mbx_xxx) */
    fun createSingle() {
        val api = api() ?: return
        viewModelScope.launch {
            busy.value = true
            try {
                val mb = api.createMailbox(commonRequest(name.value.trim().lowercase().ifBlank { null }))
                repo.add(mb.toStored())
                name.value = ""
                createdCount.value += 1
                toast.value = "邮箱已就绪: ${mb.email ?: mb.address}"
            } catch (e: Exception) {
                toast.value = "创建失败: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                busy.value = false
            }
        }
    }

    /** 批量创建 prefix-1 … prefix-N,串行请求,失败不中断 */
    fun createBatch() {
        val api = api() ?: return
        val n = batchCount.value.toIntOrNull()?.coerceIn(1, 30)
        if (n == null) {
            toast.value = "数量填 1~30"
            return
        }
        val names = try {
            MailboxLogic.batchNames(batchPrefix.value, n)
        } catch (e: IllegalArgumentException) {
            toast.value = e.message
            return
        }
        viewModelScope.launch {
            busy.value = true
            var ok = 0
            val failed = mutableListOf<String>()
            names.forEachIndexed { i, nm ->
                try {
                    val req = commonRequest(nm).copy(label = "批量")
                    val mb = api.createMailbox(req)
                    repo.add(mb.toStored())
                    ok++
                } catch (_: Exception) {
                    failed += nm
                }
                batchProgress.value = (i + 1) to n
            }
            busy.value = false
            batchProgress.value = null
            if (ok > 0) {
                createdCount.value += ok
                toast.value = if (failed.isEmpty()) "已创建 $ok 个邮箱"
                else "成功 $ok · 失败 ${failed.size}: ${failed.take(3).joinToString("、")}"
            } else {
                toast.value = "全部失败(共 ${failed.size} 个),检查配置后重试"
            }
        }
    }

    // ── 收件箱 ────────────────────────────────────────────────────────────

    fun openInbox(address: String) {
        activeAddress.value = address
        fetchActive(markRead = true)
    }

    fun closeInbox() {
        activeAddress.value = null
        messages.value = emptyList()
    }

    fun refreshActive() = fetchActive(markRead = false)

    private fun fetchActive(markRead: Boolean) {
        val addr = activeAddress.value ?: return
        val api = api() ?: return
        viewModelScope.launch {
            busy.value = true
            try {
                val list = api.mailboxMessages(addr)
                messages.value = list.messages
                if (markRead) repo.updateAll { MailboxLogic.markRead(it, addr, list.messages.size) }
            } catch (e: Exception) {
                toast.value = "拉取失败: ${e.message}"
            } finally {
                busy.value = false
            }
        }
    }

    fun removeMailbox(address: String) {
        viewModelScope.launch {
            repo.remove(address)
            if (activeAddress.value == address) closeInbox()
            toast.value = "已移除 $address(服务端到期自动清理)"
        }
    }

    // ── 全量轮询:所有活跃邮箱串行一轮,间隔 10s ─────────────────────────────

    private fun startGlobalPolling() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (isActive) {
                if (autoRefresh.value) {
                    val c = config.value
                    if (c.isComplete()) {
                        val api = MailGoferApi(c.baseUrl(), c.apiToken)
                        for (mb in mailboxes.value.filter { it.active }) {
                            try {
                                val r = api.mailboxMessages(mb.address)
                                repo.updateAll {
                                    MailboxLogic.applyPollResult(it, mb.address, r.messages.size, activeAddress.value)
                                }
                                if (mb.address == activeAddress.value) messages.value = r.messages
                            } catch (_: Exception) { /* 静默,下轮重试 */ }
                            delay(150) // 防 burst
                        }
                    }
                }
                delay(10_000)
            }
        }
    }

    fun consumeToast() {
        toast.value = null
    }
}
