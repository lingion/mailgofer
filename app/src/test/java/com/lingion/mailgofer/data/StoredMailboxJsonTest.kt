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

    @Test
    fun `默认字段_缺省可解码`() {
        val round = json.decodeFromString<StoredMailbox>("""{"address":"a@x.com"}""")
        assertEquals(StoredMailbox("a@x.com"), round)
    }
}
