// ── Minimal MIME body extraction (no deps) ────────────────────────────────
// Workers email handler 给的是解析好的 message.getRaw() 字节流;
// 这里用 postal-mime 类的最小实现: 只抽取 text/plain 与 text/html 部分。
async function extractMimeBodies(rawEmail) {
  const out = { text: '', html: '' };
  try {
    // 优先用 postal-mime(若安装);否则走内置简易解析
    let PostalMime = null;
    try { PostalMime = (await import('postal-mime')).default; } catch (_) { /* not installed */ }
    if (PostalMime) {
      const parsed = await PostalMime.parse(rawEmail);
      out.text = parsed.text || '';
      out.html = parsed.html || '';
      return out;
    }
    const buf = typeof rawEmail === 'string' ? rawEmail : new TextDecoder().decode(rawEmail);
    // 分离 header / body
    const sep = buf.indexOf('\r\n\r\n') >= 0 ? '\r\n\r\n' : '\n\n';
    const sepIdx = buf.indexOf(sep);
    if (sepIdx < 0) return out;
    const headers = buf.slice(0, sepIdx);
    let body = buf.slice(sepIdx + sep.length);
    const ct = (headers.match(/content-type:\s*([^\r\n;]+)/i) || [])[1] || 'text/plain';
    const isMultipart = /multipart\//i.test(ct);
    const boundaryMatch = headers.match(/boundary="?([^"\r\n;]+)"?/i);
    const decodeTransfer = (text, enc) => {
      const e = (enc || '').toLowerCase().trim();
      try {
        if (e === 'base64') { const bin = atob(text.replace(/\s+/g, '')); const bytes = new Uint8Array(bin.length); for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i); return new TextDecoder('utf-8').decode(bytes); }
        if (e === 'quoted-printable') {
          // =XX 十六进制序列解码为字节,再按 charset 统一解码,避免 latin-1 乱码
          const bytes = [];
          const clean = text.replace(/=\r?\n/g, '');
          for (let i = 0; i < clean.length; i++) {
            const ch = clean[i];
            if (ch === '=' && /^[0-9A-F]{2}$/i.test(clean.slice(i + 1, i + 3))) {
              bytes.push(parseInt(clean.slice(i + 1, i + 3), 16));
              i += 2;
            } else {
              bytes.push(ch.charCodeAt(0) & 0xff);
            }
          }
          return new TextDecoder('utf-8').decode(new Uint8Array(bytes));
        }
      } catch (_) { /* fallthrough */ }
      return text;
    };
    const charsetOf = (hdr) => (hdr.match(/charset="?([^"\r\n;]+)"?/i) || [])[1] || 'utf-8';
    if (isMultipart && boundaryMatch) {
      const boundary = '--' + boundaryMatch[1];
      const parts = body.split(boundary);
      for (const part of parts) {
        if (part.includes('--')) continue; // closing marker
        const psep = part.includes('\r\n\r\n') ? '\r\n\r\n' : '\n\n';
        const pIdx = part.indexOf(psep);
        if (pIdx < 0) continue;
        const pHdrs = part.slice(0, pIdx);
        let pBody = part.slice(pIdx + psep.length);
        const pCT = ((pHdrs.match(/content-type:\s*([^\r\n;]+)/i) || [])[1] || '').toLowerCase();
        const pCte = (pHdrs.match(/content-transfer-encoding:\s*([^\r\n;]+)/i) || [])[1] || '';
        pBody = decodeTransfer(pBody.replace(/\r?\n$/, ''), pCte);
        if (pCT.includes('text/plain') && !out.text) out.text = pBody;
        else if (pCT.includes('text/html') && !out.html) out.html = pBody;
      }
    } else {
      const cte = (headers.match(/content-transfer-encoding:\s*([^\r\n;]+)/i) || [])[1] || '';
      body = decodeTransfer(body.replace(/\r?\n$/, ''), cte);
      if (/html/i.test(ct)) out.html = body; else out.text = body;
    }
  } catch (_) { /* 保底: 空 body 不至于让整封信丢 */ }
  return out;
}

// ── Send email via Resend API ──────────────────────────────────────────────
async function sendViaResend({ from, to, subject, text, html, replyTo }, apiKey) {
  const body = { from, to: [to], subject };
  if (text) body.text = text;
  if (html) body.html = html;
  if (replyTo) body.reply_to = replyTo;

  const resp = await fetch('https://api.resend.com/emails', {
    method: 'POST',
    headers: { 'Authorization': `Bearer ${apiKey}`, 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });

  const data = await resp.json();
  if (!resp.ok) throw new Error(`Resend: ${data?.message || JSON.stringify(data)}`);
  return data; // { id: "re_xxx" }
}

// ── Send email via Cloudflare Worker send() (fallback) ────────────────────
// Not usable from fetch handler; only from email() handler.
// So we only support Resend for HTTP-triggered sends.

// ── Send endpoint ─────────────────────────────────────────────────────────
async function handleSend(req, env) {
  const body = await req.json().catch(() => null);
  if (!body) return apiResponse(env, null, false, 'invalid_json', 400);

  const to = String(body.to || '').trim();
  const subject = String(body.subject || '').trim();
  const text = String(body.text || body.body || '').trim();
  const html = String(body.html || '').trim();

  if (!to || !subject) return apiResponse(env, null, false, 'missing_to_or_subject', 400);
  if (!text && !html) return apiResponse(env, null, false, 'missing_body', 400);

  // Resolve "from": explicit, or generate a temp address
  let fromAddress = String(body.from || '').trim().toLowerCase();
  let replyTo = String(body.reply_to || '').trim().toLowerCase() || fromAddress;

  if (!fromAddress) {
    // Auto-generate a temp from address
    const prefix = `send_${randomToken(8).toLowerCase()}`;
    fromAddress = `${prefix}@${env.MAIL_DOMAIN}`;
    replyTo = fromAddress;
  }

  // Validate from address belongs to our domain tree
  const allowedRoot = rootMailDomain(env);
  const fromDomain = fromAddress.split('@')[1] || '';
  if (!(fromDomain === allowedRoot || fromDomain.endsWith(`.${allowedRoot}`))) {
    return apiResponse(env, { allowed_root: allowedRoot }, false, 'from_domain_not_allowed', 400);
  }

  const resendKey = String(env.RESEND_API_KEY || '').trim();
  if (!resendKey) return apiResponse(env, null, false, 'send_not_configured_no_resend_key', 500);

  let providerResult;
  try {
    providerResult = await sendViaResend({ from: fromAddress, to, subject, text, html, replyTo }, resendKey);
  } catch (e) {
    return apiResponse(env, { provider_error: String(e?.message || e) }, false, 'send_failed', 502);
  }

  // Store in D1
  const sentId = `sent_${randomToken(16)}`;
  await env.DB.prepare(
    'INSERT INTO sent_messages (id, from_address, to_address, subject, text_body, html_body, provider, provider_message_id, status, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)'
  ).bind(sentId, fromAddress, to, subject, text || null, html || null, 'resend', providerResult.id || null, 'sent', new Date().toISOString()).run();

  return apiResponse(env, {
    id: sentId,
    from: fromAddress,
    to,
    subject,
    provider_message_id: providerResult.id || null,
  }, true, null, 201);
}

// ── Frontend HTML ──────────────────────────────────────────────────────────
function renderSendPage(env) {
  const mailDomain = env.MAIL_DOMAIN || 'mail.<your-domain>';
  return new Response(`<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>临时邮箱发送 - ${mailDomain} | cf-mail-api</title>
<meta name="description" content="从 ${mailDomain} 发送一次性临时邮件。Cloudflare Workers + D1 驱动的轻量邮件 webhook 服务，支持 Resend 发件。">
<meta property="og:title" content="临时邮箱发送 - cf-mail-api">
<meta property="og:description" content="Cloudflare Workers 驱动的临时邮箱发送服务，基于 D1 存储，支持 Resend 外发和 Email Routing 入站。">
<meta property="og:type" content="website">
<meta name="robots" content="noindex, follow">
<style>
* { margin: 0; padding: 0; box-sizing: border-box; }
body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; background: #0f172a; color: #e2e8f0; min-height: 100vh; display: flex; flex-direction: column; align-items: center; }
.header { text-align: center; padding: 2rem 1rem 1rem; }
.header h1 { font-size: 1.5rem; color: #38bdf8; }
.header p { color: #94a3b8; margin-top: 0.3rem; font-size: 0.9rem; }
.container { width: 100%; max-width: 600px; padding: 0 1rem 2rem; }
.card { background: #1e293b; border-radius: 12px; padding: 1.5rem; margin-top: 1rem; box-shadow: 0 4px 24px rgba(0,0,0,0.3); }
label { display: block; font-size: 0.8rem; color: #94a3b8; margin-bottom: 0.3rem; margin-top: 0.8rem; }
label:first-child { margin-top: 0; }
input, textarea, select { width: 100%; padding: 0.6rem 0.8rem; background: #0f172a; border: 1px solid #334155; border-radius: 8px; color: #e2e8f0; font-size: 0.95rem; outline: none; transition: border-color 0.2s; }
input:focus, textarea:focus { border-color: #38bdf8; }
textarea { resize: vertical; min-height: 120px; font-family: inherit; }
.from-row { display: flex; gap: 0.5rem; }
.from-row input { flex: 1; }
.from-row span { align-self: center; color: #64748b; font-size: 0.85rem; white-space: nowrap; }
.btn { display: block; width: 100%; padding: 0.75rem; margin-top: 1.2rem; background: #38bdf8; color: #0f172a; border: none; border-radius: 8px; font-size: 1rem; font-weight: 600; cursor: pointer; transition: background 0.2s; }
.btn:hover { background: #7dd3fc; }
.btn:disabled { background: #475569; cursor: not-allowed; }
.result { margin-top: 1rem; padding: 1rem; border-radius: 8px; display: none; }
.result.success { display: block; background: #064e3b; border: 1px solid #10b981; }
.result.error { display: block; background: #450a0a; border: 1px solid #ef4444; }
.result .label { font-size: 0.8rem; color: #94a3b8; }
.result .value { color: #e2e8f0; margin-top: 0.2rem; word-break: break-all; }
.info { margin-top: 1.5rem; font-size: 0.8rem; color: #64748b; text-align: center; line-height: 1.6; }
.generating { display: flex; align-items: center; gap: 0.3rem; }
.generating .dot { animation: blink 1.4s infinite both; }
.generating .dot:nth-child(2) { animation-delay: 0.2s; }
.generating .dot:nth-child(3) { animation-delay: 0.4s; }
@keyframes blink { 0%, 80%, 100% { opacity: 0; } 40% { opacity: 1; } }
</style>
</head>
<body>
<div class="header">
  <h1>临时邮箱发送</h1>
  <p>从 ${mailDomain} 发送一次性邮件</p>
</div>
<div class="container">
  <div class="card">
    <label>收件人</label>
    <input type="email" id="to" placeholder="someone@example.com" required>

    <label>发件人（留空自动生成）</label>
    <div class="from-row">
      <input type="text" id="fromPrefix" placeholder="自动生成">
      <span>@</span>
      <input type="text" id="fromDomain" value="${mailDomain}" placeholder="${mailDomain}">
    </div>

    <label>主题</label>
    <input type="text" id="subject" placeholder="邮件主题">

    <label>正文</label>
    <textarea id="body" placeholder="邮件内容..."></textarea>

    <button class="btn" id="sendBtn" onclick="doSend()">发送邮件</button>

    <div class="result" id="result"></div>
  </div>

  <div class="info">
    发件地址可以是 *.${mailDomain} 下的任意子域名<br>
    收件回复会自动转发到你的 QQ 邮箱
  </div>
</div>

<script>
async function doSend() {
  const btn = document.getElementById('sendBtn');
  const resultDiv = document.getElementById('result');
  const to = document.getElementById('to').value.trim();
  const subject = document.getElementById('subject').value.trim();
  const body = document.getElementById('body').value.trim();
  const fromPrefix = document.getElementById('fromPrefix').value.trim();
  const fromDomain = document.getElementById('fromDomain').value.trim();

  if (!to || !subject || !body) {
    resultDiv.className = 'result error';
    resultDiv.style.display = 'block';
    resultDiv.innerHTML = '请填写收件人、主题和正文';
    return;
  }

  btn.disabled = true;
  btn.innerHTML = '<span class="generating">发送中<span class="dot">.</span><span class="dot">.</span><span class="dot">.</span></span>';
  resultDiv.style.display = 'none';

  try {
    const payload = { to, subject, text: body };
    if (fromPrefix && fromDomain) {
      payload.from = fromPrefix + '@' + fromDomain;
    }

    const resp = await fetch('/api/send', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'x-api-key': '<YOUR_API_TOKEN_HERE>'
      },
      body: JSON.stringify(payload)
    });

    const data = await resp.json();

    if (data.success) {
      resultDiv.className = 'result success';
      resultDiv.innerHTML =
        '<div class="label">发送成功</div>' +
        '<div class="value">发件地址：' + data.data.from + '</div>' +
        '<div class="value">收件地址：' + data.data.to + '</div>' +
        '<div class="value" style="margin-top:0.5rem;font-size:0.8rem;color:#94a3b8">对方回复时，邮件会自动转发到你的邮箱</div>';
      document.getElementById('fromPrefix').value = data.data.from.split('@')[0];
    } else {
      resultDiv.className = 'result error';
      resultDiv.innerHTML = '<div class="label">发送失败</div><div class="value">' + (data.error || JSON.stringify(data)) + '</div>';
    }
  } catch (e) {
    resultDiv.className = 'result error';
    resultDiv.innerHTML = '<div class="label">网络错误</div><div class="value">' + e.message + '</div>';
  }

  resultDiv.style.display = 'block';
  btn.disabled = false;
  btn.textContent = '发送邮件';
}
</script>
</body>
</html>`, {
    headers: { 'content-type': 'text/html; charset=utf-8' },
  });
}

function json(data, status = 200) {
  return new Response(JSON.stringify(data, null, 2), {
    status,
    headers: { 'content-type': 'application/json; charset=utf-8' }
  });
}

function randomToken(len = 32) {
  const alphabet = 'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789';
  const bytes = crypto.getRandomValues(new Uint8Array(len));
  let out = '';
  for (let i = 0; i < len; i++) out += alphabet[bytes[i] % alphabet.length];
  return out;
}

function auth(req, env) {
  const url = new URL(req.url);
  const bearer = req.headers.get('authorization') || '';
  const xApiKey = req.headers.get('x-api-key') || '';
  const queryApiKey = url.searchParams.get('api_key') || '';
  const envToken = String(env.API_TOKEN || '');
  if (!envToken) return false;
  return [bearer, xApiKey, queryApiKey].some((value) =>
    value === envToken ||
    value === `Bearer ${envToken}`
  );
}

function isValidMailboxName(name) {
  return /^[a-z0-9_-]{6,40}$/.test(name || '');
}

function pickRecipient(raw) {
  if (Array.isArray(raw)) return String(raw[0] || '').trim().toLowerCase();
  return String(raw || '').trim().toLowerCase();
}

function normalizeMessage(row) {
  if (!row) return row;
  const createdAt = row.created_at || row.received_at;
  return {
    id: row.id,
    external_id: row.external_id || null,
    email_address: row.email_address || row.to_addr || null,
    from_address: row.from_address || row.from_addr || null,
    subject: row.subject || '',
    content: row.content ?? row.text_body ?? '',
    html_content: row.html_content ?? row.html_body ?? '',
    raw_json: row.raw_json || null,
    created_at: createdAt,
    timestamp: createdAt ? Math.floor(new Date(createdAt).getTime() / 1000) : null,
    has_html: !!(row.html_content ?? row.html_body)
  };
}

async function getUsage(env) {
  const row = await env.DB.prepare(`
    SELECT
      (SELECT COUNT(*) FROM messages) AS total_messages,
      (SELECT COUNT(*) FROM messages WHERE date(received_at) = date('now')) AS used_today,
      (SELECT COUNT(*) FROM mailboxes WHERE active = 1) AS active_mailboxes
  `).first();

  const usedToday = Number(row?.used_today || 0);
  return {
    daily_limit: 200000,
    used_today: usedToday,
    remaining_today: Math.max(0, 200000 - usedToday),
    total_limit: 0,
    total_usage: Number(row?.total_messages || 0),
    remaining_total: -1,
    active_mailboxes: Number(row?.active_mailboxes || 0)
  };
}

async function apiResponse(env, data, success = true, error = null, status = 200) {
  const body = {
    success,
    data,
    usage: await getUsage(env)
  };
  if (error) body.error = error;
  return json(body, status);
}

function mailboxIdFromAddress(address) {
  return String(address || '').split('@')[0].trim().toLowerCase();
}

function rootMailDomain(env) {
  const configured = String(env.ROOT_MAIL_DOMAIN || '').trim().toLowerCase();
  if (configured) return configured;

  const mailDomain = String(env.MAIL_DOMAIN || '').trim().toLowerCase();
  const parts = mailDomain.split('.').filter(Boolean);
  if (parts.length >= 3) return parts.slice(1).join('.');
  return mailDomain;
}

function isValidDomainName(domain) {
  const value = String(domain || '').trim().toLowerCase();
  if (!value || value.length > 253 || !value.includes('.')) return false;
  const labels = value.split('.');
  return labels.every((label) => /^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$/.test(label));
}

function parseMailboxAddress(address) {
  const normalized = String(address || '').trim().toLowerCase();
  const [local = '', domain = ''] = normalized.split('@');
  return { local, domain, address: normalized };
}

function buildMailboxAddress(body, env) {
  const requestedAddress = String(body.address || body.email || '').trim().toLowerCase();
  const requestedName = String(body.name || body.prefix || '').trim().toLowerCase();
  const requestedSubdomain = String(body.subdomain || '').trim().toLowerCase();
  const requestedDomain = String(body.domain || body.email_domain || '').trim().toLowerCase();
  const defaultDomain = String(env.MAIL_DOMAIN || '').trim().toLowerCase();
  const allowedRootDomain = rootMailDomain(env);

  let mailboxName = requestedName;
  let domain = defaultDomain;

  if (requestedAddress) {
    const parsed = parseMailboxAddress(requestedAddress);
    mailboxName = parsed.local;
    domain = parsed.domain;
  } else {
    if (requestedDomain) {
      domain = requestedDomain;
    } else if (requestedSubdomain) {
      domain = `${requestedSubdomain}.${allowedRootDomain}`;
    }
  }

  if (mailboxName && !isValidMailboxName(mailboxName)) {
    throw new Error('invalid_mailbox_name');
  }
  if (!mailboxName) {
    mailboxName = `mbx_${randomToken(10).toLowerCase()}`;
  }

  if (!isValidDomainName(domain)) {
    throw new Error('invalid_domain_name');
  }

  if (!(domain === allowedRootDomain || domain.endsWith(`.${allowedRootDomain}`))) {
    throw new Error('domain_not_allowed');
  }

  return {
    mailboxName,
    domain,
    address: `${mailboxName}@${domain}`,
    rootDomain: allowedRootDomain,
    subdomain: domain === allowedRootDomain ? '' : domain.slice(0, -(allowedRootDomain.length + 1))
  };
}

async function getMailboxByAddress(address, env) {
  return env.DB.prepare(
    'SELECT id, address, token, label, created_at, expires_at, active, max_messages FROM mailboxes WHERE address = ? LIMIT 1'
  ).bind(address).first();
}

async function purgeMailboxMessages(mailboxId, env) {
  return env.DB.prepare('DELETE FROM messages WHERE mailbox_id = ?').bind(mailboxId).run();
}

async function deactivateMailbox(mailboxId, env) {
  return env.DB.prepare('UPDATE mailboxes SET active = 0 WHERE id = ?').bind(mailboxId).run();
}

async function purgeIfExpired(mailbox, env) {
  if (!mailbox?.expires_at) return false;
  const expired = new Date(mailbox.expires_at).getTime() < Date.now();
  if (!expired) return false;
  await purgeMailboxMessages(mailbox.id, env);
  await deactivateMailbox(mailbox.id, env);
  return true;
}

function decorateMailbox(row) {
  const parsed = parseMailboxAddress(row?.address || '');
  const rootDomain = rootMailDomain({ MAIL_DOMAIN: parsed.domain, ROOT_MAIL_DOMAIN: parsed.domain.split('.').length >= 3 ? parsed.domain.split('.').slice(1).join('.') : parsed.domain });
  return {
    ...row,
    mailbox_id: mailboxIdFromAddress(row?.address),
    domain: parsed.domain,
    subdomain: parsed.domain === rootDomain ? '' : parsed.domain.slice(0, -(rootDomain.length + 1))
  };
}

async function listMailboxes(env) {
  const { results } = await env.DB.prepare(
    'SELECT id, address, token, label, created_at, expires_at, active, max_messages FROM mailboxes ORDER BY created_at DESC LIMIT 200'
  ).all();

  return apiResponse(env, {
    mailboxes: results.map((row) => decorateMailbox(row))
  });
}

async function getMailbox(identifier, env) {
  const mailbox = await env.DB.prepare(
    'SELECT id, address, token, label, created_at, expires_at, active, max_messages FROM mailboxes WHERE id = ? OR address = ? LIMIT 1'
  ).bind(identifier, identifier.includes('@') ? identifier : `${identifier}@${env.MAIL_DOMAIN}`).first();

  if (!mailbox) return apiResponse(env, null, false, 'mailbox_not_found', 404);
  return apiResponse(env, decorateMailbox(mailbox));
}

async function deleteMailbox(identifier, env) {
  const mailbox = await env.DB.prepare(
    'SELECT id, address, token, label, created_at, expires_at, active, max_messages FROM mailboxes WHERE id = ? OR address = ? LIMIT 1'
  ).bind(identifier, identifier.includes('@') ? identifier : `${identifier}@${env.MAIL_DOMAIN}`).first();

  if (!mailbox) return apiResponse(env, null, false, 'mailbox_not_found', 404);

  await purgeMailboxMessages(mailbox.id, env);
  await env.DB.prepare('DELETE FROM mailboxes WHERE id = ?').bind(mailbox.id).run();
  return apiResponse(env, { deleted: true, mailbox: decorateMailbox(mailbox) });
}

async function createMailbox(req, env) {
  const body = await req.json().catch(() => ({}));
  const label = body.label || null;

  let built;
  try {
    built = buildMailboxAddress(body, env);
  } catch (e) {
    const code = String(e?.message || e);
    if (code === 'invalid_mailbox_name') {
      return apiResponse(env, { rule: '^[a-z0-9_-]{6,40}$' }, false, 'invalid_mailbox_name', 400);
    }
    if (code === 'invalid_domain_name') {
      return apiResponse(env, null, false, 'invalid_domain_name', 400);
    }
    if (code === 'domain_not_allowed') {
      return apiResponse(env, { allowed_root_domain: rootMailDomain(env) }, false, 'domain_not_allowed', 400);
    }
    throw e;
  }

  const { mailboxName, address, domain, subdomain } = built;
  const ttlMinutes = Number(body.ttl_minutes || 5);
  const ttlHours = Number(body.ttl_hours || 0);
  const effectiveTtlMs = ttlHours > 0
    ? ttlHours * 3600 * 1000
    : Math.max(1, ttlMinutes) * 60 * 1000;
  const expiresAt = new Date(Date.now() + effectiveTtlMs).toISOString();
  const maxMessages = Number(body.max_messages || 5);

  const existing = await getMailboxByAddress(address, env);
  if (existing) {
    const expiredPurged = await purgeIfExpired(existing, env);
    if (!expiredPurged && Number(existing.active) === 1) {
      return apiResponse(env, {
        id: existing.id,
        mailbox_id: mailboxName,
        email: existing.address,
        address: existing.address,
        domain,
        subdomain,
        token: existing.token,
        label: existing.label,
        created_at: existing.created_at,
        expires_at: existing.expires_at,
        active: existing.active,
        max_messages: existing.max_messages ?? 5
      });
    }

    await purgeMailboxMessages(existing.id, env);
    await env.DB.prepare('DELETE FROM mailboxes WHERE id = ?').bind(existing.id).run();
  }

  const token = randomToken(40);
  const createdAt = new Date().toISOString();
  await env.DB.prepare(
    'INSERT INTO mailboxes (address, token, label, created_at, expires_at, active, max_messages) VALUES (?, ?, ?, ?, ?, 1, ?)'
  ).bind(address, token, label || null, createdAt, expiresAt || null, maxMessages).run();

  const mailbox = await env.DB.prepare(
    'SELECT id, address, token, label, created_at, expires_at, active, max_messages FROM mailboxes WHERE address = ? LIMIT 1'
  ).bind(address).first();

  return apiResponse(env, {
    id: mailbox.id,
    mailbox_id: mailboxName,
    email: mailbox.address,
    address: mailbox.address,
    domain,
    subdomain,
    token: mailbox.token,
    label: mailbox.label,
    created_at: mailbox.created_at,
    expires_at: mailbox.expires_at,
    active: mailbox.active,
    max_messages: mailbox.max_messages
  }, true, null, 201);
}

async function listMessagesByMailboxIdentifier(identifier, env) {
  const mailbox = await env.DB.prepare(
    'SELECT id, address, token, expires_at, active, max_messages FROM mailboxes WHERE id = ? OR address = ? LIMIT 1'
  ).bind(identifier, identifier.includes('@') ? identifier : `${identifier}@${env.MAIL_DOMAIN}`).first();

  if (!mailbox) return apiResponse(env, null, false, 'mailbox_not_found', 404);

  const { results } = await env.DB.prepare(
    'SELECT id, external_id, from_addr, to_addr, subject, text_body, html_body, raw_json, received_at as created_at FROM messages WHERE mailbox_id = ? ORDER BY received_at DESC LIMIT 100'
  ).bind(mailbox.id).all();

  return apiResponse(env, {
    mailbox: { id: mailbox.id, mailbox_id: mailboxIdFromAddress(mailbox.address), address: mailbox.address },
    messages: results.map(normalizeMessage),
    count: results.length
  });
}

async function getMessageByMailbox(identifier, messageId, env) {
  const mailbox = await env.DB.prepare(
    'SELECT id, address FROM mailboxes WHERE id = ? OR address = ? LIMIT 1'
  ).bind(identifier, identifier.includes('@') ? identifier : `${identifier}@${env.MAIL_DOMAIN}`).first();

  if (!mailbox) return apiResponse(env, null, false, 'mailbox_not_found', 404);

  const message = await env.DB.prepare(
    'SELECT id, external_id, from_addr, to_addr, subject, text_body, html_body, raw_json, received_at as created_at FROM messages WHERE mailbox_id = ? AND id = ? LIMIT 1'
  ).bind(mailbox.id, messageId).first();

  if (!message) return apiResponse(env, null, false, 'message_not_found', 404);
  return apiResponse(env, normalizeMessage(message));
}

async function listEmailsByAddress(req, env) {
  const url = new URL(req.url);
  const email = String(url.searchParams.get('email') || '').trim().toLowerCase();
  if (!email) return apiResponse(env, null, false, 'missing_email_parameter', 400);

  const { results } = await env.DB.prepare(
    'SELECT id, external_id, to_addr as email_address, from_addr as from_address, subject, text_body as content, html_body as html_content, raw_json, received_at as created_at FROM messages WHERE to_addr = ? ORDER BY received_at DESC LIMIT 100'
  ).bind(email).all();

  return apiResponse(env, { emails: results.map(normalizeMessage), count: results.length });
}

async function getEmailById(messageId, env) {
  const message = await env.DB.prepare(
    'SELECT id, external_id, to_addr as email_address, from_addr as from_address, subject, text_body as content, html_body as html_content, raw_json, received_at as created_at FROM messages WHERE id = ? LIMIT 1'
  ).bind(messageId).first();

  if (!message) return apiResponse(env, null, false, 'message_not_found', 404);
  return apiResponse(env, normalizeMessage(message));
}

async function deleteEmailById(messageId, env) {
  const found = await env.DB.prepare('SELECT id FROM messages WHERE id = ? LIMIT 1').bind(messageId).first();
  if (!found) return apiResponse(env, null, false, 'message_not_found', 404);

  await env.DB.prepare('DELETE FROM messages WHERE id = ?').bind(messageId).run();
  return apiResponse(env, { message: 'Email deleted', id: messageId });
}

async function clearEmailsByAddress(req, env) {
  const url = new URL(req.url);
  const email = String(url.searchParams.get('email') || '').trim().toLowerCase();
  if (!email) return apiResponse(env, null, false, 'missing_email_parameter', 400);

  const result = await env.DB.prepare('DELETE FROM messages WHERE to_addr = ?').bind(email).run();
  return apiResponse(env, { message: 'Deleted emails', count: Number(result?.meta?.changes || 0) });
}

async function getStats(env) {
  const stats = await env.DB.prepare(`
    SELECT 
      (SELECT COUNT(*) FROM messages) as total_messages,
      (SELECT COUNT(DISTINCT to_addr) FROM messages) as total_active_mailboxes,
      (SELECT COUNT(*) FROM mailboxes WHERE active = 1) as configured_mailboxes
  `).first();
  return apiResponse(env, stats);
}

async function saveInboundMessage({ mailbox, externalId, fromAddr, toAddr, subject, textBody, htmlBody, rawJson }, env) {
  const inserted = await env.DB.prepare(
    'INSERT INTO messages (mailbox_id, external_id, from_addr, to_addr, subject, text_body, html_body, raw_json, received_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)'
  ).bind(mailbox.id, externalId, fromAddr, toAddr, subject, textBody, htmlBody, rawJson, new Date().toISOString()).run();
  return String(inserted?.meta?.last_row_id || '');
}

async function ensureMailbox(toAddr, env) {
  let mailbox = await getMailboxByAddress(toAddr, env);
  if (!mailbox) {
    await env.DB.prepare(
      'INSERT INTO mailboxes (address, token, label, created_at, expires_at, active, max_messages) VALUES (?, ?, ?, ?, ?, 1, ?)' 
    ).bind(toAddr, randomToken(40), null, new Date().toISOString(), new Date(Date.now() + 10 * 60 * 1000).toISOString(), 5).run();
    mailbox = await getMailboxByAddress(toAddr, env);
  }
  return mailbox;
}

async function handleInboundPayload(payload, rawJson, env) {
  const toAddr = pickRecipient(payload.to || payload.to_addr || payload.recipient);
  const fromAddr = pickRecipient(payload.from || payload.from_addr);
  const subject = String(payload.subject || '');
  const textBody = String(payload.text || payload.text_body || '');
  const htmlBody = String(payload.html || payload.html_body || '');
  const externalId = payload.id || payload.external_id || null;

  if (!toAddr) return { ok: false, error: 'missing_to_addr', status: 400 };

  const mailbox = await ensureMailbox(toAddr, env);
  const expiredPurged = await purgeIfExpired(mailbox, env);
  if (expiredPurged) return { ok: false, error: 'mailbox_expired', status: 410 };
  if (Number(mailbox.active) !== 1) return { ok: false, error: 'mailbox_inactive', status: 410 };

  const messageId = await saveInboundMessage({ mailbox, externalId, fromAddr, toAddr, subject, textBody, htmlBody, rawJson }, env);

  const countRow = await env.DB.prepare('SELECT COUNT(*) as count FROM messages WHERE mailbox_id = ?').bind(mailbox.id).first();
  const messageCount = Number(countRow?.count || 0);
  const maxMessages = Number(mailbox.max_messages || 5);

  let autoCleared = false;
  if (messageCount >= maxMessages) {
    await purgeMailboxMessages(mailbox.id, env);
    await deactivateMailbox(mailbox.id, env);
    autoCleared = true;
  }

  return {
    ok: true,
    data: {
      message_id: messageId,
      mailbox_id: mailboxIdFromAddress(mailbox.address),
      auto_cleared: autoCleared,
      max_messages: maxMessages,
      received_count_before_clear: messageCount,
      forwarded_to: env.FORWARD_TO_EMAIL || null
    }
  };
}

async function inbound(req, env) {
  const body = await req.json().catch(() => null);
  if (!body) return apiResponse(env, null, false, 'invalid_json', 400);

  const payload = (body && typeof body.data === 'object' && body.data) ? body.data : body;
  const result = await handleInboundPayload(payload, JSON.stringify(body), env);
  if (!result.ok) return apiResponse(env, null, false, result.error, result.status);
  return apiResponse(env, result.data);
}

async function forwardAndStore(message, env) {
  const forwardTo = String(env.FORWARD_TO_EMAIL || '').trim();
  // 真正解析 MIME 正文(此前硬编码空串,导致所有邮件 body 永远为空)
  let text = '';
  let html = '';
  try {
    // Workers ForwardableEmailMessage: raw 是 ReadableStream 属性(没有 getRaw() 方法)
    const rawStream = message.raw;
    if (rawStream) {
      const raw = new Uint8Array(await new Response(rawStream).arrayBuffer());
      const bodies = await extractMimeBodies(raw);
      text = bodies.text;
      html = bodies.html;
    }
  } catch (e) {
    console.log('email.mime_parse_failed', String(e?.message || e));
  }
  const payload = {
    id: message.headers.get('message-id') || crypto.randomUUID(),
    from: message.from,
    to: message.to,
    subject: message.headers.get('subject') || '',
    text,
    html
  };

  const result = await handleInboundPayload(payload, JSON.stringify(payload), env);
  if (!result.ok) {
    console.log('email.store_failed', result.error);
  }

  if (forwardTo) {
    try {
      await message.forward(forwardTo);
      console.log('email.forwarded', JSON.stringify({ to: forwardTo, original_to: message.to }));
    } catch (e) {
      console.log('email.forward_failed', String(e?.message || e));
    }
  }
}

export default {
  async email(message, env, ctx) {
    ctx.waitUntil(forwardAndStore(message, env));
  },

  async fetch(req, env) {
    try {
      const url = new URL(req.url);
      const path = url.pathname;

      if (path === '/health') return new Response('OK');
      if (req.method === 'POST' && path === '/api/inbound') return await inbound(req, env);
      if (!auth(req, env)) return await apiResponse(env, null, false, 'unauthorized', 401);

      if (req.method === 'GET' && path === '/api/mailboxes') return await listMailboxes(env);
      if (req.method === 'POST' && (path === '/api/mailboxes' || path === '/api/generate-email')) return await createMailbox(req, env);

      const mailboxMsgsMatch = path.match(/^\/api\/mailboxes\/([^/]+)\/messages$/);
      if (req.method === 'GET' && mailboxMsgsMatch) return await listMessagesByMailboxIdentifier(mailboxMsgsMatch[1], env);

      const mailboxMsgMatch = path.match(/^\/api\/mailboxes\/([^/]+)\/messages\/([^/]+)$/);
      if (req.method === 'GET' && mailboxMsgMatch) return await getMessageByMailbox(mailboxMsgMatch[1], mailboxMsgMatch[2], env);

      if (req.method === 'GET' && path === '/api/emails') return await listEmailsByAddress(req, env);
      if (req.method === 'DELETE' && path === '/api/emails/clear') return await clearEmailsByAddress(req, env);

      const emailMatch = path.match(/^\/api\/email\/([^/]+)$/);
      if (emailMatch) {
        if (req.method === 'GET') return await getEmailById(emailMatch[1], env);
        if (req.method === 'DELETE') return await deleteEmailById(emailMatch[1], env);
      }

      if (req.method === 'GET' && (path === '/api/stats' || path.startsWith('/api/statistics'))) return await getStats(env);
      if (req.method === 'POST' && path === '/api/send') return await handleSend(req, env);

      // Frontend
      if (path === '/' || path === '/index.html') return renderSendPage(env);

      return await apiResponse(env, null, false, 'not_found', 404);
    } catch (e) {
      return json({ success: false, error: String(e?.message || e), stack: String(e?.stack || '') }, 500);
    }
  }
};
