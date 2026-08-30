# 本地邮件缓存 + 增量同步 + 删除/归档 — 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 邮件本地 Room 缓存(历史保留)+ 按 messageKey 增量同步 + 单封删除(确认框二选:仅本地/本地+云端)+ 归档(独立页+徽标)。

**Architecture:** 新增 Room 表 cached_messages 为邮件唯一真源;AppViewModel 的 messages 从内存 StateFlow 改为 Room Flow;MailboxLogic 状态机函数(upsert/state 转移)先行 TDD,再接 UI。Spec: `docs/superpowers/specs/2026-08-30-message-cache-design.md`。

**Tech Stack:** Room 2.6.1 + KSP,现有 Compose M3 / kotlinx-serialization / JUnit4。

## Global Constraints

- gradlew 必须绝对路径: `/Users/lingion_k/mailgofer-android/gradlew -p /Users/lingion_k/mailgofer-android`
- 禁 adb/真机/截图/部署(用户自己手工验收;推手机由用户发令)
- 注释中文,禁彩色 emoji,✓✗⚠★ 文本符号可用
- 每个 commit 中文祈使句、无自指废话;功能块完成即 commit,禁攒大批
- UI 纯色块禁描边(无 BorderStroke/OutlinedButton 新增);选中态=色块
- Kotlin 2.0.21 / AGP 8.x / compose-compiler 已由 kotlin("plugin.compose") 提供

---

### Task 1: Room 依赖与 KSP 接入

**Files:**
- Modify: `/Users/lingion_k/mailgofer-android/app/build.gradle.kts`
- Modify: `/Users/lingion_k/mailgofer-android/build.gradle.kts`(根,若 KSP 需在根声明插件)

**Interfaces:**
- Produces: 项目可编译且 Room 注解处理器生效(后续任务依赖)

- [ ] **Step 1: 根 build.gradle.kts 的 plugins 段加 KSP(版本与 Kotlin 2.0.21 匹配: 2.0.21-1.0.28)**

```kotlin
plugins {
    // ...existing...
    id("com.google.devtools.ksp") version "2.0.21-1.0.28" apply false
}
```

- [ ] **Step 2: app/build.gradle.kts 加插件与依赖**

```kotlin
plugins {
    // ...existing...
    id("com.google.devtools.ksp")
}

dependencies {
    // ...existing...
    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")
}
```

- [ ] **Step 3: 验证解析与编译**

Run: `/Users/lingion_k/mailgofer-android/gradlew -p /Users/lingion_k/mailgofer-android :app:compileDebugKotlin --console=plain`
Expected: BUILD SUCCESSFUL(国内网络若解析失败,加 `maven("https://maven.aliyun.com/repository/google")` 到 settings.gradle.kts 的 repositories,或在 gradle.properties 走既有镜像)

- [ ] **Step 4: Commit**

```bash
git -C /Users/lingion_k/mailgofer-android add -A && git -C /Users/lingion_k/mailgofer-android commit -m "chore: 接入 Room 2.6.1 与 KSP"
```

---

### Task 2: CachedMessage 实体 + DAO + Database(纯声明,无逻辑)

**Files:**
- Create: `/Users/lingion_k/mailgofer-android/app/src/main/java/com/lingion/mailgofer/data/CachedMessage.kt`
- Create: `/Users/lingion_k/mailgofer-android/app/src/main/java/com/lingion/mailgofer/data/CachedMessageDao.kt`
- Create: `/Users/lingion_k/mailgofer-android/app/src/main/java/com/lingion/mailgofer/data/MailGoferDb.kt`

**Interfaces:**
- Produces: `CachedMessage` data class(字段见 spec 表);`CachedMessageDao` 关键签名:
  - `fun inboxFlow(mailboxAddress: String): Flow<List<CachedMessage>>`
  - `fun archiveFlow(mailboxAddress: String): Flow<List<CachedMessage>>`
  - `fun unreadCountFlow(mailboxAddress: String): Flow<Int>`
  - `fun archivedCountFlow(mailboxAddress: String): Flow<Int>`
  - `suspend fun upsertAll(items: List<CachedMessage>)`(IGNORE on conflict,已有 key 不覆盖)
  - `suspend fun refreshBodies(items: List<CachedMessage>)`(UPDATE 正文列,不动 state/unread)
  - `suspend fun setState(messageKey: String, newState: String)`
  - `suspend fun deleteLocal(messageKey: String)`(物理删除行)
  - `suspend fun byKey(messageKey: String): CachedMessage?`
- Produces: `MailGoferDb`(abstract class, `@Database(entities=[CachedMessage::class], version=1)`, `abstract fun cachedMessageDao(): CachedMessageDao`)

- [ ] **Step 1: 写 CachedMessage.kt**

```kotlin
package com.lingion.mailgofer.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** 邮件状态: 收件箱 / 已归档 / 仅本地删除(云端可能还在) */
object MessageState {
    const val INBOX = "INBOX"
    const val ARCHIVED = "ARCHIVED"
    const val DELETED_LOCAL = "DELETED_LOCAL"
}

/** 本地缓存的邮件 — Room 表 cached_messages,唯一真源(历史保留,云端清掉也还在) */
@Entity(
    tableName = "cached_messages",
    indices = [Index("mailboxAddress")],
)
data class CachedMessage(
    /** 增量去重锚: external_id 优先,缺省用 "$mailboxAddress:$云端id" */
    @PrimaryKey val messageKey: String,
    val mailboxAddress: String,
    val fromAddress: String? = null,
    val subject: String? = null,
    val content: String? = null,
    val htmlContent: String? = null,
    val createdAt: String? = null,
    val timestamp: Long? = null,
    val state: String = MessageState.INBOX,
    val unread: Boolean = true,
    val cachedAt: Long = 0L,
)
```

- [ ] **Step 2: 写 CachedMessageDao.kt**

```kotlin
package com.lingion.mailgofer.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CachedMessageDao {
    @Query("SELECT * FROM cached_messages WHERE mailboxAddress = :address AND state = 'INBOX' ORDER BY COALESCE(timestamp, 0) DESC, cachedAt DESC")
    fun inboxFlow(address: String): Flow<List<CachedMessage>>

    @Query("SELECT * FROM cached_messages WHERE mailboxAddress = :address AND state = 'ARCHIVED' ORDER BY COALESCE(timestamp, 0) DESC, cachedAt DESC")
    fun archiveFlow(address: String): Flow<List<CachedMessage>>

    @Query("SELECT COUNT(*) FROM cached_messages WHERE mailboxAddress = :address AND state = 'INBOX' AND unread = 1")
    fun unreadCountFlow(address: String): Flow<Int>

    @Query("SELECT COUNT(*) FROM cached_messages WHERE mailboxAddress = :address AND state = 'ARCHIVED'")
    fun archivedCountFlow(address: String): Flow<Int>

    /** 增量插入: 已有 key 忽略(state/unread 不被覆盖) */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun upsertAll(items: List<CachedMessage>)

    /** 刷新正文列(云端侧邮件内容可能被补全),不动 state/unread */
    @Query("UPDATE cached_messages SET fromAddress=:fromAddress, subject=:subject, content=:content, htmlContent=:htmlContent, timestamp=:timestamp WHERE messageKey=:messageKey")
    suspend fun refreshBody(messageKey: String, fromAddress: String?, subject: String?, content: String?, htmlContent: String?, timestamp: Long?)

    @Query("UPDATE cached_messages SET state = :newState WHERE messageKey = :messageKey")
    suspend fun setState(messageKey: String, newState: String)

    @Query("UPDATE cached_messages SET unread = 0 WHERE messageKey = :messageKey")
    suspend fun markRead(messageKey: String)

    @Query("UPDATE cached_messages SET unread = 0 WHERE mailboxAddress = :address AND state = 'INBOX'")
    suspend fun markAllRead(address: String)

    @Query("DELETE FROM cached_messages WHERE messageKey = :messageKey")
    suspend fun deleteLocal(messageKey: String)

    @Query("SELECT * FROM cached_messages WHERE messageKey = :messageKey LIMIT 1")
    suspend fun byKey(messageKey: String): CachedMessage?

    @Query("DELETE FROM cached_messages WHERE mailboxAddress = :address")
    suspend fun deleteAllForMailbox(address: String)
}
```

- [ ] **Step 3: 写 MailGoferDb.kt**

```kotlin
package com.lingion.mailgofer.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [CachedMessage::class], version = 1, exportSchema = false)
abstract class MailGoferDb : RoomDatabase() {
    abstract fun cachedMessageDao(): CachedMessageDao

    companion object {
        @Volatile private var instance: MailGoferDb? = null

        fun get(context: Context): MailGoferDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, MailGoferDb::class.java, "mailgofer.db")
                .fallbackToDestructiveMigration() // v1 无存量,后续版本禁用此行并写迁移
                .build()
                .also { instance = it }
        }
    }
}
```

- [ ] **Step 4: 编译验证**

Run: `/Users/lingion_k/mailgofer-android/gradlew -p /Users/lingion_k/mailgofer-android :app:compileDebugKotlin --console=plain`
Expected: BUILD SUCCESSFUL(Room 注解处理器生成 impl)

- [ ] **Step 5: Commit**

```bash
git -C /Users/lingion_k/mailgofer-android add -A && git -C /Users/lingion_k/mailgofer-android commit -m "feat: Room cached_messages 表+DAO+DB"
```

---

### Task 3: 增量同步状态机(TDD 纯函数)

**Files:**
- Modify: `/Users/lingion_k/mailgofer-android/app/src/main/java/com/lingion/mailgofer/data/MailboxLogic.kt`(新增函数)
- Test: `/Users/lingion_k/mailgofer-android/app/src/test/java/com/lingion/mailgofer/data/MailboxLogicTest.kt`

**Interfaces:**
- Produces: `MailboxLogic.messageKeyFor(mailboxAddress: String, id: String?, externalId: String?): String`(external_id 优先,缺省 `"$mailboxAddress:$id"`)
- Produces: `MailboxLogic.planSync(cached: Map<String, CachedMessage>, incoming: List<CachedMessage>): SyncPlan`:
  ```kotlin
  data class SyncPlan(
      val toInsert: List<CachedMessage>,   // 新 key,INBOX+unread=true
      val toRefresh: List<CachedMessage>,  // 已有 key,正文刷新(由 DAO.refreshBody 逐条执行)
  )
  ```

- [ ] **Step 1: 写失败测试(加到 MailboxLogicTest)**

```kotlin
// ── messageKeyFor / planSync(增量同步状态机)──
@Test
fun `同步键_externalId优先_缺省用地址拼id`() {
    assertEquals("ext-1", MailboxLogic.messageKeyFor("a@x.com", "9", "ext-1"))
    assertEquals("a@x.com:9", MailboxLogic.messageKeyFor("a@x.com", "9", null))
    assertEquals("a@x.com:9", MailboxLogic.messageKeyFor("a@x.com", "9", ""))
}

@Test
fun `同步计划_新邮件进插入列表_带INBOX与未读`() {
    val incoming = listOf(cached("k1"), cached("k2"))
    val plan = MailboxLogic.planSync(emptyMap(), incoming)
    assertEquals(listOf("k1", "k2"), plan.toInsert.map { it.messageKey })
    assertTrue(plan.toInsert.all { it.state == MessageState.INBOX && it.unread })
    assertTrue(plan.toRefresh.isEmpty())
}

@Test
fun `同步计划_已有key只刷新正文_不覆盖state与未读`() {
    val existing = cached("k1").copy(state = MessageState.ARCHIVED, unread = false, subject = "旧")
    val incoming = listOf(cached("k1").copy(subject = "新"))
    val plan = MailboxLogic.planSync(mapOf("k1" to existing), incoming)
    assertTrue(plan.toInsert.isEmpty())
    assertEquals(listOf("k1"), plan.toRefresh.map { it.messageKey })
    // 刷新项携带新正文
    assertEquals("新", plan.toRefresh[0].subject)
}

@Test
fun `同步计划_混合新增与刷新`() {
    val existing = cached("k1").copy(unread = false)
    val incoming = listOf(cached("k1").copy(subject = "新正文"), cached("k2"))
    val plan = MailboxLogic.planSync(mapOf("k1" to existing), incoming)
    assertEquals(listOf("k2"), plan.toInsert.map { it.messageKey })
    assertEquals(listOf("k1"), plan.toRefresh.map { it.messageKey })
}
```

辅助(测试文件顶部):

```kotlin
private fun cached(key: String) = CachedMessage(
    messageKey = key, mailboxAddress = "a@x.com", subject = "s",
    cachedAt = 0L,
)
```

(注意 import `com.lingion.mailgofer.data` 同包无需;`assertTrue` 需补 import org.junit.Assert.assertTrue)

- [ ] **Step 2: 跑测试确认红**

Run: `/Users/lingion_k/mailgofer-android/gradlew -p /Users/lingion_k/mailgofer-android :app:testDebugUnitTest --console=plain`
Expected: FAIL — `Unresolved reference 'messageKeyFor'` / `'planSync'`

- [ ] **Step 3: 在 MailboxLogic 实现**

```kotlin
/** 增量同步锚: external_id 优先,缺省用 地址:云端id 拼(云端 id 是 int 自增,跨邮箱会撞,必须带地址) */
fun messageKeyFor(mailboxAddress: String, id: String?, externalId: String?): String =
    externalId?.takeIf { it.isNotBlank() } ?: "$mailboxAddress:$id"

/** 一次增量同步计划: 新 key 插入(INBOX+未读),已有 key 只刷正文 */
fun planSync(cached: Map<String, CachedMessage>, incoming: List<CachedMessage>): SyncPlan {
    val toInsert = incoming.filter { it.messageKey !in cached }
        .map { it.copy(state = MessageState.INBOX, unread = true) }
    val toRefresh = incoming.filter { it.messageKey in cached }
    return SyncPlan(toInsert, toRefresh)
}
```

与 `data class SyncPlan(val toInsert: List<CachedMessage>, val toRefresh: List<CachedMessage>)`(放同文件或 CachedMessage.kt)。

- [ ] **Step 4: 跑测试确认绿**

Run: `/Users/lingion_k/mailgofer-android/gradlew -p /Users/lingion_k/mailgofer-android :app:testDebugUnitTest --console=plain`
Expected: PASS(全套,含原有 76 条)

- [ ] **Step 5: Commit**

```bash
git -C /Users/lingion_k/mailgofer-android add -A && git -C /Users/lingion_k/mailgofer-android commit -m "feat: 增量同步状态机 messageKeyFor+planSync(TDD)"
```

---

### Task 4: AppViewModel 接 Room(增量同步 + 真未读)

**Files:**
- Modify: `/Users/lingion_k/mailgofer-android/app/src/main/java/com/lingion/mailgofer/ui/AppViewModel.kt`
- Modify: `/Users/lingion_k/mailgofer-android/app/src/main/java/com/lingion/mailgofer/model/Models.kt`(若 Message→CachedMessage 转换放这)

**Interfaces:**
- Consumes: Task 2 的 `MailGoferDb.get(context).cachedMessageDao()`、Task 3 的 `messageKeyFor/planSync`
- Produces:
  - `val inboxMessages: StateFlow<List<CachedMessage>>`(收件箱页数据源,Room Flow)
  - `val archiveMessages: StateFlow<List<CachedMessage>>`(归档页数据源)
  - `fun openInbox(address: String)`(内部启动 Room Flow 收集 + fetchActive)
  - `fun openArchive(address: String)`
  - `fun archiveMessage(key: String)` / `fun unarchiveMessage(key: String)`
  - `fun deleteMessageLocal(key: String)`
  - `fun deleteMessageEverywhere(msg: CachedMessage)`(本地删+调 api.deleteEmail(msg.id);失败 toast 保留本地)
  - `fun markMessageRead(key: String)`(点开详情时)

- [ ] **Step 1: Message→CachedMessage 转换函数(放 MailboxLogic 或 Models 旁,带测试)**

测试先行(加到 MailboxLogicTest):

```kotlin
@Test
fun `消息转换_键生成与字段搬运`() {
    val m = mapOf(
        "id" to kotlinx.serialization.json.JsonPrimitive(9),
        "external_id" to kotlinx.serialization.json.JsonPrimitive("ext-9"),
        "from_address" to kotlinx.serialization.json.JsonPrimitive("noreply@x.com"),
        "subject" to kotlinx.serialization.json.JsonPrimitive("验证码"),
        "content" to kotlinx.serialization.json.JsonPrimitive("code 123456"),
        "created_at" to kotlinx.serialization.json.JsonPrimitive("2026-08-30T00:00:00Z"),
    )
    val c = MailboxLogic.toCached("a@x.com", m)
    assertEquals("ext-9", c.messageKey)
    assertEquals("a@x.com", c.mailboxAddress)
    assertEquals("验证码", c.subject)
}
```

实现(放 MailboxLogic):

```kotlin
/** 云端 Message(JsonElement map,兼容 int/string id)→ 本地缓存行 */
fun toCached(mailboxAddress: String, m: Map<String, kotlinx.serialization.json.JsonElement>): CachedMessage {
    fun str(k: String) = (m[k] as? kotlinx.serialization.json.JsonPrimitive)?.content
    val id = str("id")
    return CachedMessage(
        messageKey = messageKeyFor(mailboxAddress, id, str("external_id")),
        mailboxAddress = mailboxAddress,
        fromAddress = str("from_address"),
        subject = str("subject"),
        content = str("content"),
        htmlContent = str("html_content"),
        createdAt = str("created_at"),
        timestamp = str("timestamp")?.toLongOrNull(),
        cachedAt = System.currentTimeMillis(),
    )
}
```

(具体调用时用 kotlinx 序列化 `Json.decodeToJsonElement` 把 `Message` 或原始响应转 map;若直接拿 `Message` data class,改成 `toCached(address, msg: Message)` 重载,字段直取,id 用 `msg.id?.content`。二选一,以实际 API 返回处理代码为准——MailGoferApi 已把响应解成 `Message` data class,故**推荐** `toCached(address, msg: Message)` 直取字段:`msg.id?.content` 作 id,`msg.externalId` 作 externalId。)

- [ ] **Step 2: AppViewModel 改造**

关键改动(保持现有函数名不动,改内部):

```kotlin
private val dao = MailGoferDb.get(app).cachedMessageDao()

// 收件箱/归档数据源: 由 openInbox/openArchive 切换的 Room Flow
val inboxMessages = MutableStateFlow<List<CachedMessage>>(emptyList())
val archiveMessages = MutableStateFlow<List<CachedMessage>>(emptyList())
private var inboxJob: Job? = null
private var archiveJob: Job? = null

fun openInbox(address: String) {
    activeAddress.value = address
    inboxJob?.cancel()
    inboxJob = viewModelScope.launch {
        dao.inboxFlow(address).collect { inboxMessages.value = it }
    }
    fetchActive(markRead = false) // 拉增量;未读标记改由「点开单封」驱动,不再打开即全读
}

fun openArchive(address: String) {
    archiveJob?.cancel()
    archiveJob = viewModelScope.launch {
        dao.archiveFlow(address).collect { archiveMessages.value = it }
    }
}

private suspend fun syncIntoCache(address: String, list: List<Message>) {
    val incoming = list.map { MailboxLogic.toCached(address, it) }
    val keys = incoming.map { it.messageKey }.toSet()
    val cachedRows = dao.byKeys(keys) // DAO 加批量查询: WHERE messageKey IN (:keys)
    val plan = MailboxLogic.planSync(cachedRows.associateBy { it.messageKey }, incoming)
    dao.upsertAll(plan.toInsert)
    plan.toRefresh.forEach { r ->
        dao.refreshBody(r.messageKey, r.fromAddress, r.subject, r.content, r.htmlContent, r.timestamp)
    }
}
```

`fetchActive` 的 `messages.value = list.messages` 替换为 `syncIntoCache(addr, list.messages)`;删除旧 `messages` StateFlow(或保留别名过渡,推荐直接删并同步改 MailboxInboxScreen 数据源字段)。`markRead(repo层)` 调用点删除——未读改由 `markMessageRead(key)`(点开详情时 `dao.markRead(key)`)与收件箱顶部「全部已读」按钮(可选,不做也行)驱动;`StoredMailbox.unread`/`lastSeenCount` 改由 DAO `unreadCountFlow` 回填到列表 UI(在 MailboxListScreen 收集 per-address unread 或简化为 inbox 空态判断——最小实现:列表卡片 unread 徽标改为 Room 查询,`MailboxListScreen` 内对每个地址 `dao.unreadCountFlow` 收集,收敛到 `Map<String,Int>` StateFlow 放 VM)。

批量 DAO 增加(加到 Task 2 的 DAO):

```kotlin
@Query("SELECT * FROM cached_messages WHERE messageKey IN (:keys)")
suspend fun byKeys(keys: Set<String>): List<CachedMessage>
```

VM 增加操作:

```kotlin
fun archiveMessage(key: String) = viewModelScope.launch { dao.setState(key, MessageState.ARCHIVED) }
fun unarchiveMessage(key: String) = viewModelScope.launch { dao.setState(key, MessageState.INBOX) }
fun deleteMessageLocal(key: String) = viewModelScope.launch { dao.deleteLocal(key) }
fun deleteMessageEverywhere(msg: CachedMessage) = viewModelScope.launch {
    val id = msg.messageKey.substringAfterLast(':') // 云端删需要数字 id
    try {
        api()?.deleteEmail(id)
        dao.deleteLocal(msg.messageKey)
        toast.value = "已删除(本地+云端)"
    } catch (e: Exception) {
        toast.value = "云端删除失败: ${e.message},邮件保留,可重试"
    }
}
fun markMessageRead(key: String) = viewModelScope.launch { dao.markRead(key) }
```

(api() 是同步取配置的私有函数,launch 里可直接调;id 提取用 `byKey` 查回行里的原始 id 更稳——CachedMessage 没有 id 列,messageKey 兜底格式是 `address:id`,substringAfterLast(':') 可靠;external_id 优先的 key 不是这个格式,此时云端删除要用 external_id 查 `GET /api/mailboxes/{addr}/messages` 对比——**简化**:deleteMessageEverywhere 先 `dao.byKey` 拿行,若 messageKey 含 `:` 且前缀=mailboxAddress 则拆 id,否则从 messages 接口按 external_id 匹配云端 id。测试见下。)

- [ ] **Step 3: 单测覆盖转换与删除 id 提取**

```kotlin
@Test
fun `删除云端id_兜底键拆出_地址前缀校验`() {
    assertEquals("9", MailboxLogic.cloudIdFor("a@x.com", "a@x.com:9"))
    assertEquals(null, MailboxLogic.cloudIdFor("a@x.com", "ext-9")) // external_id 键,无云端 id
}
```

实现(放 MailboxLogic):

```kotlin
/** 云端删除用的数字 id: 兜底键 "$address:$id" 才能拆出;external_id 键返回 null(需查接口匹配) */
fun cloudIdFor(mailboxAddress: String, messageKey: String): String? =
    messageKey.takeIf { it.startsWith("$mailboxAddress:") }?.substringAfter(':')
```

- [ ] **Step 4: 跑全套测试 + 编译**

Run: `/Users/lingion_k/mailgofer-android/gradlew -p /Users/lingion_k/mailgofer-android :app:testDebugUnitTest --console=plain`
Expected: PASS(改写后的 applyPollResult/markRead 旧测试:删掉或改为针对新状态的测试;`lastSeenCount` 字段保留但不再驱动未读)

- [ ] **Step 5: Commit**

```bash
git -C /Users/lingion_k/mailgofer-android add -A && git -C /Users/lingion_k/mailgofer-android commit -m "feat: 收件箱接入Room增量同步,未读改本地真值"
```

---

### Task 5: 收件箱 UI — 侧滑/长按/删除确认框

**Files:**
- Modify: `/Users/lingion_k/mailgofer-android/app/src/main/java/com/lingion/mailgofer/ui/MailboxInboxScreen.kt`
- Modify: `/Users/lingion_k/mailgofer-android/app/src/main/java/com/lingion/mailgofer/ui/AppViewModel.kt`(若 VM 少暴露)

**Interfaces:**
- Consumes: `vm.inboxMessages`(List<CachedMessage>)、`vm.archiveMessage/unarchiveMessage/deleteMessageLocal/deleteMessageEverywhere/markMessageRead`

- [ ] **Step 1: 每行包 SwipeToDismissBox(M3,Material3 1.3.0)**

```kotlin
// 右滑→归档 EndToStart=删除 左滑;confirm 值变更时执行动作,记得 else SnapBack
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipeableMessageRow(
    msg: CachedMessage,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { v ->
            when (v) {
                SwipeToDismissBoxValue.StartToEnd -> { onArchive(); true }
                SwipeToDismissBoxValue.EndToStart -> { onDelete(); true }
                else -> false
            }
        },
        positionalThreshold = { it * 0.45f },
    )
    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            val (icon, color, align) = when (dismissState.dismissDirection) {
                SwipeToDismissBoxValue.StartToEnd -> Triple(Icons.Default.Archive, MaterialTheme.colorScheme.primary, Alignment.CenterStart)
                else -> Triple(Icons.Default.Delete, MaterialTheme.colorScheme.error, Alignment.CenterEnd)
            }
            Box(Modifier.fillMaxSize().background(color).padding(horizontal = 20.dp), contentAlignment = align) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.onPrimary)
            }
        },
    ) {
        Box(
            Modifier
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
        ) { content() }
    }
}
```

(左滑删除触发 onDelete 只弹确认框——确认后才真删,天然防误删。)

- [ ] **Step 2: 长按菜单 + 删除确认框(二选)**

```kotlin
var pendingDelete by remember { mutableStateOf<CachedMessage?>(null) }
var menuFor by remember { mutableStateOf<CachedMessage?>(null) }
DropdownMenu(menuFor != null, { menuFor = null }) {
    DropdownMenuItem(text = { Text("归档") }, onClick = { menuFor?.let { vm.archiveMessage(it.messageKey) }; menuFor = null })
    DropdownMenuItem(text = { Text("删除") }, onClick = { pendingDelete = menuFor; menuFor = null })
}
AlertDialog(
    onDismissRequest = { pendingDelete = null },
    title = { Text("删除这封邮件?") },
    text = { Text(pendingDelete?.subject ?: "") },
    dismissButton = { TextButton({ pendingDelete = null }) { Text("取消") } },
    confirmButton = {},
)
// AlertDialog 的 confirmButton 槽放两个按钮(用 Row 包):
// TextButton({ vm.deleteMessageLocal(k); pendingDelete = null }) { Text("仅本地删") }
// TextButton({ vm.deleteMessageEverywhere(k); pendingDelete = null }) { Text("本地+云端都删") }
```

- [ ] **Step 3: 数据源切换与点开已读**

- 列表 `items(vm.inboxMessages, key = { it.messageKey })`;行内容沿用现有(OTP 徽标/时间/主题),字段从 CachedMessage 取
- 点开详情 `onOpenMessage(msg)` 前 `vm.markMessageRead(msg.messageKey)`
- 详情页导航参数从 `Message` 改为 `CachedMessage`(或 messageKey+来源查询),HtmlEmailView/OtpChip 输入字段对应适配(subject/content/htmlContent 直接读缓存行)

- [ ] **Step 4: 编译 + 全套测试**

Run: `/Users/lingion_k/mailgofer-android/gradlew -p /Users/lingion_k/mailgofer-android :app:testDebugUnitTest :app:assembleDebug --console=plain`
Expected: PASS + BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git -C /Users/lingion_k/mailgofer-android add -A && git -C /Users/lingion_k/mailgofer-android commit -m "feat: 收件箱侧滑归档/删除+长按菜单+删除二选确认框"
```

---

### Task 6: 归档页 + 列表徽标

**Files:**
- Create: `/Users/lingion_k/mailgofer-android/app/src/main/java/com/lingion/mailgofer/ui/ArchiveScreen.kt`
- Modify: `/Users/lingion_k/mailgofer-android/app/src/main/java/com/lingion/mailgofer/ui/AppNav.kt`(路由 `inbox/{address}/archive`,与 AppNav 实际文件名对齐——路由表在 MainActivity.kt 的 AppNav 内)
- Modify: `/Users/lingion_k/mailgofer-android/app/src/main/java/com/lingion/mailgofer/ui/MailboxListScreen.kt`(卡片徽标)
- Modify: `/Users/lingion_k/mailgofer-android/app/src/main/java/com/lingion/mailgofer/ui/AppViewModel.kt`(`archivedCounts: StateFlow<Map<String,Int>>`)

**Interfaces:**
- Consumes: `vm.archiveMessages`、`vm.unarchiveMessage/deleteMessageLocal/deleteMessageEverywhere`、DAO `archivedCountFlow`

- [ ] **Step 1: VM 归档计数**

```kotlin
val archivedCounts = MutableStateFlow<Map<String, Int>>(emptyMap())
// init 里: repo.mailboxes.collect → 对每个地址挂 archivedCountFlow 合并(用 combine 或逐个 launch 收集)
```

- [ ] **Step 2: ArchiveScreen(复用 Task 5 的 SwipeableMessageRow/确认框)**

- TopAppBar「abc@… 的归档」+ 返回;`items(vm.archiveMessages, key={it.messageKey})`
- 行操作: 点开可看(详情同收件箱)、取消归档(菜单/侧滑)、删除(二选确认框)
- 侧滑方向语义与收件箱互换: 左滑=取消归档(图标 Unarchive)、右滑=删除

- [ ] **Step 3: 列表卡片徽标**

MailboxCard 内(未读徽标旁或下行小字)加:

```kotlin
if (archived > 0) {
    Text("归档 $archived", style = MaterialTheme.typography.labelSmall,
         color = MaterialTheme.colorScheme.onSurfaceVariant)
}
```

卡片 onClick 保持进收件箱;「归档 N」文本本身可点(用 combinedClickable 或 Row 内 clickable 包住)进归档页。

- [ ] **Step 4: 路由接线**

AppNav 加 `composable("inbox/{address}/archive") { ArchiveScreen(...) }`;收件箱 TopAppBar actions 加「归档」入口图标(Archive icon)作为第二入口(徽标+图标双入口,点同一个 route)。

- [ ] **Step 5: 编译 + 全套测试 + 构建**

Run: `/Users/lingion_k/mailgofer-android/gradlew -p /Users/lingion_k/mailgofer-android :app:testDebugUnitTest :app:assembleDebug --console=plain`
Expected: PASS + BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git -C /Users/lingion_k/mailgofer-android add -A && git -C /Users/lingion_k/mailgofer-android commit -m "feat: 独立归档页+邮箱卡片归档徽标"
```

---

### Task 7: 收尾 — 旧逻辑清理与 README

**Files:**
- Modify: `/Users/lingion_k/mailgofer-android/app/src/main/java/com/lingion/mailgofer/data/MailboxLogic.kt`(applyPollResult/markRead 若已无调用方则删,测试同步删)
- Modify: `/Users/lingion_k/mailgofer-android/README.md:42` 附近(删除单封 UI 已接入;新增本地缓存/归档描述)

- [ ] **Step 1: 盘点死代码** `grep -rn 'applyPollResult\|markRead\|lastSeenCount' app/src/main/java` — 无调用方的函数与字段处理: applyPollResult/markRead 删;lastSeenCount 字段保留(StoredMailbox 兼容旧 DataStore JSON,标注 deprecated 注释)
- [ ] **Step 2: README 更新** — 功能列表补: 本地缓存历史(云端清空不丢)、侧滑归档/删除、删除二选(仅本地/本地+云端)、归档页
- [ ] **Step 3: 全套测试+构建绿**

Run: `/Users/lingion_k/mailgofer-android/gradlew -p /Users/lingion_k/mailgofer-android :app:testDebugUnitTest :app:assembleDebug --console=plain`

- [ ] **Step 4: Commit**

```bash
git -C /Users/lingion_k/mailgofer-android add -A && git -C /Users/lingion_k/mailgofer-android commit -m "chore: 清理count差值未读旧逻辑,README对齐"
```

---

## Self-Review 记录

- Spec 覆盖: Room 表(T2)/增量同步(T3,T4)/未读真值(T4)/侧滑长按(T5)/删除二选(T5)/独立归档页+徽标(T6)/本地详情离线可看(T5 Step3)/旧逻辑清理(T7) ✓
- 类型一致性: SyncPlan/messageKeyFor/cloudIdFor/DAO 签名在 T2/T3/T4 间一致;SwipeableMessageRow 在 T5/T6 复用 ✓
- 风险标注: Message.id 是 JsonPrimitive(int/string 兼容),toCached 用 `?.content` 取字符串 ✓;删除云端 id 从兜底键拆,external_id 键需接口匹配(T4 已写方案) ✓
