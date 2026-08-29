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
