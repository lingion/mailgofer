package com.lingion.mailgofer.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lingion.mailgofer.api.MailGoferApi
import com.lingion.mailgofer.data.CachedMessage
import com.lingion.mailgofer.data.MailGoferDb
import com.lingion.mailgofer.data.MailboxLogic
import com.lingion.mailgofer.data.MailboxRepository
import com.lingion.mailgofer.data.MessageState
import com.lingion.mailgofer.data.ServerConfig
import com.lingion.mailgofer.data.SessionStore
import com.lingion.mailgofer.data.SettingsStore
import com.lingion.mailgofer.data.StoredMailbox
import com.lingion.mailgofer.model.CreateMailboxRequest
import com.lingion.mailgofer.model.Mailbox
import com.lingion.mailgofer.model.Message
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class) // flatMapLatest
class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val settingsStore = SettingsStore(app)
    private val sessionStore = SessionStore(app)
    private val repo = MailboxRepository(app)
    private val dao = MailGoferDb.get(app).cachedMessageDao()

    val config: StateFlow<ServerConfig> = settingsStore.config
        .stateIn(viewModelScope, SharingStarted.Eagerly, ServerConfig())

    val mailboxes: StateFlow<List<StoredMailbox>> = repo.mailboxes
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // ── UI state ──────────────────────────────────────────────────────────
    val activeAddress = MutableStateFlow<String?>(null)

    // 收件箱/归档数据源: openInbox/openArchive 切换地址后收集的 Room Flow(唯一真源是 DB)
    val inboxMessages = MutableStateFlow<List<CachedMessage>>(emptyList())
    val archiveMessages = MutableStateFlow<List<CachedMessage>>(emptyList())
    private var inboxJob: Job? = null
    private var archiveJob: Job? = null

    // 列表页未读徽标: 每个地址一条 DAO.unreadCountFlow,合并成 Map(真值在本地 DB)
    val unreadCounts: StateFlow<Map<String, Int>> = unreadCountsFlow()

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

    /**
     * 列表页未读徽标数据源: 每个地址一条 DAO.unreadCountFlow,聚合进 Map<地址, 未读数>。
     * 邮箱列表变化(新增/移除)时经 flatMapLatest 换订阅;任何一条计数变化都重发整张 Map。
     */
    private fun unreadCountsFlow(): StateFlow<Map<String, Int>> =
        mailboxes
            .flatMapLatest { mbs ->
                if (mbs.isEmpty()) flowOf(emptyMap())
                else {
                    // 显式 types: Iterable<Flow<Int>> 重载的 transform 收 Array<Int>
                    val flows: List<Flow<Int>> = mbs.map { mb -> dao.unreadCountFlow(mb.address) }
                    val addresses: List<String> = mbs.map { it.address }
                    combine(flows) { counts: Array<Int> ->
                        addresses.zip(counts.toList()) { addr, n -> addr to n }.toMap()
                    }
                }
            }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

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

    /** isExpired 只看地址+expiresAt,给轮询同步处的轻量视图用 */
    private fun Mailbox.toLocalView() = StoredMailbox(
        address = email ?: address ?: "",
        expiresAt = expiresAt,
    )

    private fun commonRequest(nameOrNull: String?): CreateMailboxRequest? {
        // 约束二选一:ttl 与 max 至少一项 > 0;留空 = 不限(永不过期/无限收信)
        if (!MailboxLogic.validateConstraints(ttlHours.value, maxMessages.value)) return null
        // 前端权威:表单填多少就发多少,不静默钳制(说1000封就是1000)
        val (ttlH, max) = MailboxLogic.requestConstraints(ttlHours.value, maxMessages.value)
        return CreateMailboxRequest(
            name = nameOrNull,
            domain = domain.value.trim().lowercase().ifBlank { null },
            ttlHours = ttlH,
            maxMessages = max,
        )
    }

    /** 约束摘要文案(创建成功 toast 回显,让用户立刻核对生效标准) */
    private fun constraintSummary(req: CreateMailboxRequest): String = buildString {
        append(if (req.ttlHours != null) "有效期${req.ttlHours}小时" else "永不过期")
        append(if ((req.maxMessages ?: 0) > 0) " · 收满${req.maxMessages}封清空" else " · 无限收信")
    }

    /** 单个创建(名字留空 → 服务端自动生成 mbx_xxx) */
    fun createSingle() {
        val api = api() ?: return
        val req = commonRequest(name.value.trim().lowercase().ifBlank { null })
        if (req == null) {
            toast.value = "有效期和最大邮件数至少填一项(留空的那个=不限)"
            return
        }
        busy.value = true
        viewModelScope.launch {
            try {
                val mb = api.createMailbox(req)
                repo.add(mb.toStored())
                name.value = ""
                createdCount.value += 1
                toast.value = "邮箱已就绪(${constraintSummary(req)}): ${mb.email ?: mb.address}"
            } catch (e: Exception) {
                toast.value = "创建失败: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                busy.value = false
            }
        }
    }

    /** 批量创建 prefix-1 … prefix-N,串行请求,失败不中断。
     *  数量按表单值诚实拒绝(超出范围给错误,不静默钳制)——前端权威禁钳制的对应面:1~30 是服务端的语义,
     *  我们如实回报「不在 1~30」而不是偷偷改成 1 或 30。 */
    fun createBatch() {
        val api = api() ?: return
        val n = batchCount.value.toIntOrNull()
        if (n == null || n !in 1..30) {
            toast.value = "数量填 1~30(表单填的值未通过校验)"
            return
        }
        val names = try {
            MailboxLogic.batchNames(batchPrefix.value, n)
        } catch (e: IllegalArgumentException) {
            toast.value = e.message
            return
        }
        val baseReq = commonRequest(null)
        if (baseReq == null) {
            toast.value = "有效期和最大邮件数至少填一项(留空的那个=不限)"
            return
        }
        busy.value = true
        viewModelScope.launch {
            var ok = 0
            val failed = mutableListOf<String>()
            names.forEachIndexed { i, nm ->
                try {
                    val req = baseReq.copy(name = nm, label = "批量")
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
                val summary = constraintSummary(baseReq)
                toast.value = if (failed.isEmpty()) "已创建 $ok 个邮箱($summary)"
                else "成功 $ok($summary) · 失败 ${failed.size}: ${failed.take(3).joinToString("、")}"
            } else {
                toast.value = "全部失败(共 ${failed.size} 个),检查配置后重试"
            }
        }
    }

    // ── 收件箱(本地 DB 真源 + 云端增量同步)──────────────────────────────

    fun openInbox(address: String) {
        activeAddress.value = address
        inboxJob?.cancel()
        inboxJob = viewModelScope.launch {
            dao.inboxFlow(address).collect { inboxMessages.value = it }
        }
        fetchActive() // 拉增量落库;未读标记改由「点开单封」驱动,不再打开即全读
    }

    fun closeInbox() {
        activeAddress.value = null
        inboxJob?.cancel()
        inboxJob = null
        inboxMessages.value = emptyList()
    }

    fun openArchive(address: String) {
        archiveJob?.cancel()
        archiveJob = viewModelScope.launch {
            dao.archiveFlow(address).collect { archiveMessages.value = it }
        }
    }

    fun closeArchive() {
        archiveJob?.cancel()
        archiveJob = null
        archiveMessages.value = emptyList()
    }

    fun refreshActive() = fetchActive()

    private fun fetchActive() {
        val addr = activeAddress.value ?: return
        val api = api() ?: return
        busy.value = true
        viewModelScope.launch {
            try {
                val list = api.mailboxMessages(addr)
                syncIntoCache(addr, list.messages)
                // brief 现在带 expires_at/active/max_messages,顺手同步状态(过期即标)
                list.mailbox?.let { brief ->
                    repo.updateAll {
                        MailboxLogic.syncFromServer(
                            it, addr,
                            expiresAt = brief.expiresAt,
                            active = (brief.active ?: 1) != 0,
                            maxMessages = brief.maxMessages ?: 0,
                        )
                    }
                }
            } catch (e: MailGoferApi.ApiException) {
                if (e.code == 410) markExpired(addr)
                toast.value = when (e.code) {
                    410 -> "此邮箱已过期(旧邮件已被服务端清空),可点刷新重新启用"
                    else -> "拉取失败: ${e.message}"
                }
            } catch (e: Exception) {
                toast.value = "拉取失败: ${e.message}"
            } finally {
                busy.value = false
            }
        }
    }

    /** 云端列表 → 缓存行,增量落库: 新 key 插入(未读),已有 key 只刷正文(state/unread 不动) */
    private suspend fun syncIntoCache(address: String, list: List<Message>) {
        val incoming = list.map { MailboxLogic.toCached(address, it) }
        if (incoming.isEmpty()) return
        val keys = incoming.map { it.messageKey }.toSet()
        if (keys.isEmpty()) return // messageKey 恒非空,纯防御(空 set 会让 IN () 报语法错)
        val cachedRows = dao.byKeys(keys)
        val plan = MailboxLogic.planSync(cachedRows.associateBy { it.messageKey }, incoming)
        dao.upsertAll(plan.toInsert)
        plan.toRefresh.forEach { r ->
            dao.refreshBody(r.messageKey, r.fromAddress, r.subject, r.content, r.htmlContent, r.timestamp)
        }
    }

    private suspend fun markExpired(address: String) {
        repo.updateAll { list -> list.map { if (it.address == address) it.copy(active = false) else it } }
    }

    // ── 单封邮件操作(本地 DB 即时生效,云端删失败则保留并可重试)──────────

    /** 归档: 收件箱消失,归档页可见 */
    fun archiveMessage(key: String) {
        viewModelScope.launch { dao.setState(key, MessageState.ARCHIVED) }
    }

    /** 取消归档: 回收件箱 */
    fun unarchiveMessage(key: String) {
        viewModelScope.launch { dao.setState(key, MessageState.INBOX) }
    }

    /** 仅本地删除(云端可能还在,服务端到期后自然消失) */
    fun deleteMessageLocal(key: String) {
        viewModelScope.launch { dao.deleteLocal(key) }
    }

    /** 本地删 + 云端删;external_id 键拿不到云端 id 时只做本地隐藏并明说 */
    fun deleteMessageEverywhere(msg: CachedMessage) {
        viewModelScope.launch {
            val cloudId = MailboxLogic.cloudIdFor(msg.mailboxAddress, msg.messageKey)
            if (cloudId == null) {
                // external_id 键: 客户端没有数字 id,不猜不试,只隐藏并告知
                dao.deleteLocal(msg.messageKey)
                toast.value = "该邮件暂不支持云端删除,仅本地隐藏"
                return@launch
            }
            val api = api() ?: return@launch
            try {
                api.deleteEmail(cloudId)
                dao.deleteLocal(msg.messageKey)
                toast.value = "已删除(本地+云端)"
            } catch (e: Exception) {
                toast.value = "云端删除失败: ${e.message},邮件保留,可重试"
            }
        }
    }

    /** 点开详情时标记已读(未读徽标的唯一衰减来源) */
    fun markMessageRead(key: String) {
        viewModelScope.launch { dao.markRead(key) }
    }

    /**
     * 刷新邮箱:清空旧邮件 + 重新激活 + 按邮箱【自己的原标准】续期。
     * UI 层调用前必须已向用户确认"旧邮件会全部丢失"。
     * 原标准 = 从 created_at→expires_at 反推原 ttl;永不过期邮箱不传 ttl(服务端沿用 null)。
     * 不读创建表单的值——表单只属于创建,"刷新换个标准"不在刷新语义里。
     */
    fun refreshMailbox(address: String) {
        val api = api() ?: return
        busy.value = true
        viewModelScope.launch {
            try {
                val local = mailboxes.value.firstOrNull { it.address == address }
                val ttlMinutes = local?.let { MailboxLogic.originalTtlMinutes(it.createdAt, it.expiresAt) }
                val req = ttlMinutes?.let {
                    CreateMailboxRequest(ttlMinutes = it.toInt(), maxMessages = local.maxMessages.takeIf { m -> m > 0 })
                }
                val mb = api.refreshMailbox(address, req)
                repo.updateAll { list ->
                    MailboxLogic.replaceOrAppend(list, mb.toStored())
                }
                // 服务端旧邮件已清空 → 本地缓存同步清掉,别让已删邮件还躺在收件箱
                dao.deleteAllForMailbox(address)
                if (activeAddress.value == address) fetchActive()
                val max = mb.maxMessages ?: 0
                val constraint = buildString {
                    append(MailboxLogic.formatExpiry(mb.expiresAt))
                    append(if (max > 0) " · 收满${max}封清空" else " · 无限收信")
                }
                toast.value = "邮箱已刷新(按原标准:$constraint)"
            } catch (e: MailGoferApi.ApiException) {
                if (e.code == 410) markExpired(address)
                toast.value = if (e.code == 410) "邮箱已过期且刷新失败,请重试" else "刷新失败: ${e.message}"
            } catch (e: Exception) {
                toast.value = "刷新失败: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                busy.value = false
            }
        }
    }

    fun removeMailbox(address: String) {
        viewModelScope.launch {
            repo.remove(address)
            dao.deleteAllForMailbox(address) // 邮箱没了,它的本地缓存一并清
            if (activeAddress.value == address) closeInbox()
            toast.value = "已移除 $address(服务端到期自动清理)"
        }
    }

    // ── 全量轮询:先同步一次邮箱状态(过期/active),再对活跃邮箱逐个拉信,间隔 10s ──

    private fun startGlobalPolling() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (isActive) {
                if (autoRefresh.value) {
                    val c = config.value
                    if (c.isComplete()) {
                        val api = MailGoferApi(c.baseUrl(), c.apiToken)
                        // ① 状态同步:GET /api/mailboxes 一次拿到所有邮箱的 expires_at/active,
                        //    过期的立即标灰,不再对它逐封轮询
                        try {
                            val all = api.listMailboxes().mailboxes
                            repo.updateAll { list ->
                                list.fold(list) { acc, local ->
                                    val remote = all.firstOrNull { (it.email ?: it.address) == local.address }
                                    if (remote == null) acc
                                    else MailboxLogic.syncFromServer(
                                        acc, local.address,
                                        expiresAt = remote.expiresAt,
                                        active = (remote.active ?: 1) != 0 && !MailboxLogic.isExpired(remote.toLocalView()),
                                        maxMessages = remote.maxMessages ?: 0,
                                    )
                                }
                            }
                        } catch (_: Exception) { /* 静默,下轮重试 */ }
                        // ② 只对仍活跃的邮箱拉信,增量落库(DB Flow 自动推到 UI)
                        for (mb in mailboxes.value.filter { it.active }) {
                            try {
                                val r = api.mailboxMessages(mb.address)
                                syncIntoCache(mb.address, r.messages)
                            } catch (e: MailGoferApi.ApiException) {
                                if (e.code == 410) markExpired(mb.address) // 服务端说过期 → 本地标记
                                /* 其余静默,下轮重试 */
                            } catch (_: Exception) { }
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
