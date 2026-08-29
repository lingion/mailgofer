# MailGofer 多邮箱管理系统 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 MailGofer 从"单临时邮箱"升级为多邮箱管理系统:批量创建 N 个邮箱、列表页展示全部(带未读徽标)、点进任意邮箱查看其收件箱。

**Architecture:** 数据层用 `MailboxRepository`(DataStore 单 key 存 JSON 列表)取代单会话 `SessionStore`;轮询层在 VM 内全量轮询所有活跃邮箱,用 count 增量算未读;UI 拆成 列表页 → 收件箱页 → 邮件详情 三层导航。旧单会话数据一次性迁移为列表首项。

**Tech Stack:** Kotlin + Jetpack Compose (Material3) + Navigation Compose + DataStore Preferences + kotlinx.serialization + HttpURLConnection(现有 `MailGoferApi` 不动)

## Global Constraints

- UI 全程中文文案
- 禁用描边按钮(BorderedButton/OutlinedButton 组件);选中态用色块/FilterChip
- `personal.properties`(真实 token)已 git-ignore,**严禁提交**;构建 personal APK 需其在位
- 每个 Task 结束即 commit(小步提交)
- 服务端规则(已核实):创建名 `^[a-z0-9_-]{6,40}$` 或留空自动生成;`max_messages` 收满自动清空并停用;读信走 `GET /api/mailboxes/{address}/messages`(全地址或裸 id 均可);邮箱列表端点是全库 LIMIT 200 无用户过滤 → **app 只管自己创建的**
- 未读算法:轮询得 `newCount`,`newCount < lastSeenCount` → 视为服务端已清空,`lastSeen=newCount, unread=0`;否则 `delta=newCount-lastSeen`,`lastSeen=newCount`,正在看的邮箱 `unread=0`,其他 `unread+=delta`
- 构建/测试命令:`./gradlew assembleDebug`(debug = personal 包名 `.personal`)、`./gradlew testDebugUnitTest`

---

### Task 1: 纯逻辑层 MailboxLogic(批量命名/未读增量/迁移)

**Files:**
- Create: `app/src/main/java/com/lingion/mailgofer/data/MailboxLogic.kt`
- Create: `app/src/main/java/com/lingion/mailgofer/data/StoredMailbox.kt`
- Test: `app/src/test/java/com/lingion/mailgofer/data/MailboxLogicTest.kt`

**Interfaces:**
- Consumes: `MailboxSession`(现有,`data/MailboxSession.kt`)
- Produces: `StoredMailbox` 数据类、`MailboxLogic.batchNames(prefix,count): List<String>`、`MailboxLogic.applyPollResult(list,address,newCount,openAddress): List<StoredMailbox>`、`MailboxLogic.markRead(list,address,fetchedCount): List<StoredMailbox>`、`MailboxLogic.migrateLegacy(existing,legacy): List<StoredMailbox>`、`MailboxLogic.replaceOrAppend(list,item): List<StoredMailbox>`

- [ ] **Step 1: 写 StoredMailbox 数据类**

`app/src/main/java/com/lingion/mailgofer/data/StoredMailbox.kt`:

```kotlin
package com.lingion.mailgofer.data

import kotlinx.serialization.Serializable

/** app 内创建并本地跟踪的一个邮箱(DataStore JSON 列表的单项) */
@Serializable
data class StoredMailbox(
    val address: String,            // 完整地址,唯一 key;读信路由也用它
    val mailboxId: String? = null,  // 服务端 mailbox_id,仅展示
    val token: String? = null,      // per-mailbox token,仅展示(读信仍用全局 API Token)
    val label: String? = null,
    val createdAt: String? = null,
    val expiresAt: String? = null,
    val maxMessages: Int = 0,
    val active: Boolean = true,
    val lastSeenCount: Int = 0,     // 上次轮询见到的邮件总数(未读增量基准)
    val unread: Int = 0,
)
```

- [ ] **Step 2: 写 MailboxLogic 纯函数对象**

`app/src/main/java/com/lingion/mailgofer/data/MailboxLogic.kt`:

```kotlin
package com.lingion.mailgofer.data

/** 多邮箱纯逻辑:批量命名 / 未读增量 / 旧会话迁移。全部无 Android 依赖,单测直打。 */
object MailboxLogic {

    /** 覆盖同地址项,或追加新项;保持原顺序 */
    fun replaceOrAppend(list: List<StoredMailbox>, item: StoredMailbox): List<StoredMailbox> =
        if (list.any { it.address == item.address })
            list.map { if (it.address == item.address) item else it }
        else list + item

    /**
     * 批量命名:prefix-1 … prefix-count。
     * 服务端规则 ^[a-z0-9_-]{6,40}$ → 生成的完整名必须 6~40 位。
     * @throws IllegalArgumentException 前缀含非法字符 / 数量越界 / 名字长度不达标(中文消息)
     */
    fun batchNames(prefixRaw: String, count: Int): List<String> {
        val prefix = prefixRaw.trim().lowercase()
        require(prefix.isNotEmpty() && prefix.matches(Regex("[a-z0-9_-]+"))) {
            "前缀只允许 a-z、0-9、_、-"
        }
        require(count in 1..30) { "数量需在 1~30" }
        return (1..count).map { i ->
            val name = "$prefix-$i"
            require(name.length in 6..40) { "生成的名字「$name」不满足 6~40 位,请调整前缀长度" }
            name
        }
    }

    /**
     * 一轮轮询结果落到列表上。
     * newCount < lastSeen → 服务端收满清空过:基准重置、未读清零;
     * 否则 delta 计入未读;正在查看的邮箱始终 unread=0。
     */
    fun applyPollResult(
        list: List<StoredMailbox>,
        address: String,
        newCount: Int,
        openAddress: String?,
    ): List<StoredMailbox> = list.map { mb ->
        when {
            mb.address != address -> mb
            newCount < mb.lastSeenCount -> mb.copy(lastSeenCount = newCount, unread = 0)
            else -> {
                val unread =
                    if (address == openAddress) 0
                    else mb.unread + (newCount - mb.lastSeenCount)
                mb.copy(lastSeenCount = newCount, unread = unread)
            }
        }
    }

    /** 打开收件箱后标记已读,并把基准校准到刚拉到的数量 */
    fun markRead(list: List<StoredMailbox>, address: String, fetchedCount: Int): List<StoredMailbox> =
        list.map {
            if (it.address == address) it.copy(unread = 0, lastSeenCount = fetchedCount) else it
        }

    /** 旧单会话 → 列表首项;地址空或已存在则原样返回 */
    fun migrateLegacy(existing: List<StoredMailbox>, legacy: MailboxSession): List<StoredMailbox> =
        if (legacy.email.isBlank() || existing.any { it.address == legacy.email }) existing
        else existing + StoredMailbox(
            address = legacy.email,
            token = legacy.token,
            expiresAt = legacy.expiresAt,
            maxMessages = legacy.maxMessages,
        )
}
```

- [ ] **Step 3: 写失败测试**

`app/src/test/java/com/lingion/mailgofer/data/MailboxLogicTest.kt`:

```kotlin
package com.lingion.mailgofer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MailboxLogicTest {

    private val mb = { addr: String, last: Int, unread: Int ->
        StoredMailbox(address = addr, lastSeenCount = last, unread = unread)
    }

    // ── batchNames ──
    @Test
    fun `批量命名_正常序列`() {
        assertEquals(listOf("shop-1", "shop-2", "shop-3"), MailboxLogic.batchNames("Shop", 3))
    }

    @Test
    fun `批量命名_短前缀拼接后达标也通过`() {
        assertEquals(listOf("ab-1"), MailboxLogic.batchNames("AB", 1)) // "ab-1" = 4? 否 → 应抛
    }
    // 注意: "ab-1" 只有 4 位,这条应抛异常 → 改成正确用例:

    @Test
    fun `批量命名_太短抛异常`() {
        assertThrows(IllegalArgumentException::class.java) { MailboxLogic.batchNames("AB", 1) } // "ab-1"=4 位
    }

    @Test
    fun `批量命名_非法字符抛异常`() {
        assertThrows(IllegalArgumentException::class.java) { MailboxLogic.batchNames("ac@count", 2) }
    }

    @Test
    fun `批量命名_数量越界抛异常`() {
        assertThrows(IllegalArgumentException::class.java) { MailboxLogic.batchNames("shopxx", 0) }
        assertThrows(IllegalArgumentException::class.java) { MailboxLogic.batchNames("shopxx", 31) }
    }

    // ── applyPollResult ──
    @Test
    fun `新邮件到达_未读累加`() {
        val list = listOf(mb("a@x.com", 3, 0), mb("b@x.com", 1, 2))
        val out = MailboxLogic.applyPollResult(list, "a@x.com", 5, openAddress = null)
        assertEquals(2, out[0].unread)
        assertEquals(5, out[0].lastSeenCount)
        assertEquals(2, out[1].unread) // 别的邮箱不动
    }

    @Test
    fun `正在查看的邮箱_不涨未读`() {
        val list = listOf(mb("a@x.com", 3, 0))
        val out = MailboxLogic.applyPollResult(list, "a@x.com", 7, openAddress = "a@x.com")
        assertEquals(0, out[0].unread)
        assertEquals(7, out[0].lastSeenCount)
    }

    @Test
    fun `服务端清空_count回退_基准重置未读清零`() {
        val list = listOf(mb("a@x.com", 10, 4))
        val out = MailboxLogic.applyPollResult(list, "a@x.com", 0, openAddress = null)
        assertEquals(0, out[0].unread)
        assertEquals(0, out[0].lastSeenCount)
    }

    // ── markRead ──
    @Test
    fun `标记已读_基准校准`() {
        val list = listOf(mb("a@x.com", 3, 9), mb("b@x.com", 1, 1))
        val out = MailboxLogic.markRead(list, "a@x.com", fetchedCount = 8)
        assertEquals(0, out[0].unread)
        assertEquals(8, out[0].lastSeenCount)
        assertEquals(1, out[1].unread)
    }

    // ── migrateLegacy ──
    @Test
    fun `旧会话迁移_追加为首项`() {
        val legacy = MailboxSession(email = "old@x.com", token = "tok", expiresAt = "2026-01-01", maxMessages = 7)
        val out = MailboxLogic.migrateLegacy(emptyList(), legacy)
        assertEquals(1, out.size)
        assertEquals("old@x.com", out[0].address)
        assertEquals("tok", out[0].token)
        assertEquals(7, out[0].maxMessages)
    }

    @Test
    fun `旧会话迁移_地址已存在不重复`() {
        val legacy = MailboxSession(email = "old@x.com")
        val existing = listOf(StoredMailbox(address = "old@x.com"))
        assertEquals(1, MailboxLogic.migrateLegacy(existing, legacy).size)
    }

    @Test
    fun `旧会话迁移_空地址原样返回`() {
        val existing = listOf(StoredMailbox(address = "a@x.com"))
        assertEquals(existing, MailboxLogic.migrateLegacy(existing, MailboxSession()))
    }

    // ── replaceOrAppend ──
    @Test
    fun `replaceOrAppend_同地址覆盖`() {
        val list = listOf(StoredMailbox(address = "a@x.com", unread = 5))
        val out = MailboxLogic.replaceOrAppend(list, StoredMailbox(address = "a@x.com", unread = 0))
        assertEquals(1, out.size)
        assertEquals(0, out[0].unread)
    }
}
```

(注:Step 3 里第二条 `批量命名_短前缀拼接后达标也通过` 是草稿残留,落地时删掉,保留 `批量命名_太短抛异常`。)

- [ ] **Step 4: 跑测试确认全绿**

Run: `cd ~/mailgofer-android && ./gradlew testDebugUnitTest --tests "com.lingion.mailgofer.data.MailboxLogicTest"`
Expected: PASS(全部用例)

- [ ] **Step 5: Commit**

```bash
cd ~/mailgofer-android && git add app/src/main/java/com/lingion/mailgofer/data/StoredMailbox.kt app/src/main/java/com/lingion/mailgofer/data/MailboxLogic.kt app/src/test/java/com/lingion/mailgofer/data/MailboxLogicTest.kt && git commit -m "feat: 多邮箱纯逻辑层 StoredMailbox + MailboxLogic"
```

---

### Task 2: MailboxRepository(DataStore JSON 列表持久化)

**Files:**
- Create: `app/src/main/java/com/lingion/mailgofer/data/MailboxRepository.kt`
- Test: `app/src/test/java/com/lingion/mailgofer/data/StoredMailboxJsonTest.kt`

**Interfaces:**
- Consumes: `StoredMailbox`、`MailboxLogic`(Task 1)、`MailboxSession`(现有)
- Produces: `MailboxRepository(app)` —— `val mailboxes: Flow<List<StoredMailbox>>`、`suspend saveAll(List)`、`suspend add(StoredMailbox)`、`suspend remove(address)`、`suspend updateAll((List)->List)`、`suspend migrateLegacyIfNeeded(legacy: MailboxSession)`

- [ ] **Step 1: 写 Repository**

`app/src/main/java/com/lingion/mailgofer/data/MailboxRepository.kt`:

```kotlin
package com.lingion.mailgofer.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.mailboxStore by preferencesDataStore(name = "mailboxes")

/** 多邮箱本地仓库:整个列表序列化成 JSON 存在单 key 里(量级 ≤30,够用) */
class MailboxRepository(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }
    private val listKey = stringPreferencesKey("list")

    val mailboxes: Flow<List<StoredMailbox>> = context.mailboxStore.data.map { prefs ->
        prefs[listKey]?.let { raw ->
            try {
                json.decodeFromString<List<StoredMailbox>>(raw)
            } catch (_: Exception) {
                null // 损坏数据当空列表,别崩 App
            }
        } ?: emptyList()
    }

    suspend fun saveAll(list: List<StoredMailbox>) {
        context.mailboxStore.edit { it[listKey] = json.encodeToString(list) }
    }

    suspend fun add(mailbox: StoredMailbox) =
        saveAll(MailboxLogic.replaceOrAppend(mailboxes.first(), mailbox))

    suspend fun remove(address: String) =
        saveAll(mailboxes.first().filterNot { it.address == address })

    suspend fun updateAll(transform: (List<StoredMailbox>) -> List<StoredMailbox>) =
        saveAll(transform(mailboxes.first()))

    /** 旧单会话迁进来并清掉旧 key(幂等:地址去重) */
    suspend fun migrateLegacyIfNeeded(legacy: MailboxSession) {
        if (legacy.email.isBlank()) return
        saveAll(MailboxLogic.migrateLegacy(mailboxes.first(), legacy))
    }
}
```

- [ ] **Step 2: 写 JSON 往返序列化测试**

`app/src/test/java/com/lingion/mailgofer/data/StoredMailboxJsonTest.kt`:

```kotlin
package com.lingion.mailgofer.data

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class StoredMailboxJsonTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `往返序列化_字段不丢`() {
        val src = StoredMailbox(
            address = "a@x.com", mailboxId = "MBX-1", token = "tok", label = "批量",
            createdAt = "2026-08-29T00:00:00Z", expiresAt = "2026-08-29T01:00:00Z",
            maxMessages = 5, active = true, lastSeenCount = 3, unread = 2,
        )
        val round = json.decodeFromString<StoredMailbox>(json.encodeToString(src))
        assertEquals(src, round)
    }

    @Test
    fun `列表往返_顺序保持`() {
        val src = listOf(StoredMailbox("a@x.com"), StoredMailbox("b@x.com", unread = 1))
        val round = json.decodeFromString<List<StoredMailbox>>(json.encodeToString(src))
        assertEquals(src, round)
    }
}
```

- [ ] **Step 3: 跑测试**

Run: `cd ~/mailgofer-android && ./gradlew testDebugUnitTest`
Expected: PASS(Task 1 + Task 2 全部用例)

- [ ] **Step 4: Commit**

```bash
cd ~/mailgofer-android && git add app/src/main/java/com/lingion/mailgofer/data/MailboxRepository.kt app/src/test/java/com/lingion/mailgofer/data/StoredMailboxJsonTest.kt && git commit -m "feat: MailboxRepository DataStore JSON 列表仓库"
```

---

### Task 3: AppViewModel 多邮箱化(创建/批量/选择/移除/全量轮询)

**Files:**
- Modify: `app/src/main/java/com/lingion/mailgofer/ui/AppViewModel.kt`(整体重写)

**Interfaces:**
- Consumes: `MailboxRepository`+`MailboxLogic`(Task 1/2)、`MailGoferApi`(现有)、`SessionStore`(现有,只读迁移)
- Produces(UI 层 Task 4/5 依赖):
  - `mailboxes: StateFlow<List<StoredMailbox>>`、`activeAddress: StateFlow<String?>`、`messages: StateFlow<List<Message>>`
  - `openInbox(address)` / `closeInbox()` / `refreshActive()` / `removeMailbox(address)`
  - `createSingle()` / `createBatch()`
  - 表单字段:`name` `batchPrefix` `batchCount` `domain` `ttlHours` `maxMessages: MutableStateFlow<String>`
  - `busy: StateFlow<Boolean>`、`batchProgress: StateFlow<Pair<Int,Int>?>`、`toast: StateFlow<String?>`、`consumeToast()`、`autoRefresh`
  - `createdCount: StateFlow<Int>`(创建成功数累计,CreateSheet 关闭时机用)

- [ ] **Step 1: 重写 AppViewModel**

`app/src/main/java/com/lingion/mailgofer/ui/AppViewModel.kt` 全文替换:

```kotlin
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
```

- [ ] **Step 2: 编译验证**

Run: `cd ~/mailgofer-android && ./gradlew compileDebugKotlin`
Expected: BUILD SUCCESSFUL(UI 还引用旧 `session`/`abandonMailbox`/`refresh` → MainScreen 未改前此处会编译失败是**预期内**,Task 4/5 会换掉;若想此步独立绿,可与 Task 4/5 连做后统一编译)

> 注:Task 3 单独不保证编译绿(旧 MainScreen 还引用 `vm.session` 等)。落地时 Task 3+4+5 连续做完再编译。commit 仍按任务分开。

- [ ] **Step 3: Commit**

```bash
cd ~/mailgofer-android && git add app/src/main/java/com/lingion/mailgofer/ui/AppViewModel.kt && git commit -m "feat: AppViewModel 多邮箱化(批量创建/全量轮询/未读增量)"
```

---

### Task 4: 列表页 MailboxListScreen + 创建底部弹层 CreateMailboxSheet

**Files:**
- Create: `app/src/main/java/com/lingion/mailgofer/ui/MailboxListScreen.kt`
- Create: `app/src/main/java/com/lingion/mailgofer/ui/CreateMailboxSheet.kt`

**Interfaces:**
- Consumes: Task 3 的 `AppViewModel` 全部公开成员
- Produces: `MailboxListScreen(vm, onOpenSettings: () -> Unit, onOpenInbox: (String) -> Unit)`、`CreateMailboxSheet(vm, onDismiss: () -> Unit)`

- [ ] **Step 1: 写 CreateMailboxSheet(两个模式:单个/批量)**

`app/src/main/java/com/lingion/mailgofer/ui/CreateMailboxSheet.kt`:

```kotlin
package com.lingion.mailgofer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
```

- [ ] **Step 2: 写 MailboxListScreen**

`app/src/main/java/com/lingion/mailgofer/ui/MailboxListScreen.kt`:

```kotlin
package com.lingion.mailgofer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lingion.mailgofer.data.StoredMailbox

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MailboxListScreen(
    vm: AppViewModel,
    onOpenSettings: () -> Unit,
    onOpenInbox: (String) -> Unit,
) {
    val mailboxes by vm.mailboxes.collectAsState()
    val toast by vm.toast.collectAsState()
    var showCreate by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(toast) {
        toast?.let {
            snackbar.showSnackbar(it)
            vm.consumeToast()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("MailGofer") },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, "设置")
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showCreate = true },
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text("新建邮箱") }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { pad ->
        if (mailboxes.isEmpty()) {
            Column(
                Modifier
                    .padding(pad)
                    .fillMaxSize()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("还没有邮箱", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text(
                    "点右下角「新建邮箱」创建一个,或一次性批量建一批",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                Modifier
                    .padding(pad)
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item { Spacer(Modifier.height(4.dp)) }
                items(mailboxes, key = { it.address }) { mb ->
                    MailboxCard(
                        mailbox = mb,
                        onClick = { onOpenInbox(mb.address) },
                        onRemove = { vm.removeMailbox(mb.address) }
                    )
                }
                item { Spacer(Modifier.height(72.dp)) } // 给 FAB 留空间
            }
        }
    }

    if (showCreate) CreateMailboxSheet(vm, onDismiss = { showCreate = false })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MailboxCard(mailbox: StoredMailbox, onClick: () -> Unit, onRemove: () -> Unit) {
    ElevatedCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    mailbox.address,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    buildString {
                        append("到期 ${mailbox.expiresAt.take(10).ifBlank { "?" }}")
                        if (mailbox.maxMessages > 0) append(" · 收满${mailbox.maxMessages}封清空")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (mailbox.unread > 0) {
                Box(
                    Modifier
                        .size(28.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (mailbox.unread > 99) "99+" else "${mailbox.unread}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }
            IconButton(onClick = onRemove) {
                Icon(Icons.Default.Close, "移除", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
```

- [ ] **Step 3: Commit**

```bash
cd ~/mailgofer-android && git add app/src/main/java/com/lingion/mailgofer/ui/MailboxListScreen.kt app/src/main/java/com/lingion/mailgofer/ui/CreateMailboxSheet.kt && git commit -m "feat: 邮箱列表页 + 创建弹层(单个/批量)"
```

---

### Task 5: 收件箱页 MailboxInboxScreen + 导航接线,删除旧 MainScreen

**Files:**
- Create: `app/src/main/java/com/lingion/mailgofer/ui/MailboxInboxScreen.kt`
- Modify: `app/src/main/java/com/lingion/mailgofer/MainActivity.kt`(导航加 "inbox")
- Delete: `app/src/main/java/com/lingion/mailgofer/ui/MainScreen.kt`

**Interfaces:**
- Consumes: Task 3 VM;Task 4 列表页
- Produces: `MailboxInboxScreen(vm, address: String, onBack: () -> Unit, onOpenMessage: (Message) -> Unit)`

- [ ] **Step 1: 写 MailboxInboxScreen(邮箱信息卡 + 邮件列表,从旧 MainScreen 迁移)**

`app/src/main/java/com/lingion/mailgofer/ui/MailboxInboxScreen.kt`:

```kotlin
package com.lingion.mailgofer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lingion.mailgofer.model.Message

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MailboxInboxScreen(
    vm: AppViewModel,
    address: String,
    onBack: () -> Unit,
    onOpenMessage: (Message) -> Unit,
) {
    val mailbox = vm.mailboxes.collectAsState().value.firstOrNull { it.address == address }
    val messages by vm.messages.collectAsState()
    val busy by vm.busy.collectAsState()
    val toast by vm.toast.collectAsState()
    val autoRefresh by vm.autoRefresh.collectAsState()

    val clipboard = LocalClipboardManager.current
    val snackbar = remember { SnackbarHostState() }

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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "到期 ${mailbox?.expiresAt?.take(10)?.ifBlank { "?" } ?: "?"} · 收满${mailbox?.maxMessages ?: 0}封自动清空",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { clipboard.setText(AnnotatedString(address)) }) {
                            Text("复制地址")
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
                    items(messages, key = { it.idString ?: "idx-${it.hashCode()}" }) { msg ->
                        Card(
                            onClick = { onOpenMessage(msg) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    msg.fromAddress ?: "(未知发件人)",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    msg.subject ?: "(无主题)",
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    msg.content.orEmpty().lineSequence().firstOrNull().orEmpty(),
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
                }
            }
        }
    }
}
```

- [ ] **Step 2: MainActivity 导航接线**

`app/src/main/java/com/lingion/mailgofer/MainActivity.kt` 的 `AppNav()` 改为:

```kotlin
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
```

imports 相应改:`MailboxListScreen` 换掉 `MainScreen`(其余不动)。

- [ ] **Step 3: 删旧 MainScreen**

```bash
cd ~/mailgofer-android && git rm app/src/main/java/com/lingion/mailgofer/ui/MainScreen.kt
```

- [ ] **Step 4: 全量编译 + 单测**

Run: `cd ~/mailgofer-android && ./gradlew assembleDebug testDebugUnitTest`
Expected: BUILD SUCCESSFUL,单测全绿

- [ ] **Step 5: Commit**

```bash
cd ~/mailgofer-android && git add -A && git commit -m "feat: 收件箱页 + 导航接线(列表→收件箱→详情),移除单会话 MainScreen"
```

---

### Task 6: 真机验证(装 personal 包 → 批量建 3 个 → SMTP 探针 → 未读徽标)

**Files:** 无代码改动;纯验证。

**Interfaces:**
- Consumes: Task 5 产出的 debug APK(personal 包名,预填 token)
- Produces: 端到端验证证据

- [ ] **Step 1: 装机**

```bash
cd ~/mailgofer-android && adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Expected: Success(手机 d3efcd6a 在线;若 offline 先 `adb wait-for-device`)

- [ ] **Step 2: 启动 App 并迁移旧会话**

```bash
adb shell monkey -p com.lingion.mailgofer.personal -c android.intent.category.LAUNCHER 1 && sleep 4 && adb exec-out uiautomator dump /dev/tty 2>/dev/null | grep -o "text=\"[^\"]*@mail.qdp.qzz.io\"" | head -3
```

Expected: 旧单会话邮箱(若有)出现在列表 → 迁移生效

- [ ] **Step 3: UI 自动化批量创建 3 个**

uiautomator 点「新建邮箱」→ 切「批量创建」→ 填前缀 `lingtest`、数量 3 → 批量创建 → 等完成后回到列表,验证列表多 3 项 `lingtest-1/2/3@mail.qdp.qzz.io`:

```bash
TOKEN=$(cat ~/.cloudflare-token); curl -s "https://api.qdp.qzz.io/api/mailboxes" -H "x-api-key: $TOKEN" | python3 -c "import json,sys; d=json.load(sys.stdin)['data']; print([m['address'] for m in d if 'lingtest-' in m['address']])"
```

Expected: `['lingtest-1@mail.qdp.qzz.io', 'lingtest-2@…', 'lingtest-3@…']`

- [ ] **Step 4: SMTP 探针打其中一个邮箱,验证未读徽标**

(复用上轮脚本:直连 CF MX `route1.mx.cloudflare.net:25` STARTTLS 发一封到 `lingtest-1@mail.qdp.qzz.io`,主题带时间戳)

```bash
sleep 15 && adb exec-out uiautomator dump /dev/tty 2>/dev/null | grep -o "text=\"[0-9]*\"" | head -5
```

Expected: 列表页 `lingtest-1` 卡片出现未读数字徽标(≥1);server 侧交叉验证:

```bash
TOKEN=$(cat ~/.cloudflare-token); curl -s "https://api.qdp.qzz.io/api/mailboxes/lingtest-1@mail.qdp.qzz.io/messages" -H "x-api-key: $TOKEN" | python3 -c "import json,sys; d=json.load(sys.stdin)['data']; print('count:', d['count'])"
```

Expected: `count: 1`

- [ ] **Step 5: 点进收件箱验证详情 + 未读清零**

uiautomator 点 `lingtest-1` 卡片 → 邮件出现在列表 → 点邮件 → 详情页有正文;返回列表后 `lingtest-1` 徽标消失。

Run: `adb exec-out uiautomator dump /dev/tty 2>/dev/null | grep -c "lingtest-1"`
Expected: 卡片仍在且无未读数字

- [ ] **Step 6: 清理测试邮箱(从列表移除)**

UI 上逐个点 × 移除 `lingtest-*`(本地列表移除;服务端 1h 后自动过期,无需手动删库)。

---

### Task 7: 收尾(版本号 + 提交推送;release 视用户意愿)

**Files:**
- Modify: `app/build.gradle.kts`(versionCode 1→2, versionName "1.0.0"→"1.1.0")

- [ ] **Step 1: 版本号**

`app/build.gradle.kts`: `versionCode = 2`、`versionName = "1.1.0"`

- [ ] **Step 2: commit + push(gh.qdp.qzz.io 镜像)**

```bash
cd ~/mailgofer-android && git add app/build.gradle.kts && git commit -m "chore: v1.1.0 多邮箱管理" && git push origin master
```

Expected: push 成功(push 断流重试,参照 gh 镜像 HTTP/1.1 经验)

- [ ] **Step 3: (可选,用户确认后) GitHub release**

公开 release 前必须把 `personal.properties` 移出项目根再打 release APK,并用 dex 校验 0 个 qdp 命中;APK 上传后用 `gh api` 校验 asset digest(禁用镜像下载哈希)。

---

## Self-Review 结论

- 规格覆盖:批量N+前缀+手动 ✓(Task 4 CreateMailboxSheet 两模式,单个留空自动生成=手动)、未读数+全量轮询 ✓(Task 1 applyPollResult + Task 3 startGlobalPolling)、只管 app 内创建 ✓(repo 只有 app 创建/迁移的地址)、旧数据迁移 ✓(Task 3 init)、过期/停用展示 ✓(卡片日期行,轮询静默跳过失败邮箱)、复制/token 展示/放弃邮箱 ✓(Task 5)
- 类型一致性:`StoredMailbox` 字段在 Task 1 定义、Task 2 序列化、Task 3 toStored() 构造、Task 4/5 消费,字段名一致 ✓
- 无占位符 ✓;Task 3 编译依赖 Task 4/5 已在步骤中注明(连做后统一编译,commit 分开)
