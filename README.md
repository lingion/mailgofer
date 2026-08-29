<p align="center">
  <a href="https://img.shields.io/github/stars/lingion/mailgofer?style=for-the-badge&logo=github&color=FFD700"><img src="https://img.shields.io/github/stars/lingion/mailgofer?style=for-the-badge&logo=github&color=FFD700" alt="Stars"></a>
  <a href="https://github.com/lingion/mailgofer/network/members"><img src="https://img.shields.io/github/forks/lingion/mailgofer?style=for-the-badge&logo=github&color=8B5CF6" alt="Forks"></a>
  <a href="https://github.com/lingion/mailgofer/issues"><img src="https://img.shields.io/github/issues/lingion/mailgofer?style=for-the-badge&logo=github&color=EF4444" alt="Issues"></a>
  <a href="https://github.com/lingion/mailgofer/blob/main/LICENSE"><img src="https://img.shields.io/github/license/lingion/mailgofer?style=for-the-badge&logo=github&color=10B981" alt="License"></a>
  <br>
  <a href="https://github.com/lingion/mailgofer/commits/main"><img src="https://img.shields.io/github/last-commit/lingion/mailgofer?style=flat-square" alt="Last commit"></a>
  <img src="https://img.shields.io/badge/runtime-Cloudflare%20Workers-F38020?style=flat-square&logo=cloudflare&logoColor=white" alt="CF Workers">
  <img src="https://img.shields.io/badge/storage-Cloudflare%20D1-FF7043?style=flat-square&logo=cloudflare&logoColor=white" alt="D1">
  <img src="https://img.shields.io/badge/lang-JavaScript-F7DF1E?style=flat-square&logo=javascript&logoColor=black" alt="JS">
  <a href="README.zh.md"><img src="https://img.shields.io/badge/README-中文-CC0000?style=flat-square" alt="中文"></a>
</p>

<h1 align="center">MailGofer</h1>

<p align="center">
  A disposable mail backend on Cloudflare Workers + D1.<br>
  HTTP-native. Zero DNS. Built for scripts, not browsers.
</p>

---

## What is MailGofer?

Every temp-mail service is built for humans: open a webpage, click generate, copy an address, refresh to check for replies.

MailGofer is not that.

MailGofer is an HTTP API you deploy to your own Cloudflare account. Give your AI agent the ability to receive emails. Let your CI pipeline drop reports into a queryable address. Spin up a test mailbox with a TTL in one call during an automated signup flow.

- **One webhook**: `POST /api/inbound` deposits a message. `GET /api/emails?email=...` reads it back. That's the contract.
- **No DNS. No MX records. No SMTP**: The address `xxx@mail.<your-domain>` is just a string in D1. The domain part doesn't need to exist.
- **Free tier covers everything**: 100K requests/day, 5M D1 reads/day. You'll never come close as an individual user.
- **One command**: `wrangler deploy`. No build step. Zero npm dependencies.
- **API-first**: Bearer token auth, JSON in/out. Wire it into Claude Code or any CI script in five minutes.

---

## Who is this for?

| You want to... | Use this |
|----------------|----------|
| Open a webpage, grab a code, close the tab | Any temp-mail website (✗ — none of them have an API) |
| Have your CI script POST a message and poll it back via API | MailGofer — ✓ |
| Add an email receiver to your AI agent's tool function | MailGofer — ✓ |
| Own a programmable mail endpoint with zero recurring cost | MailGofer — ✓ |

---

## Repository Layout

| Component | Purpose |
|---|---|
| `src/index.js` | Main worker — `POST /api/inbound` plus mailbox query API |
| `schema.sql` | D1 schema (mailboxes, messages) |
| `wrangler.toml` | Worker config — placeholders must be replaced before deploy |
| `cloudflare_mail_client.py` | Optional Python client |
| `LICENSE` | GNU GPL-3.0 |
| `README.md` | English (this file) |
| `README.zh.md` | 中文文档 |

---

## Tech Stack

| Layer | Choice |
|---|---|
| Runtime | Cloudflare Workers (V8 isolates) |
| Storage | Cloudflare D1 (SQLite) |
| Inbound (core) | HTTP webhook |
| Inbound (optional) | Cloudflare Email Routing |
| Outbound (optional) | Resend HTTP API |
| Auth | Bearer token / `x-api-key` / `?api_key=<API_TOKEN>` |
| Client (optional) | Python 3 (`requests`) |

The worker has zero Node dependencies and no build step. `wrangler deploy` is all you need to run.

---

## Quick Start

### 1. Prerequisites

- A Cloudflare account (free tier is sufficient)
- `wrangler` CLI: `npm i -g wrangler`
- Node.js 18+
- No domain is required for the core webhook path. The default `*.workers.dev` route is sufficient for personal use.

### 2. Clone and install

```bash
git clone https://github.com/lingion/mailgofer.git
cd mailgofer
npm install
```

### 3. Create the D1 database

```bash
wrangler d1 create mail_api
# Copy the printed `database_id` into wrangler.toml
wrangler d1 execute mail_api --remote --file=./schema.sql
```

### 4. Configure `wrangler.toml`

The only required setting is `API_TOKEN`. Set it as an encrypted Worker secret — never a plaintext variable. All other variables are optional and correspond to one of the add-ons.

```toml
# Minimal configuration — replace <your-d1-database-id>:
name = "mailgofer"
main = "src/index.js"
compatibility_date = "2026-03-22"

[[d1_databases]]
binding = "DB"
database_name = "mail_api"
database_id = "<your-d1-database-id>"

```

Generate a token and store it as an encrypted secret (read at runtime via `env.API_TOKEN`):

```bash
openssl rand -hex 32
npx wrangler secret put API_TOKEN   # paste the token when prompted
```

Custom-domain routing is only needed if you intend to use the Email Routing add-on (see below).

### 5. Deploy

```bash
wrangler deploy
```

### 6. Smoke test

```bash
# Health check
curl https://<your-worker>.<your-subdomain>.workers.dev/health

# Generate a mailbox (optional — the webhook accepts any address)
curl -X POST https://<your-worker>.<your-subdomain>.workers.dev/api/generate-email \
  -H 'x-api-key: <API_TOKEN>' \
  -H 'Content-Type: application/json' \
  -d '{"prefix":"task_demo01","label":"signup-test","ttl_hours":24}'

# Deposit a message via the webhook
curl -X POST https://<your-worker>.<your-subdomain>.workers.dev/api/inbound \
  -H 'x-api-key: <API_TOKEN>' \
  -H 'Content-Type: application/json' \
  -d '{
    "from":    "noreply@example.org",
    "to":      "task_demo01@mail.<your-domain>",
    "subject": "Verify your account",
    "text":    "Click here to verify..."
  }'

# Read it back
curl 'https://<your-worker>.<your-subdomain>.workers.dev/api/emails?email=task_demo01@mail.<your-domain>' \
  -H 'x-api-key: <API_TOKEN>'
```

---

## API Reference

All endpoints require authentication via one of:
- `Authorization: Bearer <API_TOKEN>`
- `x-api-key: <API_TOKEN>`
- `?api_key=<API_TOKEN>`

Two exceptions: `GET /health` (plain-text `OK`, no auth) and `POST /api/inbound` (currently hit before the auth check — see its row below).

| Method | Path | Purpose |
|---|---|---|
| GET | `/health` | Health check. Returns plain text `OK` (content-type text/plain, not JSON), no auth |
| POST | `/api/generate-email` | Create a new mailbox record. Same handler as `POST /api/mailboxes` |
| POST | `/api/send` | Send via Resend (requires `RESEND_API_KEY`, 500 if unset; `from` domain must equal `ROOT_MAIL_DOMAIN` or a subdomain, else 400 `from_domain_not_allowed`) |
| GET | `/api/mailboxes` | List all mailboxes |
| GET | `/api/mailboxes/:id/messages` | List messages in a mailbox |
| GET | `/api/mailboxes/:id/messages/:msg_id` | Fetch a single message |
| GET | `/api/emails?email=...` | List messages for an address |
| GET | `/api/email/:id` | Fetch a single message by id |
| DELETE | `/api/email/:id` | Delete a single message |
| DELETE | `/api/emails/clear?email=...` | Clear all mail for an address |
| GET | `/api/stats` | Counts of mailboxes and messages |
| POST | `/api/inbound` | Webhook — deposit a message into D1. Note: currently matched before the auth check (an unauthenticated request can write to any address and auto-create the mailbox). Do not expose the worker URL publicly |

### POST /api/inbound

```bash
curl -X POST https://<your-worker>.<your-subdomain>.workers.dev/api/inbound \
  -H 'x-api-key: <API_TOKEN>' \
  -H 'Content-Type: application/json' \
  -d '{
    "from":    "alice@example.org",
    "to":      "task_demo01@mail.<your-domain>",
    "subject": "Verify your account",
    "text":    "Click here to verify...",
    "html":    "<a href=\"...\">Verify</a>"
  }'
```

Request fields (only `to` is required):

| Field | Aliases | Notes |
|---|---|---|
| `to` | `to_addr`, `recipient` | Target mailbox address. String or array; first element is used. |
| `from` | `from_addr` | Sender. String or array; first element is used. |
| `subject` | — | Mail subject. |
| `text` | `text_body`, `body` | Plain-text body. |
| `html` | `html_body` | HTML body. |
| `id` | `external_id` | Optional external message id for correlation. |

If `to` does not match an existing mailbox in D1, the worker creates one. The webhook therefore accepts messages into any address, registered or not.

### POST /api/generate-email

```bash
curl -X POST https://<your-worker>.<your-subdomain>.workers.dev/api/generate-email \
  -H 'x-api-key: <API_TOKEN>' \
  -H 'Content-Type: application/json' \
  -d '{"prefix":"task_demo01","label":"signup-test","ttl_hours":24}'
```

| Field | Rule |
|---|---|
| `prefix` / `name` | Optional. Must match `^[a-z0-9_-]{6,40}$` when provided. |
| `address` / `email` | Optional. Full address `local@domain`; takes priority over prefix+domain. |
| `subdomain` | Optional. Generates `subdomain.<root domain>`. |
| `domain` / `email_domain` | Optional. Must be the root domain or its subdomain; otherwise 400 `domain_not_allowed`. |
| `label` | Optional free-form tag. |
| `ttl_hours` | Optional mailbox lifetime in hours. Takes priority when > 0. |
| `ttl_minutes` | Optional lifetime in minutes; used when `ttl_hours` is not given. Defaults to 5, minimum 1. |
| `max_messages` | Optional message cap, default 5. When reached the mailbox is auto-cleared and deactivated. |

Both `POST /api/generate-email` and `POST /api/mailboxes` hit the same handler and return the same shape: `{ success, data: { id, mailbox_id, email, address, domain, subdomain, token, label, created_at, expires_at, active, max_messages }, usage }`.

This endpoint creates a tracked mailbox record with metadata. It is not a prerequisite for receiving mail — the webhook accepts any address.

### GET endpoints

```bash
# Messages for an address
curl 'https://<your-worker>.<your-subdomain>.workers.dev/api/emails?email=task_demo01@mail.<your-domain>' \
  -H 'x-api-key: <API_TOKEN>'

# Single message by id
curl 'https://<your-worker>.<your-subdomain>.workers.dev/api/email/<message_id>' \
  -H 'x-api-key: <API_TOKEN>'

# Mailboxes
curl 'https://<your-worker>.<your-subdomain>.workers.dev/api/mailboxes' -H 'x-api-key: <API_TOKEN>'

# Stats
curl 'https://<your-worker>.<your-subdomain>.workers.dev/api/stats' -H 'x-api-key: <API_TOKEN>'
```

`GET /api/mailboxes` returns `{ success, data: { mailboxes: [...] }, usage }`. Each item carries `id, address, token, label, created_at, expires_at, active, max_messages, mailbox_id, domain, subdomain`. At most 200 entries, newest first (`created_at DESC`).

### DELETE endpoints

```bash
# Delete a single message
curl -X DELETE 'https://<your-worker>.<your-subdomain>.workers.dev/api/email/<message_id>' \
  -H 'x-api-key: <API_TOKEN>'

# Clear all mail for an address
curl -X DELETE 'https://<your-worker>.<your-subdomain>.workers.dev/api/emails/clear?email=<addr>@mail.<your-domain>' \
  -H 'x-api-key: <API_TOKEN>'
```

---

## Optional Add-ons

The webhook path functions without any of these. Enable them only if needed.

### Add-on A — Real SMTP via Email Routing

Allows the worker to receive mail that real SMTP servers deliver to `xxx@mail.<your-domain>`.

1. Add a domain to Cloudflare with DNS on CF.
2. In `wrangler.toml`, add a route:
   ```toml
   [[routes]]
   pattern = "api.<your-domain>/*"
   zone_name = "<your-domain>"
   ```
3. Set `[vars] MAIL_DOMAIN = "mail.<your-domain>"`.
4. In the Cloudflare dashboard for the zone: **Email → Email Routing → Enable**, then add a catch-all route `*@mail.<your-domain>` → **Send to Worker** → `mailgofer`.

Real SMTP messages are dispatched to the worker by Cloudflare and stored in D1 via the same path used by the webhook.

### Add-on B — Forward to a real inbox

In `wrangler.toml`:

```toml
[vars]
FORWARD_TO_EMAIL = "you@gmail.com"
```

The worker reads this variable and uses it as the destination for a secondary copy of each inbound message. Delivery of the copy is delegated to whatever forwarder is wired up in the worker code; this variable only specifies the address.

### Add-on C — Outbound send via Resend

Add a third route `send.<your-domain>` and set a Resend API key:

```toml
[vars]
RESEND_API_KEY = "***"
```

```bash
curl -X POST https://send.<your-domain>/api/send \
  -H 'x-api-key: <API_TOKEN>' \
  -H 'Content-Type: application/json' \
  -d '{
    "from":    "task_demo01@mail.<your-domain>",
    "to":      "bob@example.org",
    "subject": "Hello",
    "text":    "Sent via MailGofer"
  }'
```

`from` must reference a mailbox that exists in D1. The domain must have a valid SPF/DKIM record for deliverability.

---

## Configuration Reference

| Variable | Required | Purpose |
|---|---|---|
| `API_TOKEN` | yes | Bearer token for the API. Set via `wrangler secret put API_TOKEN` — never commit it to `wrangler.toml`. |
| `MAIL_DOMAIN` | no | Display domain used in generated mailbox addresses. Not used for routing. |
| `FORWARD_TO_EMAIL` | no | Destination address for forwarded copies. Add-on B. |
| `RESEND_API_KEY` | no | Outbound provider key. Add-on C. |

---

## Cost and Quota

The worker is designed to run entirely within Cloudflare's free tier:

| Resource | Free tier |
|---|---|
| Workers requests | 100,000 / day |
| D1 reads | 5,000,000 / day |
| D1 writes | 100,000 / day |
| Email Routing messages | Rate-limited per destination address; Cloudflare's current limits page (updated 2026-06) no longer publishes a fixed daily number (older docs: 25/min, 100/day per destination) — trust the dashboard (Add-on A only) |

Auth prevents anonymous access, but won't save you if the token leaks. Don't expose your worker URL publicly — once someone burns through the free quota, your deployment becomes unusable.

---

## Repository Rule

`lingion/mailgofer` is the sole upstream for this project. Don't treat mirrors or forks as the primary entry point. Everything merges here.

---

## Documentation

- `README.md` — English (this file)
- `README.zh.md` — 中文文档
- `RESEND_SETUP.md` — Legacy Resend / outbound setup notes
- `schema.sql` — D1 database schema reference

---

## License

GNU General Public License v3.0. See [LICENSE](./LICENSE).

You may use, modify, and redistribute this work, including for commercial purposes, provided that derivative works are also licensed under GPL-3.0 and the copyright notice is preserved. No warranty is provided.

---

## Contributing

PRs are accepted at <https://github.com/lingion/mailgofer>. By contributing, you agree that your contribution is licensed under GPL-3.0.
