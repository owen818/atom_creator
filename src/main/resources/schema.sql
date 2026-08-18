-- Atoms Forge SQLite schema.
-- Spring Boot runs this on startup (spring.sql.init.mode=always).

-- Account used by the demo login. X-User-Id is users.id.
CREATE TABLE IF NOT EXISTS users (
  id            INTEGER PRIMARY KEY AUTOINCREMENT,
  name          TEXT    NOT NULL,
  email         TEXT    NOT NULL UNIQUE,
  password_hash TEXT    NOT NULL,
  created_at    TEXT    NOT NULL
);

-- One generated application. status: PLAN_READY | GENERATING | READY.
CREATE TABLE IF NOT EXISTS projects (
  id         INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id    INTEGER NOT NULL,
  title      TEXT    NOT NULL,
  prompt     TEXT    NOT NULL,
  status     TEXT    NOT NULL,
  created_at TEXT    NOT NULL,
  updated_at TEXT    NOT NULL
);

-- Immutable HTML artifact. Switching history reads a row by version.
CREATE TABLE IF NOT EXISTS generations (
  id         INTEGER PRIMARY KEY AUTOINCREMENT,
  project_id INTEGER NOT NULL,
  version    INTEGER NOT NULL,
  prompt     TEXT    NOT NULL,
  html       TEXT    NOT NULL,
  provider   TEXT    NOT NULL,
  created_at TEXT    NOT NULL
);

-- One observable Agent run: plan → approve → generate → regression.
-- status: PLAN_READY | RUNNING | COMPLETED | FAILED | CANCELLED
-- stage:  AWAITING_APPROVAL | LOADING_CONTEXT | GENERATING | REGRESSION | COMPLETED | FAILED | CANCELLED
CREATE TABLE IF NOT EXISTS agent_runs (
  id             INTEGER PRIMARY KEY AUTOINCREMENT,
  project_id     INTEGER NOT NULL,
  user_id        INTEGER NOT NULL,
  prompt         TEXT    NOT NULL,
  change_type    TEXT    NOT NULL,
  plan           TEXT    NOT NULL,
  status         TEXT    NOT NULL,
  stage          TEXT    NOT NULL,
  trace          TEXT    NOT NULL,
  regression     TEXT,
  result_version INTEGER,
  created_at     TEXT    NOT NULL,
  updated_at     TEXT    NOT NULL
);
