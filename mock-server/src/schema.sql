-- Mock CRM schema. Applied at start-up (idempotent). Records are kept as JSONB, the same objects the
-- JSON-file store holds; the columns next to them carry the keys the API relies on, enforced by the database.

CREATE TABLE IF NOT EXISTS contacts (
  id    TEXT PRIMARY KEY,
  phone TEXT NOT NULL UNIQUE,
  data  JSONB NOT NULL
);

CREATE TABLE IF NOT EXISTS leads (
  id    TEXT PRIMARY KEY,
  phone TEXT NOT NULL UNIQUE, -- one lead per number: POST /leads answers 409 PHONE_EXISTS
  data  JSONB NOT NULL
);

CREATE TABLE IF NOT EXISTS activities (
  client_id TEXT PRIMARY KEY, -- the app's idempotency key for the activity
  data      JSONB NOT NULL
);

CREATE TABLE IF NOT EXISTS idempotency (
  key    TEXT PRIMARY KEY,
  record JSONB NOT NULL
);

CREATE TABLE IF NOT EXISTS counters (
  prefix TEXT PRIMARY KEY,
  value  INTEGER NOT NULL
);
