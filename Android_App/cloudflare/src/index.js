/**
 * PyIME 同步后端(Cloudflare Worker + D1),带账号体系、按账号隔离数据。
 *
 * 账号:
 *   - 注册仅限管理员:请求头 X-Admin-Key 必须等于机密 ADMIN_KEY(wrangler secret put ADMIN_KEY)。
 *   - 普通用户只能登录;登录返回 token,后续同步请求带 X-Auth-Token。
 *   - 密码以 SHA-256(salt + 密码) 存储,不存明文。
 *
 * 端点(全部 POST,JSON):
 *   /register  头 X-Admin-Key   body {username,password}          -> {ok:true}        仅管理员
 *   /login                      body {username,password}          -> {token,username}
 *   /pull      头 X-Auth-Token                                    -> {clipboard,folders,phrases}  仅本账号
 *   /push      头 X-Auth-Token  body {clipboard,folders,phrases}  -> {ok:true,applied} 写入本账号
 *
 * 数据表均含 owner 列,主键 (owner, uuid),不同账号互不可见。
 */

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    try {
      await ensureTables(env.DB);
      if (request.method !== "POST") return json({ error: "method" }, 405);

      switch (url.pathname) {
        case "/register": return await handleRegister(env, request);
        case "/login":    return await handleLogin(env, request);
        case "/pull":     return await withAuth(env, request, handlePull);
        case "/push":     return await withAuth(env, request, handlePush);
        default:          return json({ error: "not found" }, 404);
      }
    } catch (e) {
      return json({ error: String(e && e.message ? e.message : e) }, 500);
    }
  },
};

// ---------------------------------------------------------------- 表
async function ensureTables(db) {
  await db.batch([
    db.prepare(
      "CREATE TABLE IF NOT EXISTS users (" +
        "username TEXT PRIMARY KEY, salt TEXT NOT NULL, pass_hash TEXT NOT NULL, " +
        "token TEXT, created_at INTEGER NOT NULL)"
    ),
    db.prepare(
      "CREATE TABLE IF NOT EXISTS clipboard (" +
        "owner TEXT NOT NULL, uuid TEXT NOT NULL, content TEXT NOT NULL, " +
        "updated_at INTEGER NOT NULL, deleted INTEGER NOT NULL DEFAULT 0, " +
        "PRIMARY KEY(owner, uuid))"
    ),
    db.prepare(
      "CREATE TABLE IF NOT EXISTS phrase_folder (" +
        "owner TEXT NOT NULL, uuid TEXT NOT NULL, name TEXT NOT NULL, " +
        "sort_order INTEGER NOT NULL DEFAULT 0, updated_at INTEGER NOT NULL, " +
        "deleted INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(owner, uuid))"
    ),
    db.prepare(
      "CREATE TABLE IF NOT EXISTS phrase (" +
        "owner TEXT NOT NULL, uuid TEXT NOT NULL, folder_uuid TEXT, content TEXT NOT NULL, " +
        "last_used_at INTEGER NOT NULL DEFAULT 0, sort_order INTEGER NOT NULL DEFAULT 0, " +
        "updated_at INTEGER NOT NULL, deleted INTEGER NOT NULL DEFAULT 0, " +
        "PRIMARY KEY(owner, uuid))"
    ),
  ]);
}

// ---------------------------------------------------------------- 账号
async function handleRegister(env, request) {
  if (!env.ADMIN_KEY || request.headers.get("X-Admin-Key") !== env.ADMIN_KEY) {
    return json({ error: "仅管理员可注册" }, 403);
  }
  const { username, password } = await request.json();
  if (!username || !password) return json({ error: "用户名/密码不能为空" }, 400);

  const exists = await env.DB.prepare("SELECT username FROM users WHERE username = ?1")
    .bind(username).first();
  if (exists) return json({ error: "用户名已存在" }, 409);

  const salt = crypto.randomUUID();
  const hash = await sha256(salt + password);
  await env.DB.prepare(
    "INSERT INTO users (username, salt, pass_hash, token, created_at) VALUES (?1,?2,?3,NULL,?4)"
  ).bind(username, salt, hash, Date.now()).run();
  return json({ ok: true });
}

async function handleLogin(env, request) {
  const { username, password } = await request.json();
  if (!username || !password) return json({ error: "用户名/密码不能为空" }, 400);

  const u = await env.DB.prepare("SELECT salt, pass_hash FROM users WHERE username = ?1")
    .bind(username).first();
  if (!u) return json({ error: "用户名或密码错误" }, 401);
  const hash = await sha256(u.salt + password);
  if (hash !== u.pass_hash) return json({ error: "用户名或密码错误" }, 401);

  const token = crypto.randomUUID() + crypto.randomUUID();
  await env.DB.prepare("UPDATE users SET token = ?1 WHERE username = ?2")
    .bind(token, username).run();
  return json({ token, username });
}

/** 校验 X-Auth-Token,解析出 owner 后调用 handler(env, request, owner)。 */
async function withAuth(env, request, handler) {
  const token = request.headers.get("X-Auth-Token");
  if (!token) return json({ error: "未登录" }, 401);
  const u = await env.DB.prepare("SELECT username FROM users WHERE token = ?1")
    .bind(token).first();
  if (!u) return json({ error: "登录已失效,请重新登录" }, 401);
  return await handler(env, request, u.username);
}

// ---------------------------------------------------------------- 同步
async function handlePull(env, _request, owner) {
  const clipboard = (await env.DB.prepare(
    "SELECT uuid, content, updated_at, deleted FROM clipboard WHERE owner = ?1"
  ).bind(owner).all()).results || [];
  const folders = (await env.DB.prepare(
    "SELECT uuid, name, sort_order, updated_at, deleted FROM phrase_folder WHERE owner = ?1"
  ).bind(owner).all()).results || [];
  const phrases = (await env.DB.prepare(
    "SELECT uuid, folder_uuid, content, last_used_at, sort_order, updated_at, deleted FROM phrase WHERE owner = ?1"
  ).bind(owner).all()).results || [];
  return json({ clipboard, folders, phrases });
}

async function handlePush(env, request, owner) {
  const body = await request.json();
  const stmts = [];
  for (const r of body.clipboard || []) {
    stmts.push(env.DB.prepare(
      "INSERT OR REPLACE INTO clipboard (owner, uuid, content, updated_at, deleted) VALUES (?1,?2,?3,?4,?5)"
    ).bind(owner, r.uuid, r.content, num(r.updated_at), num(r.deleted)));
  }
  for (const r of body.folders || []) {
    stmts.push(env.DB.prepare(
      "INSERT OR REPLACE INTO phrase_folder (owner, uuid, name, sort_order, updated_at, deleted) VALUES (?1,?2,?3,?4,?5,?6)"
    ).bind(owner, r.uuid, r.name, num(r.sort_order), num(r.updated_at), num(r.deleted)));
  }
  for (const r of body.phrases || []) {
    stmts.push(env.DB.prepare(
      "INSERT OR REPLACE INTO phrase (owner, uuid, folder_uuid, content, last_used_at, sort_order, updated_at, deleted) VALUES (?1,?2,?3,?4,?5,?6,?7,?8)"
    ).bind(owner, r.uuid, r.folder_uuid ?? null, r.content, num(r.last_used_at), num(r.sort_order), num(r.updated_at), num(r.deleted)));
  }
  if (stmts.length) await env.DB.batch(stmts);
  return json({ ok: true, applied: stmts.length });
}

// ---------------------------------------------------------------- 工具
async function sha256(text) {
  const buf = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(text));
  return [...new Uint8Array(buf)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

function num(v) { return v == null ? 0 : Number(v); }

function json(obj, status = 200) {
  return new Response(JSON.stringify(obj), {
    status,
    headers: { "content-type": "application/json; charset=utf-8" },
  });
}
