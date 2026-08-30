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
