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
}
