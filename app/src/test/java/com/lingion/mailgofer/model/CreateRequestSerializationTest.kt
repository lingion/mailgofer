package com.lingion.mailgofer.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class CreateRequestSerializationTest {

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    @Test
    fun `只填max不填ttl_序列化不含ttl字段`() {
        val req = CreateMailboxRequest(name = "abc-1", maxMessages = 100)
        val payload = json.encodeToString(CreateMailboxRequest.serializer(), req)
        println("PAYLOAD=$payload")
        assertEquals(false, payload.contains("ttl"))
        assertEquals(true, payload.contains("\"max_messages\":100"))
    }

    @Test
    fun `只填ttl不填max_序列化不含max字段`() {
        val req = CreateMailboxRequest(ttlHours = 2)
        val payload = json.encodeToString(CreateMailboxRequest.serializer(), req)
        println("PAYLOAD=$payload")
        assertEquals(false, payload.contains("max_messages"))
        assertEquals(true, payload.contains("\"ttl_hours\":2"))
    }
}
