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
    val lastSeenCount: Int = 0,     // 已废弃:未读改为 Room 本地真值(cached_messages.unread),保留仅兼容旧 DataStore JSON 反序列化
    val unread: Int = 0,            // 已废弃:同上,真值在 Room,保留仅兼容旧 JSON
)
