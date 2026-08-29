package com.lingion.mailgofer.data

/** 多邮箱纯逻辑:批量命名 / 未读增量 / 旧会话迁移。全部无 Android 依赖,单测直打。 */
object MailboxLogic {

    /** 覆盖同地址项,或追加新项;保持原顺序 */
    fun replaceOrAppend(list: List<StoredMailbox>, item: StoredMailbox): List<StoredMailbox> =
        if (list.any { it.address == item.address })
            list.map { if (it.address == item.address) item else it }
        else list + item

    /**
     * 批量命名:prefix-1 … prefix-count。
     * 服务端规则 ^[a-z0-9_-]{6,40}$ → 生成的完整名必须 6~40 位。
     * @throws IllegalArgumentException 前缀含非法字符 / 数量越界 / 名字长度不达标(中文消息)
     */
    fun batchNames(prefixRaw: String, count: Int): List<String> {
        val prefix = prefixRaw.trim().lowercase()
        require(prefix.isNotEmpty() && prefix.matches(Regex("[a-z0-9_-]+"))) {
            "前缀只允许 a-z、0-9、_、-"
        }
        require(count in 1..30) { "数量需在 1~30" }
        return (1..count).map { i ->
            val name = "$prefix-$i"
            require(name.length in 6..40) { "生成的名字「$name」不满足 6~40 位,请调整前缀长度" }
            name
        }
    }

    /**
     * 一轮轮询结果落到列表上。
     * newCount < lastSeen → 服务端收满清空过:基准重置、未读清零;
     * 否则 delta 计入未读;正在查看的邮箱始终 unread=0。
     */
    fun applyPollResult(
        list: List<StoredMailbox>,
        address: String,
        newCount: Int,
        openAddress: String?,
    ): List<StoredMailbox> = list.map { mb ->
        when {
            mb.address != address -> mb
            newCount < mb.lastSeenCount -> mb.copy(lastSeenCount = newCount, unread = 0)
            else -> {
                val unread =
                    if (address == openAddress) 0
                    else mb.unread + (newCount - mb.lastSeenCount)
                mb.copy(lastSeenCount = newCount, unread = unread)
            }
        }
    }

    /** 打开收件箱后标记已读,并把基准校准到刚拉到的数量 */
    fun markRead(list: List<StoredMailbox>, address: String, fetchedCount: Int): List<StoredMailbox> =
        list.map {
            if (it.address == address) it.copy(unread = 0, lastSeenCount = fetchedCount) else it
        }

    /** 旧单会话 → 列表首项;地址空或已存在则原样返回 */
    fun migrateLegacy(existing: List<StoredMailbox>, legacy: MailboxSession): List<StoredMailbox> =
        if (legacy.email.isBlank() || existing.any { it.address == legacy.email }) existing
        else existing + StoredMailbox(
            address = legacy.email,
            token = legacy.token,
            expiresAt = legacy.expiresAt,
            maxMessages = legacy.maxMessages,
        )

    /**
     * 邮箱是否已过期:expires_at=null 表示永不过期,永不判过期;
     * 解析失败也判未过期(宁可不标,不误报)。
     */
    fun isExpired(mailbox: StoredMailbox, nowMs: Long = System.currentTimeMillis()): Boolean {
        val raw = mailbox.expiresAt ?: return false
        return try {
            java.time.Instant.parse(raw).toEpochMilli() < nowMs
        } catch (_: Exception) {
            false
        }
    }

    /** 把服务端 /api/mailboxes 的最新约束/状态合并到本地条目(未读是本地维度,不覆盖) */
    fun syncFromServer(
        list: List<StoredMailbox>,
        address: String,
        expiresAt: String?,
        active: Boolean,
        maxMessages: Int,
    ): List<StoredMailbox> = list.map {
        if (it.address == address) it.copy(expiresAt = expiresAt, active = active, maxMessages = maxMessages) else it
    }

    /**
     * 从 created_at/expires_at 反推原 ttl 分钟数,供"按原标准刷新"续期。
     * 任一字段缺失/非法/差值<=0 → null(调用人改传 null = 沿用服务端旧值)。
     */
    fun originalTtlMinutes(createdAt: String?, expiresAt: String?): Long? {
        if (createdAt.isNullOrBlank() || expiresAt.isNullOrBlank()) return null
        return try {
            val created = java.time.Instant.parse(createdAt).toEpochMilli()
            val expires = java.time.Instant.parse(expiresAt).toEpochMilli()
            val minutes = (expires - created) / 60_000
            minutes.takeIf { it > 0 }
        } catch (_: Exception) {
            null
        }
    }

    /** 创建约束二选一:ttl_hours 与 max_messages 至少一项 > 0;留空/0 = 不限 */
    fun validateConstraints(ttlText: String, maxText: String): Boolean =
        (ttlText.trim().toLongOrNull() ?: 0L) > 0 || (maxText.trim().toLongOrNull() ?: 0L) > 0

    /** 输入框文本 → 请求参数:非法/空输入归 0(不限) */
    fun parseConstraints(ttlText: String, maxText: String): Pair<Int, Int> =
        (ttlText.trim().toIntOrNull() ?: 0) to (maxText.trim().toIntOrNull() ?: 0)

    /**
     * 到期时间 → 用户可读文案。剩余时间优先,超过一天才带日期:
     * null → 永不过期 · <1h → 42分钟后过期 · <24h → 2小时18分钟后过期
     * ≥24h → 3天后过期 · 11月18日 · 已过期 → 已过期 · 解析失败 → 过期时间未知
     */
    fun formatExpiry(expiresAt: String?, nowMs: Long = System.currentTimeMillis()): String {
        if (expiresAt.isNullOrBlank()) return "永不过期"
        val expiryMs = try {
            java.time.Instant.parse(expiresAt).toEpochMilli()
        } catch (_: Exception) {
            return "过期时间未知"
        }
        val remaining = expiryMs - nowMs
        if (remaining <= 0) return "已过期"

        val minutes = remaining / 60_000
        if (minutes < 1) return "1分钟后过期"
        if (minutes < 60) return "${minutes}分钟后过期"

        val hours = remaining / 3_600_000
        val remMinutes = (remaining % 3_600_000) / 60_000
        if (hours < 24) {
            return if (remMinutes > 0) "${hours}小时${remMinutes}分钟后过期" else "${hours}小时后过期"
        }

        val days = remaining / 86_400_000
        val date = java.time.Instant.ofEpochMilli(expiryMs)
            .atZone(java.time.ZoneId.systemDefault())
            .let { "${it.monthValue}月${it.dayOfMonth}日" }
        return "${days}天后过期 · $date"
    }
}
