package com.lingion.mailgofer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MailboxLogicTest {

    private fun mb(addr: String, last: Int, unread: Int) =
        StoredMailbox(address = addr, lastSeenCount = last, unread = unread)

    // ── batchNames ──
    @Test
    fun `批量命名_正常序列`() {
        assertEquals(listOf("shop-1", "shop-2", "shop-3"), MailboxLogic.batchNames("Shop", 3))
    }

    @Test
    fun `批量命名_太短抛异常`() {
        // "ab-1" 只有 4 位,不满足服务端 6~40 位
        assertThrows(IllegalArgumentException::class.java) { MailboxLogic.batchNames("AB", 1) }
    }

    @Test
    fun `批量命名_非法字符抛异常`() {
        assertThrows(IllegalArgumentException::class.java) { MailboxLogic.batchNames("ac@count", 2) }
    }

    @Test
    fun `批量命名_空前缀抛异常`() {
        assertThrows(IllegalArgumentException::class.java) { MailboxLogic.batchNames("", 2) }
    }

    @Test
    fun `批量命名_数量越界抛异常`() {
        assertThrows(IllegalArgumentException::class.java) { MailboxLogic.batchNames("shopxx", 0) }
        assertThrows(IllegalArgumentException::class.java) { MailboxLogic.batchNames("shopxx", 31) }
    }

    @Test
    fun `批量命名_大写前缀归一化`() {
        assertEquals(listOf("lingtest-1", "lingtest-2"), MailboxLogic.batchNames("LingTest", 2))
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
    fun `旧会话迁移_追加为列表项`() {
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

    @Test
    fun `replaceOrAppend_新地址追加`() {
        val out = MailboxLogic.replaceOrAppend(listOf(StoredMailbox("a@x.com")), StoredMailbox("b@x.com"))
        assertEquals(2, out.size)
    }

    // ── isExpired ──
    @Test
    fun `永不过期_null的expiresAt_不算过期`() {
        assertEquals(false, MailboxLogic.isExpired(StoredMailbox(address = "a@x.com", expiresAt = null)))
    }

    @Test
    fun `过期时间在未来_不算过期`() {
        assertEquals(false, MailboxLogic.isExpired(StoredMailbox(address = "a@x.com", expiresAt = "2099-01-01T00:00:00Z")))
    }

    @Test
    fun `过期时间在过去_算过期`() {
        assertEquals(true, MailboxLogic.isExpired(StoredMailbox(address = "a@x.com", expiresAt = "2020-01-01T00:00:00Z")))
    }

    @Test
    fun `过期时间解析失败_不算过期_不误报`() {
        assertEquals(false, MailboxLogic.isExpired(StoredMailbox(address = "a@x.com", expiresAt = "垃圾数据")))
    }

    // ── syncFromServer ──
    @Test
    fun `同步服务端状态_更新约束与active_保留本地未读`() {
        val list = listOf(StoredMailbox(address = "a@x.com", unread = 3, lastSeenCount = 5, active = true))
        val out = MailboxLogic.syncFromServer(
            list, "a@x.com",
            expiresAt = "2099-01-01T00:00:00Z", active = false, maxMessages = 0
        )
        assertEquals(false, out[0].active)
        assertEquals(0, out[0].maxMessages)
        assertEquals("2099-01-01T00:00:00Z", out[0].expiresAt)
        assertEquals(3, out[0].unread) // 未读是本地维度,同步不覆盖
    }

    @Test
    fun `同步服务端状态_列表里没有的地址_原样返回`() {
        val list = listOf(StoredMailbox(address = "a@x.com"))
        val out = MailboxLogic.syncFromServer(list, "other@x.com", expiresAt = null, active = true, maxMessages = 5)
        assertEquals(list, out)
    }

    // ── validateConstraints ──
    @Test
    fun `约束校验_ttl和max都空_报错`() {
        assertEquals(false, MailboxLogic.validateConstraints(ttlText = "", maxText = ""))
        assertEquals(false, MailboxLogic.validateConstraints(ttlText = "0", maxText = "0"))
    }

    @Test
    fun `约束校验_只填ttl_通过`() {
        assertEquals(true, MailboxLogic.validateConstraints(ttlText = "24", maxText = ""))
    }

    @Test
    fun `约束校验_只填max_通过`() {
        assertEquals(true, MailboxLogic.validateConstraints(ttlText = "", maxText = "5"))
    }

    @Test
    fun `约束校验_两个都填_通过`() {
        assertEquals(true, MailboxLogic.validateConstraints(ttlText = "1", maxText = "10"))
    }

    @Test
    fun `约束解析_ttl空max空_返回null与0`() {
        val (ttlH, maxM) = MailboxLogic.parseConstraints(ttlText = "", maxText = "")
        assertEquals(0, ttlH)
        assertEquals(0, maxM)
    }

    @Test
    fun `约束解析_正常数字`() {
        val (ttlH, maxM) = MailboxLogic.parseConstraints(ttlText = "48", maxText = "20")
        assertEquals(48, ttlH)
        assertEquals(20, maxM)
    }

    // ── formatExpiry ──
    @Test
    fun `到期显示_null永不过期`() {
        assertEquals("永不过期", MailboxLogic.formatExpiry(null, 1_700_000_000_000L))
    }

    @Test
    fun `到期显示_不足1小时_分钟`() {
        // now = 2023-11-14T22:13:20Z, expiry 在 42 分钟后
        assertEquals("42分钟后过期", MailboxLogic.formatExpiry("2023-11-14T22:55:20Z", 1_700_000_000_000L))
    }

    @Test
    fun `到期显示_不足1小时_不足1分钟显示刚刚级`() {
        assertEquals("1分钟后过期", MailboxLogic.formatExpiry("2023-11-14T22:14:19Z", 1_700_000_000_000L))
    }

    @Test
    fun `到期显示_1到24小时_小时加分钟`() {
        // 5h59m → 5小时59分钟后过期
        assertEquals("5小时59分钟后过期", MailboxLogic.formatExpiry("2023-11-15T04:12:20Z", 1_700_000_000_000L))
        // 2h18m → 2小时18分钟后过期
        assertEquals("2小时18分钟后过期", MailboxLogic.formatExpiry("2023-11-15T00:31:20Z", 1_700_000_000_000L))
    }

    @Test
    fun `到期显示_超过24小时_天加日期`() {
        // 2023-11-17T22:13:20Z = 3天后, 日期 11月18日
        assertEquals("3天后过期 · 11月18日", MailboxLogic.formatExpiry("2023-11-17T22:13:20Z", 1_700_000_000_000L))
    }

    @Test
    fun `到期显示_已过期`() {
        assertEquals("已过期", MailboxLogic.formatExpiry("2020-01-01T00:00:00Z", 1_700_000_000_000L))
    }

    @Test
    fun `到期显示_解析失败`() {
        assertEquals("过期时间未知", MailboxLogic.formatExpiry("bad", 1_700_000_000_000L))
    }

    // ── originalTtlMinutes(按原标准刷新)──
    @Test
    fun `原ttl反推_正常1小时邮箱`() {
        // created 22:00, expires 23:00 → 60 分钟
        val mins = MailboxLogic.originalTtlMinutes("2023-11-14T22:00:00Z", "2023-11-14T23:00:00Z")
        assertEquals(60L, mins)
    }

    @Test
    fun `原ttl反推_永不过期返回null`() {
        assertEquals(null, MailboxLogic.originalTtlMinutes("2023-11-14T22:00:00Z", null))
    }

    @Test
    fun `原ttl反推_缺created返回null`() {
        assertEquals(null, MailboxLogic.originalTtlMinutes(null, "2023-11-14T23:00:00Z"))
    }

    @Test
    fun `原ttl反推_非法时间返回null`() {
        assertEquals(null, MailboxLogic.originalTtlMinutes("垃圾", "2023-11-14T23:00:00Z"))
        assertEquals(null, MailboxLogic.originalTtlMinutes("2023-11-14T22:00:00Z", "垃圾"))
    }

    @Test
    fun `原ttl反推_expires早于created返回null`() {
        assertEquals(null, MailboxLogic.originalTtlMinutes("2023-11-14T23:00:00Z", "2023-11-14T22:00:00Z"))
    }

    @Test
    fun `原ttl反推_不足1分钟返回null`() {
        // 差 30 秒,取整为 0 分钟 → null(不传,沿用服务端)
        assertEquals(null, MailboxLogic.originalTtlMinutes("2023-11-14T22:00:00Z", "2023-11-14T22:00:30Z"))
    }
}
