-- 仅作参考:Worker 首次请求时会自动 CREATE TABLE IF NOT EXISTS 建好这些表,
-- 一般无需手动执行。需要手动建表时:wrangler d1 execute pyime --file=schema.sql

CREATE TABLE IF NOT EXISTS users (
  username   TEXT PRIMARY KEY,
  salt       TEXT NOT NULL,
  pass_hash  TEXT NOT NULL,
  token      TEXT,
  created_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS clipboard (
  owner      TEXT NOT NULL,
  uuid       TEXT NOT NULL,
  content    TEXT NOT NULL,
  updated_at INTEGER NOT NULL,
  PRIMARY KEY (owner, uuid)
);

CREATE TABLE IF NOT EXISTS phrase_folder (
  owner      TEXT NOT NULL,
  uuid       TEXT NOT NULL,
  name       TEXT NOT NULL,
  sort_order INTEGER NOT NULL DEFAULT 0,
  updated_at INTEGER NOT NULL,
  PRIMARY KEY (owner, uuid)
);

CREATE TABLE IF NOT EXISTS phrase (
  owner        TEXT NOT NULL,
  uuid         TEXT NOT NULL,
  folder_uuid  TEXT,
  content      TEXT NOT NULL,
  last_used_at INTEGER NOT NULL DEFAULT 0,
  sort_order   INTEGER NOT NULL DEFAULT 0,
  updated_at   INTEGER NOT NULL,
  PRIMARY KEY (owner, uuid)
);
