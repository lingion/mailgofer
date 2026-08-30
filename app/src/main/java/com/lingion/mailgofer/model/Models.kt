package com.lingion.mailgofer.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * API 契约来源: lingion/mailgofer src/index.js (2026-08-29 本地源码核实)
 * 统一响应信封: apiResponse() → { success, data, usage, error? }
 */

@Serializable
data class ApiEnvelope<T>(
    val success: Boolean = false,
    val data: T? = null,
    val error: String? = null,
    // usage 字段存在但客户端不用,忽略未知字段
)

@Serializable
data class Mailbox(
    // 服务端 D1 自增主键,返回的是数字(如 7349);声明为 Any 等价物会破坏序列化,
    // 用 kotlinx 的 JsonPrimitive 兼容 int/string 两种形态
    val id: kotlinx.serialization.json.JsonPrimitive? = null,
    @SerialName("mailbox_id") val mailboxId: String? = null,
    val email: String? = null,
    val address: String? = null,
    val domain: String? = null,
    val subdomain: String? = null,
    val token: String? = null,
    val label: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("expires_at") val expiresAt: String? = null,
    val active: Int? = null,
    @SerialName("max_messages") val maxMessages: Int? = null,
)

@Serializable
data class Message(
    // 线上 D1 实测返回 int(schema 声明 TEXT 但存量数据是自增 int),JsonPrimitive 兼容两种
    val id: kotlinx.serialization.json.JsonPrimitive? = null,
    @SerialName("external_id") val externalId: String? = null,
    @SerialName("email_address") val emailAddress: String? = null,
    @SerialName("from_address") val fromAddress: String? = null,
    val subject: String? = null,
    val content: String? = null,
    @SerialName("html_content") val htmlContent: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    val timestamp: Long? = null,
    @SerialName("has_html") val hasHtml: Boolean? = null,
)

@Serializable
data class MailboxMessages(
    val mailbox: MailboxBrief? = null,
    val messages: List<Message> = emptyList(),
    val count: Int = 0,
)

/** GET /api/mailboxes 的 data */
@Serializable
data class MailboxList(
    val mailboxes: List<Mailbox> = emptyList(),
)

@Serializable
data class MailboxBrief(
    // 服务端返回 int(同 Mailbox.id),JsonPrimitive 兼容
    val id: kotlinx.serialization.json.JsonPrimitive? = null,
    @SerialName("mailbox_id") val mailboxId: String? = null,
    val address: String? = null,
    // GET /api/mailboxes/{id}/messages 的 brief 自 2026-08-29 起带上过期状态(App 同步红星依据)
    @SerialName("expires_at") val expiresAt: String? = null,
    val active: Int? = null,
    @SerialName("max_messages") val maxMessages: Int? = null,
)

@Serializable
data class EmailList(
    val emails: List<Message> = emptyList(),
    val count: Int = 0,
)

/** 创建邮箱请求体 — buildMailboxAddress() + createMailbox() 的字段 */
@Serializable
data class CreateMailboxRequest(
    val name: String? = null,       // ^[a-z0-9_-]{6,40}$ 或留空自动生成 mbx_xxx
    val domain: String? = null,     // 必须是根域或其子域
    val label: String? = null,
    @SerialName("ttl_minutes") val ttlMinutes: Int? = null,
    @SerialName("ttl_hours") val ttlHours: Int? = null,
    @SerialName("max_messages") val maxMessages: Int? = null,
)

@Serializable
data class HealthCheck(
    val ok: Boolean,
    val httpCode: Int,
    val detail: String? = null,
)
