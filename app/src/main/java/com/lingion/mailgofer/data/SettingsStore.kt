package com.lingion.mailgofer.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

data class ServerConfig(
    val host: String = "",
    val port: String = "",
    val apiToken: String = "",
    val domain: String = "",
) {
    /** 拼出 base URL;host 不含 scheme 时默认 https */
    fun baseUrl(): String {
        val h = host.trim().trimEnd('/')
        if (h.isEmpty()) return ""
        val withScheme = if (h.startsWith("http://") || h.startsWith("https://")) h else "https://$h"
        val p = port.trim()
        return if (p.isNotEmpty()) "$withScheme:$p" else withScheme
    }

    fun isComplete(): Boolean = host.isNotBlank() && apiToken.isNotBlank()
}

/** 服务端配置持久化 — DataStore */
class SettingsStore(private val context: Context) {

    private val hostKey = stringPreferencesKey("host")
    private val portKey = stringPreferencesKey("port")
    private val tokenKey = stringPreferencesKey("api_token")
    private val domainKey = stringPreferencesKey("domain")

    val config: Flow<ServerConfig> = context.dataStore.data.map { prefs ->
        ServerConfig(
            host = prefs[hostKey] ?: "",
            port = prefs[portKey] ?: "",
            apiToken = prefs[tokenKey] ?: "",
            domain = prefs[domainKey] ?: "",
        )
    }

    suspend fun save(config: ServerConfig) {
        context.dataStore.edit { prefs ->
            prefs[hostKey] = config.host.trim()
            prefs[portKey] = config.port.trim()
            prefs[tokenKey] = config.apiToken.trim()
            prefs[domainKey] = config.domain.trim()
        }
    }
}
