'use strict';

/**
 * Copies a JSON-file store (default data/db.json) into the Postgres store, e.g. to carry the laptop's test data
 * over to Neon:
 *
 *   DATABASE_URL=postgres://… node scripts/import-json.js [path/to/db.json]
 *
 * Existing rows win (ON CONFLICT DO NOTHING), so it is safe to run twice; counters only ever move up.
 */
const fs = require('node:fs');
const path = require('node:path');
const { createPgStore } = require('../src/pgStore');

async function main() {
  const url = process.env.DATABASE_URL;
  if (!url) throw new Error('Set DATABASE_URL');
  const file = process.argv[2] ?? path.join(__dirname, '..', 'data', 'db.json');
  const data = JSON.parse(fs.readFileSync(file, 'utf8'));

  const { Pool } = require('pg');
  const pool = new Pool({ connectionString: url, ssl: /localhost|127\.0\.0\.1/.test(url) ? false : { rejectUnauthorized: true } });
  await createPgStore({ pool, seed: false }); // creates the tables
  const client = await pool.connect();
  const counts = { contacts: 0, leads: 0, activities: 0, idempotency: 0 };
  try {
    await client.query('BEGIN');
    for (const c of data.contacts ?? []) {
      const r = await client.query('INSERT INTO contacts (id, phone, data) VALUES ($1, $2, $3) ON CONFLICT DO NOTHING', [c.id, c.phone, c]);
      counts.contacts += r.rowCount;
    }
    for (const l of data.leads ?? []) {
      const r = await client.query('INSERT INTO leads (id, phone, data) VALUES ($1, $2, $3) ON CONFLICT DO NOTHING', [l.id, l.phone, l]);
      counts.leads += r.rowCount;
    }
    for (const a of Object.values(data.activities ?? {})) {
      const r = await client.query('INSERT INTO activities (client_id, data) VALUES ($1, $2) ON CONFLICT DO NOTHING', [a.clientId, a]);
      counts.activities += r.rowCount;
    }
    for (const [key, record] of Object.entries(data.idempotency ?? {})) {
      const r = await client.query('INSERT INTO idempotency (key, record) VALUES ($1, $2) ON CONFLICT DO NOTHING', [key, record]);
      counts.idempotency += r.rowCount;
    }
    for (const [prefix, value] of Object.entries(data.counters ?? {})) {
      await client.query(
        'INSERT INTO counters (prefix, value) VALUES ($1, $2) ON CONFLICT (prefix) DO UPDATE SET value = GREATEST(counters.value, $2)',
        [prefix, value],
      );
    }
    await client.query('COMMIT');
  } catch (err) {
    await client.query('ROLLBACK').catch(() => {});
    throw err;
  } finally {
    client.release();
    await pool.end();
  }
  console.log(`Imported ${JSON.stringify(counts)} from ${path.basename(file)}`);
}

main().catch((err) => {
  console.error(`Import failed: ${err.code ?? err.name}: ${err.message}`);
  process.exit(1);
});
