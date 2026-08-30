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
