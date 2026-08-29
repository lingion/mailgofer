// RED test: createMailbox 同名活跃邮箱必须按"本次请求约束覆盖"语义重写。
// 本地 node:sqlite 模拟 D1,纯内存文件,零网络,不连生产。
import { strict as assert } from 'node:assert';
import { DatabaseSync } from 'node:sqlite';
import { mkdtempSync, readFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import worker from '../src/index.js';

// ── D1 adapter over node:sqlite ──────────────────────────────────────────────
class D1Stmt {
  constructor(db, sql) { this.db = db; this.sql = sql; this.args = []; }
  bind(...args) { this.args = args; return this; }
  async first() { return this.db.prepare(this.sql).get(...this.args) ?? null; }
  async run() { this.db.prepare(this.sql).run(...this.args); return { success: true }; }
  async all() { return { results: this.db.prepare(this.sql).all(...this.args) }; }
}
class D1 {
  constructor(file) { this.db = new DatabaseSync(file); }
  prepare(sql) { return new D1Stmt(this.db, sql); }
  raw() { return this.db; }
}

// schema 与既有本地 D1 一致: INTEGER PRIMARY KEY AUTOINCREMENT(任务给定)
const SCHEMA = readFileSync(new URL('../schema.sql', import.meta.url), 'utf8')
  .replace('id TEXT PRIMARY KEY,', 'id INTEGER PRIMARY KEY AUTOINCREMENT,');

const dir = mkdtempSync(join(tmpdir(), 'mgf-test-'));
const db = new D1(join(dir, 'test.db'));
db.raw().exec('PRAGMA foreign_keys=ON');
db.raw().exec(SCHEMA);

const env = {
  DB: db,
  MAIL_DOMAIN: 'mail.qdp.qzz.io',
  API_TOKEN: 'localtesttoken123'
};

async function call(method, path, body) {
  const req = new Request(`https://api.test.local${path}`, {
    method,
    headers: { 'content-type': 'application/json', 'x-api-key': env.API_TOKEN },
    body: body === undefined ? undefined : JSON.stringify(body)
  });
  const res = await worker.fetch(req, env);
  const json = await res.json();
  if (res.status >= 500) console.log(`  [debug ${method} ${path}]`, JSON.stringify(json));
  return { status: res.status, json };
}

function rowByAddress(addr) {
  return db.raw().prepare('SELECT * FROM mailboxes WHERE address = ?').get(addr) ?? null;
}
function msgCount(mailboxId) {
  return db.raw().prepare('SELECT COUNT(*) AS n FROM messages WHERE mailbox_id = ?').get(mailboxId).n;
}
function insertMessage(mailboxId, subject) {
  db.raw().prepare(
    "INSERT INTO messages (mailbox_id, from_addr, to_addr, subject, text_body, received_at) VALUES (?, 'a@b.c', ?, ?, 'body', ?)"
  ).run(mailboxId, mailboxId, subject, new Date().toISOString());
}

const NAME = 'reapply1';
const ADDR = `${NAME}@mail.qdp.qzz.io`;
const results = [];
const t = (name, fn) => results.push({ name, fn });

// ── Case 0a: 全新邮箱路径不回归 ─────────────────────────────────────────────
t('fresh create with ttl_hours=1 max=5 → 201 with new values', async () => {
  const r = await call('POST', '/api/mailboxes', { name: NAME, ttl_hours: 1, max_messages: 5 });
  assert.equal(r.status, 201);
  assert.equal(r.json.success, true);
  assert.ok(r.json.data.expires_at, 'expires_at set');
  assert.equal(r.json.data.max_messages, 5);
  assert.equal(r.json.data.active, 1);
  const row = rowByAddress(ADDR);
  assert.equal(row.expires_at, r.json.data.expires_at);
  assert.equal(row.max_messages, 5);
});

// ── Case 0b: 全新邮箱无任何约束 → 400 constraint_required(不回归) ──────────
t('fresh create without constraints → 400 constraint_required', async () => {
  const r = await call('POST', '/api/mailboxes', { name: 'nocase1' });
  assert.equal(r.status, 400);
  assert.equal(r.json.error, 'constraint_required');
});

// 种旧邮件: 2 封,重建全程必须保留(在 case 0a 建邮箱之后、case 1 之前执行)
let originalCreatedAt = null;
let originalToken = null;
t('seed 2 old messages into the mailbox', async () => {
  const seedRow0 = rowByAddress(ADDR);
  assert.ok(seedRow0, 'mailbox must exist after case 0a');
  insertMessage(seedRow0.id, 'keep-me-1');
  insertMessage(seedRow0.id, 'keep-me-2');
  originalCreatedAt = seedRow0.created_at;
  originalToken = seedRow0.token;
  assert.equal(msgCount(seedRow0.id), 2);
});

// ── Case 1: 同名活跃邮箱,无 ttl + max=50 → expires_at=null, max=50 ────────
t('same-name re-create (no ttl, max=50) → expires_at=null, max_messages=50, active=1 [BUG REPRO]', async () => {
  const r = await call('POST', '/api/mailboxes', { name: NAME, max_messages: 50 });
  assert.equal(r.status, 200);
  assert.equal(r.json.success, true);
  assert.equal(r.json.data.expires_at, null, 'response expires_at must be null (request authority)');
  assert.equal(r.json.data.max_messages, 50, 'response max_messages must follow request');
  assert.equal(r.json.data.active, 1);
  const row = rowByAddress(ADDR);
  assert.equal(row.expires_at, null, 'db expires_at must be null');
  assert.equal(row.max_messages, 50, 'db max_messages must be 50');
  assert.equal(row.active, 1);
  assert.equal(row.token, originalToken, 'token must not rotate');
});

// ── Case 2: 同名再建 ttl_hours=2 max=10 → ≈now+2h,max=10,created_at 刷新 ──
t('same-name re-create (ttl_hours=2, max=10) → expires_at≈now+2h, max=10, created_at refreshed', async () => {
  const before = rowByAddress(ADDR);
  const t0 = Date.now();
  const r = await call('POST', '/api/mailboxes', { name: NAME, ttl_hours: 2, max_messages: 10 });
  const t1 = Date.now();
  assert.equal(r.status, 200);
  const got = Date.parse(r.json.data.expires_at);
  const min = t0 + 2 * 3600 * 1000 - 60 * 1000;
  const max = t1 + 2 * 3600 * 1000 + 60 * 1000;
  assert.ok(got >= min && got <= max, `expires_at ${r.json.data.expires_at} ≈ now+2h`);
  assert.equal(r.json.data.max_messages, 10);
  assert.notEqual(r.json.data.created_at, before.created_at, 'created_at must be refreshed');
  assert.ok(Date.parse(r.json.data.created_at) >= t0 - 1000 && Date.parse(r.json.data.created_at) <= t1 + 1000,
    'created_at ≈ now');
  // 安卓端 originalTtlMinutes 反推语义: expires - created ≈ 2h
  const derivedMs = Date.parse(r.json.data.expires_at) - Date.parse(r.json.data.created_at);
  assert.ok(Math.abs(derivedMs - 2 * 3600 * 1000) < 2000,
    `expires-created should invert to ttl (got ${derivedMs}ms)`);
  const row = rowByAddress(ADDR);
  assert.equal(row.expires_at, r.json.data.expires_at);
  assert.equal(row.max_messages, 10);
});

// ── Case 3: 全程旧 messages 保留 ────────────────────────────────────────────
t('old messages preserved through all re-creates', async () => {
  const row = rowByAddress(ADDR);
  assert.equal(msgCount(row.id), 2);
  const subjects = db.raw().prepare('SELECT subject FROM messages WHERE mailbox_id = ? ORDER BY subject').all(row.id)
    .map((r) => r.subject);
  assert.deepEqual(subjects, ['keep-me-1', 'keep-me-2']);
});

// ── Case 4: refresh 路由一行未动(回归哨兵) ────────────────────────────────
t('refresh route unchanged: purges messages, re-arms on ttl, keeps omitted max_messages', async () => {
  await call('POST', '/api/mailboxes', { name: 'refresh1', max_messages: 7, ttl_hours: 1 });
  const r1 = rowByAddress('refresh1@mail.qdp.qzz.io');
  insertMessage(r1.id, 'doomed');
  assert.equal(msgCount(r1.id), 1);
  const t0 = Date.now();
  const r = await call('POST', `/api/mailboxes/${r1.id}/refresh`, { ttl_minutes: 5 });
  const t1 = Date.now();
  assert.equal(r.status, 200);
  assert.equal(r.json.data.purged_messages, true, 'refresh still purges');
  assert.equal(msgCount(r1.id), 0, 'refresh purged the message');
  assert.equal(r.json.data.max_messages, 7, 'refresh keeps omitted max_messages (old behavior)');
  const got = Date.parse(r.json.data.expires_at);
  assert.ok(got >= t0 + 5 * 60 * 1000 - 60 * 1000 && got <= t1 + 5 * 60 * 1000 + 60 * 1000,
    'refresh with ttl_minutes=5 re-arms expires_at ≈ now+5m');
});

// ── run ──────────────────────────────────────────────────────────────────────
let failed = 0;
for (const { name, fn } of results) {
  try {
    await fn();
    console.log(`PASS  ${name}`);
  } catch (e) {
    failed++;
    console.log(`FAIL  ${name}\n      ${String(e.message).split('\n').join('\n      ')}`);
  }
}
console.log(`\n${results.length - failed}/${results.length} passed`);
process.exit(failed ? 1 : 0);
