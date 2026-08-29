# 邮箱约束二选一 + 过期状态同步 + 刷新邮箱

## 需求(用户原话转译)
1. 邮箱两条约束: 有效期(ttl) / 最大邮件数(max_messages)。允许"永不过期"或"无限收信",但**至少填一条**,两条全空 = 拒绝。
2. App 随时获取后端过期状态(轮询同步),过期 → 列表卡红色警告。
3. 过期/任意时刻允许"刷新邮箱": 同地址清空旧邮件 + 重置约束重新激活。

## 语义定义
- ttl_hours/ttl_minutes 缺省或 <=0 → expires_at = null(永不过期)
- max_messages 缺省或 <=0 → 存 0(无限收信,不再自动清空)
- 两者全空 → 400 constraint_required
- 刷新 POST /api/mailboxes/{id|addr}/refresh: body 可带 ttl_hours/ttl_minutes/max_messages;缺省沿用旧值(旧值全空不可能,创建时已保证);删全部邮件 + active=1 + 新 expires_at

## Task 列表
- [ ] T1 worker: createMailbox 约束语义+校验 / refresh 端点 / inbound max=0 不清空 / messages 响应 brief 带 expires_at+active+max_messages
- [ ] T2 worker: wrangler dev --local + curl 验证(全空400/永不过期/无限收信/刷新/inbound)
- [ ] T3 android: Models(MailboxBrief 扩展, MailboxList) + Api(listMailboxes, refreshMailbox)
- [ ] T4 android: MailboxLogic.isExpired + validateConstraints(TDD 先测)
- [ ] T5 android: ViewModel(轮询先拉 /api/mailboxes 同步状态; 只对 active 拉信; refreshMailbox; 410 处理; 创建校验)
- [ ] T6 android: CreateMailboxSheet(0=不限 支持文案 + 至少一条校验)
- [ ] T7 android: MailboxListScreen(过期红星⚠ / 永不过期·无限收信文案 / 刷新按钮+确认对话框"旧邮件会清空") + Inbox 到期文案同步 + 410 标记
- [ ] T8 android: test + assembleDebug + 装真机冒烟
- [ ] T9 commit(android 渲染工作单独一笔 + 本功能一笔; worker 一笔) · deploy/push 前单独确认

## 风险
- 改了"缺省 ttl=5min"语义: 老脚本不带参数创建会 400 → 用户自有服务,可接受,release note 里写明
- 轮询多一个 GET /api/mailboxes(10s 一轮 1 次) → 量级无所谓
