# Cloudflare Email Routing / 转发说明

> 这是一份历史性部署笔记，描述本仓库默认形态下的收件链路。
> README 里的 `<你的域名>` / `<your-domain>` 在本文件中统一用 `<your-domain>` 表示。

## 收件链路

- 外部邮件发到：`xxx@mail.<your-domain>`
- Cloudflare Email Routing catch-all → `mailgofer` Worker
- Worker 收到后：
  1. 写入 D1 数据库 (`mail_api`) 留痕
  2. 自动转发到 `FORWARD_TO_EMAIL`（可填 QQ 邮箱）

## 启用步骤

1. Cloudflare Dashboard → 你的域名 → **Email → Email Routing → Enable**
2. 添加并验证 destination address（你的真实邮箱，例如 QQ）
3. 添加 catch-all route：
   - Pattern: `*@mail.<your-domain>`
   - Action: **Send to Worker** → `mailgofer`
4. 确认 `wrangler.toml` 中：
   - `MAIL_DOMAIN = "mail.<your-domain>"`
   - `FORWARD_TO_EMAIL = "<your-real-inbox@example.com>"`

## Resend（可选发件）

- 仅当启用 `send.<your-domain>` 路由时需要
- 注册 Resend → 获取 API key → 填到 `wrangler.toml` 的 `RESEND_API_KEY`
- 注意：`from` 必须已在 D1 mailbox 表中存在
- 域名需有 SPF/DKIM 记录对齐 `<your-domain>`

## API 自检

```bash
curl https://api.<your-domain>/health

curl -X POST https://api.<your-domain>/api/mailboxes \
  -H 'Authorization: Bearer <API_TOKEN>' \
  -H 'Content-Type: application/json' \
  -d '{"prefix":"test123","label":"self-test","ttl_hours":1}'

curl 'https://api.<your-domain>/api/emails?email=test123@mail.<your-domain>' \
  -H 'Authorization: Bearer <API_TOKEN>'

curl -X POST https://api.<your-domain>/api/inbound \
  -H 'Authorization: Bearer <API_TOKEN>' \
  -H 'Content-Type: application/json' \
  -d '{
    "from": "alice@example.org",
    "to": "test123@mail.<your-domain>",
    "subject": "self-test",
    "text": "hello"
  }'
```

> ⚠ 不要把这些示例里的 `<your-domain>` 替换成任何非你自己持有的域名。免费额度会被立即耗光。