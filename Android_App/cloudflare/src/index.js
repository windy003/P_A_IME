/**
 * PyIME 同步后端(Cloudflare Worker + D1),带账号体系、按账号隔离数据。
 *
 * 账号:
 *   - 注册仅限管理员:请求头 X-Admin-Key 必须等于机密 ADMIN_KEY(wrangler secret put ADMIN_KEY)。
 *   - 普通用户只能登录;登录返回 token,后续同步请求带 X-Auth-Token。
 *   - 密码以 SHA-256(salt + 密码) 存储,不存明文。
 *   - 一个账号可同时持有多条 token(每台设备一条):在手机 B 上登录不会顶掉手机 A。
 *
 * 端点(全部 POST,JSON):
 *   /register  头 X-Admin-Key   body {username,password}          -> {ok:true}        仅管理员
 *   /login                      body {username,password}          -> {token,username}
 *   /logout    头 X-Auth-Token                                    -> {ok:true} 只注销本设备的 token
 *   /pull      头 X-Auth-Token                                    -> {folders,phrases}  仅本账号
 *   /push      头 X-Auth-Token  body {folders,phrases}  写入/更新;
 *              另可带 body {del:{folders:[uuid],phrases:[uuid]}} 按 uuid 硬删除
 *              -> {ok:true,applied} 写入本账号
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
        case "/logout":   return await handleLogout(env, request);
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
    // 每台设备一条 token,互不顶替;users.token 只作旧版兼容(见 withAuth)
    db.prepare(
      "CREATE TABLE IF NOT EXISTS sessions (" +
        "token TEXT PRIMARY KEY, username TEXT NOT NULL, created_at INTEGER NOT NULL)"
    ),
    db.prepare(
      "CREATE TABLE IF NOT EXISTS phrase_folder (" +
        "owner TEXT NOT NULL, uuid TEXT NOT NULL, name TEXT NOT NULL, " +
        "sort_order INTEGER NOT NULL DEFAULT 0, updated_at INTEGER NOT NULL, " +
        "PRIMARY KEY(owner, uuid))"
    ),
    db.prepare(
      "CREATE TABLE IF NOT EXISTS phrase (" +
        "owner TEXT NOT NULL, uuid TEXT NOT NULL, folder_uuid TEXT, " +
        "description TEXT NOT NULL DEFAULT '', content TEXT NOT NULL, " +
        "last_used_at INTEGER NOT NULL DEFAULT 0, sort_order INTEGER NOT NULL DEFAULT 0, " +
        "updated_at INTEGER NOT NULL, " +
        "PRIMARY KEY(owner, uuid))"
    ),
  ]);
  // 老库补列:phrase 表新增 description(描述,仅展示用,不参与发送)。
  // 已存在该列时 ALTER 会抛错,忽略即可。
  try {
    await db.prepare("ALTER TABLE phrase ADD COLUMN description TEXT NOT NULL DEFAULT ''").run();
  } catch (_) {}
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

  // 新发一条设备专用 token;不再覆盖 users.token,所以其他设备已持有的 token 继续有效。
  const token = crypto.randomUUID() + crypto.randomUUID();
  await env.DB.batch([
    env.DB.prepare(
      "INSERT INTO sessions (token, username, created_at) VALUES (?1,?2,?3)"
    ).bind(token, username, Date.now()),
    // 顺手清掉该账号长期不用的旧 token(正常用不到,防止表无限增长)
    env.DB.prepare("DELETE FROM sessions WHERE username = ?1 AND created_at < ?2")
      .bind(username, Date.now() - SESSION_TTL_MS),
  ]);
  return json({ token, username });
}

/** 注销当前设备这一条 token(其他设备不受影响)。 */
async function handleLogout(env, request) {
  const token = request.headers.get("X-Auth-Token");
  if (token) {
    await env.DB.batch([
      env.DB.prepare("DELETE FROM sessions WHERE token = ?1").bind(token),
      // 旧版把 token 存在 users 表里,一并清掉
      env.DB.prepare("UPDATE users SET token = NULL WHERE token = ?1").bind(token),
    ]);
  }
  return json({ ok: true });
}

/** 校验 X-Auth-Token,解析出 owner 后调用 handler(env, request, owner)。 */
async function withAuth(env, request, handler) {
  const token = request.headers.get("X-Auth-Token");
  if (!token) return json({ error: "未登录" }, 401);
  // 先查多设备的 sessions;查不到再回退到旧版的 users.token(老客户端还没重新登录时仍能用)
  let u = await env.DB.prepare("SELECT username FROM sessions WHERE token = ?1")
    .bind(token).first();
  if (!u) {
    u = await env.DB.prepare("SELECT username FROM users WHERE token = ?1")
      .bind(token).first();
  }
  if (!u) return json({ error: "登录已失效,请重新登录" }, 401);
  return await handler(env, request, u.username);
}

// ---------------------------------------------------------------- 同步
async function handlePull(env, _request, owner) {
  const folders = (await env.DB.prepare(
    "SELECT uuid, name, sort_order, updated_at FROM phrase_folder WHERE owner = ?1"
  ).bind(owner).all()).results || [];
  const phrases = (await env.DB.prepare(
    "SELECT uuid, folder_uuid, description, content, last_used_at, sort_order, updated_at " +
      "FROM phrase WHERE owner = ?1"
  ).bind(owner).all()).results || [];
  return json({ folders, phrases });
}

async function handlePush(env, request, owner) {
  const body = await request.json();
  const stmts = [];
  for (const r of body.folders || []) {
    stmts.push(env.DB.prepare(
      "INSERT OR REPLACE INTO phrase_folder (owner, uuid, name, sort_order, updated_at) VALUES (?1,?2,?3,?4,?5)"
    ).bind(owner, r.uuid, r.name, num(r.sort_order), num(r.updated_at)));
  }
  for (const r of body.phrases || []) {
    stmts.push(env.DB.prepare(
      "INSERT OR REPLACE INTO phrase (owner, uuid, folder_uuid, description, content, last_used_at, sort_order, updated_at) VALUES (?1,?2,?3,?4,?5,?6,?7,?8)"
    ).bind(owner, r.uuid, r.folder_uuid ?? null, r.description ?? "", r.content, num(r.last_used_at), num(r.sort_order), num(r.updated_at)));
  }
  // 删除:body.del.{folders,phrases} 为各表要硬删除的 uuid 数组
  const del = body.del || {};
  for (const [table, key] of [["phrase_folder", "folders"], ["phrase", "phrases"]]) {
    for (const uuid of del[key] || []) {
      stmts.push(env.DB.prepare(
        `DELETE FROM ${table} WHERE owner = ?1 AND uuid = ?2`
      ).bind(owner, uuid));
    }
  }
  if (stmts.length) await env.DB.batch(stmts);
  return json({ ok: true, applied: stmts.length });
}

// ---------------------------------------------------------------- 工具
/** 一条设备 token 的闲置保留时长:超过它在同一账号下次登录时被回收。 */
const SESSION_TTL_MS = 180 * 24 * 60 * 60 * 1000;

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
