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
}
