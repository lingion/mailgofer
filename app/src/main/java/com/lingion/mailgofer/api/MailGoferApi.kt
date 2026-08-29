package com.lingion.mailgofer.api

import com.lingion.mailgofer.model.ApiEnvelope
import com.lingion.mailgofer.model.CreateMailboxRequest
import com.lingion.mailgofer.model.EmailList
import com.lingion.mailgofer.model.HealthCheck
import com.lingion.mailgofer.model.Mailbox
import com.lingion.mailgofer.model.MailboxMessages
import com.lingion.mailgofer.model.Message
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * MailGofer HTTP API 客户端。
 *
 * 鉴权(源码 index.js auth()): 全局 API_TOKEN,三种载体任选其一:
 *   Authorization: Bearer <token> / x-api-key: <token> / ?api_key=<token>
 * 这里统一用 x-api-key(POST body 无需关心 query 转义,Bearer 与第三方脚本习惯一致)。
 *
 * 说明: 创建邮箱响应里的 mailbox token 只入库展示,不参与读信鉴权 ——
 * 读信仍需全局 API_TOKEN(与官方 cloudflare_mail_client.py 行为一致)。
 */
class MailGoferApi(
    private val baseUrl: String,
    private val apiToken: String,
) {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    /** 统一错误:非 2xx 或 success=false 都抛 */
    class ApiException(val code: Int, val serverError: String?) :
        Exception("HTTP $code${serverError?.let { ": $it" } ?: ""}")

    private fun request(
        method: String,
        path: String,
        body: String? = null,
        query: Map<String, String> = emptyMap(),
    ): Pair<Int, String> {
        val qs = query.entries.joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
        }
        val url = URL("$baseUrl$path${if (qs.isEmpty()) "" else "?$qs"}")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("x-api-key", apiToken)
            if (body != null) {
                setRequestProperty("Content-Type", "application/json")
                doOutput = true
            }
        }
        try {
            body?.let { payload ->
                conn.outputStream.use { out -> out.write(payload.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use(BufferedReader::readText) ?: ""
            return code to text
        } finally {
            conn.disconnect()
        }
    }

    private inline fun <reified T> call(method: String, path: String, body: String? = null, query: Map<String, String> = emptyMap()): T {
        val (code, text) = request(method, path, body, query)
        val envelope = try {
            json.decodeFromString<ApiEnvelope<T>>(text)
        } catch (e: Exception) {
            throw ApiException(code, if (text.isNotBlank()) text.take(200) else "invalid response")
        }
        if (code !in 200..299 || !envelope.success) {
            throw ApiException(code, envelope.error ?: text.take(200))
        }
        @Suppress("UNCHECKED_CAST")
        return envelope.data as T
    }

    /** GET /health — 唯一免鉴权端点,用于连接测试 */
    suspend fun health(): HealthCheck {
        return try {
            val (code, _) = request("GET", "/health")
            HealthCheck(ok = code == 200, httpCode = code)
        } catch (e: Exception) {
            HealthCheck(ok = false, httpCode = -1)
        }
    }

    /** POST /api/mailboxes — 创建(或复用同名活跃)邮箱;返回含 token/expires_at */
    suspend fun createMailbox(req: CreateMailboxRequest): Mailbox {
        val payload = json.encodeToString(CreateMailboxRequest.serializer(), req)
        return call<Mailbox>("POST", "/api/mailboxes", body = payload)
    }

    /** GET /api/emails?email= — 按完整地址拉邮件列表(最新100封) */
    suspend fun listEmails(email: String): EmailList =
        call<EmailList>("GET", "/api/emails", query = mapOf("email" to email))

    /** GET /api/mailboxes/{id}/messages — 按 mailbox 拉邮件列表 */
    suspend fun mailboxMessages(mailboxIdOrAddress: String): MailboxMessages =
        call<MailboxMessages>("GET", "/api/mailboxes/$mailboxIdOrAddress/messages")

    /** GET /api/email/{id} — 单封详情 */
    suspend fun getEmail(messageId: String): Message =
        call<Message>("GET", "/api/email/$messageId")

    /** DELETE /api/email/{id} — 删单封 */
    suspend fun deleteEmail(messageId: String) {
        call<Unit>("DELETE", "/api/email/$messageId")
    }

    /** DELETE /api/emails/clear?email= — 清空某地址全部邮件 */
    suspend fun clearEmails(email: String) {
        call<Unit>("DELETE", "/api/emails/clear", query = mapOf("email" to email))
    }
}
