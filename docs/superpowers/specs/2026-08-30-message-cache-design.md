# MailGofer 本地邮件缓存 + 增量同步 + 删除/归档 — 设计文档

日期: 2026-08-30 · 状态: 已批准(用户确认)

## 目标

像正常邮件管理系统:邮件缓存在手机本地(空间无限),可删除/归档单封;增量爬云端新内容;刷新邮箱/云端清空后,本地历史记录保留,云端腾出空间。

## 用户决策(AskUserQuestion 确认)

1. **删除**:每次删除都弹确认框,框内二选——「仅本地删」(云端保留) / 「本地+云端都删」(腾 D1 空间)
2. **归档查看**:独立归档页;邮箱列表卡片加「归档 N」徽标,点进该邮箱的归档页
3. **操作手势**:M3 SwipeToDismissBox 侧滑(右滑=归档,左滑=删除)+ 长按菜单(归档/删除)

## 架构

### 1. 本地库 — Room

新增依赖: `androidx.room:room-runtime:2.6.1` + `room-ktx:2.6.1` + `ksp` + `room-compiler:2.6.1`

新表 `cached_messages`:

| 列 | 类型 | 说明 |
|---|---|---|
| messageKey (PK) | String | external_id 优先,缺省 `"$mailboxAddress:$id"`;增量去重锚 |
| mailboxAddress | String | 归属邮箱,索引 |
| fromAddress | String? | 发件人 |
| subject | String? | 主题 |
| content | String? | 纯文本正文 |
| htmlContent | String? | HTML 正文 |
| createdAt | String? | 云端时间戳(排序用) |
| timestamp | Long? | 毫秒时间戳(排序兜底) |
| state | String | INBOX / ARCHIVED / DELETED_LOCAL |
| unread | Boolean | **未读改为本地真值**(按 messageKey 判重) |
| cachedAt | Long | 缓存时间 |

`StoredMailbox` 加 `archivedCount: Int = 0`(邮箱卡片徽标数)。

### 2. 增量同步

- `fetchActive`/轮询拉到云端 list 后,按 messageKey **upsert**:
  - 新 key → 插入 `INBOX` 态,`unread=true`
  - 已有 key → 刷新正文等字段,**state 与 unread 不动**
- 云端被 refresh/收满清空 → 本地行原样保留(历史不丢)
- 云端列表里消失的本地 INBOX 行**不动**(云端 LIMIT 100 截断会误判,不标已删)
- 未读计数 = Room 查询 `WHERE unread=1 AND state='INBOX' AND mailboxAddress=?`,替代现有 `lastSeenCount` 差值推算
- `MailboxLogic.applyPollResult/markRead` 的 count 差值逻辑废弃,unread 维护移入 Room 层

### 3. UI 流

- **收件箱**:读 Room `state INBOX` Flow(替代内存 StateFlow 全量替换);右滑=归档、左滑=删除、长按=菜单
- **删除确认框**:每次必弹,二选按钮「仅本地删」(state→DELETED_LOCAL) / 「本地+云端都删」(本地删+调 `DELETE /api/email/{id}`;网络失败→toast 报错,本地保留,可重试) + 取消
- **归档页**:独立 Screen(路由 `inbox/{address}/archive`);Room `state ARCHIVED` Flow;支持取消归档(state→INBOX)/删除(同二选确认框)
- **邮箱列表卡片**:加「归档 N」徽标(N=archivedCount),点进归档页

### 4. 消息详情

点开缓存邮件直接读 Room(离线可看);OTP 提取/渲染逻辑不变。

## 兼容与迁移

- 现有 `messages: MutableStateFlow<List<Message>>` 收件箱数据源改为 Room Flow 收集;AppViewModel 层做映射
- 现有 6 条 applyPollResult/markRead 相关测试改写为 Room 状态机测试(upsert 不覆盖 state/unread、删除二态、归档切换)
- DataStore 的 StoredMailbox 列表继续负责邮箱级信息(地址/过期/active);`archivedCount` 由 Room 查询结果回填

## 验证

- Room/状态机逻辑:JVM 单测(纯 Kotlin 状态机函数,不引 instrumented)
- UI/手势:构建通过后推真机手工验收(用户自己验,禁 Claude 真机操作/截图)

## 不做(YAGNI)

- 不做消息全文搜索
- 不做批量选择操作
- 不做本地删除与云端状态双向同步(云端没有归档概念,单机语义)
- 不做附件下载
