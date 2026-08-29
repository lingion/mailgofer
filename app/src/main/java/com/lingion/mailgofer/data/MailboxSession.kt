package com.lingion.mailgofer.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.lingion.mailgofer.model.Mailbox
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** 当前活跃邮箱会话(持久化,杀 App 不丢) */
data class MailboxSession(
    val email: String = "",
    val token: String = "",
    val expiresAt: String = "",
    val maxMessages: Int = 0,
) {
    fun isValid(): Boolean = email.isNotBlank()
}

private val Context.sessionStore by preferencesDataStore(name = "session")

class SessionStore(private val context: Context) {

    private val emailKey = stringPreferencesKey("email")
    private val tokenKey = stringPreferencesKey("mailbox_token")
    private val expiresKey = stringPreferencesKey("expires_at")
    private val maxMsgKey = androidx.datastore.preferences.core.intPreferencesKey("max_messages")

    val session: Flow<MailboxSession> = context.sessionStore.data.map { prefs ->
        MailboxSession(
            email = prefs[emailKey] ?: "",
            token = prefs[tokenKey] ?: "",
            expiresAt = prefs[expiresKey] ?: "",
            maxMessages = prefs[maxMsgKey] ?: 0,
        )
    }

    suspend fun save(mailbox: Mailbox) {
        context.sessionStore.edit { prefs ->
            prefs[emailKey] = mailbox.email ?: mailbox.address ?: ""
            prefs[tokenKey] = mailbox.token ?: ""
            prefs[expiresKey] = mailbox.expiresAt ?: ""
            prefs[maxMsgKey] = mailbox.maxMessages ?: 0
        }
    }

    suspend fun clear() {
        context.sessionStore.edit { it.clear() }
    }
}
