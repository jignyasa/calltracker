'use strict';

const fs = require('node:fs');
const path = require('node:path');
const { Pool } = require('pg');
const { seedData } = require('./seed');
const { phoneExists } = require('./store');

const SCHEMA = fs.readFileSync(path.join(__dirname, 'schema.sql'), 'utf8');
const UNIQUE_VIOLATION = '23505';

/**
 * Postgres store (e.g. Neon), same async interface as the memory/JSON store in `store.js`.
 * `transaction(lockKey, fn)` runs `fn` in one database transaction holding an advisory lock on `lockKey`, so two
 * requests for the same lead key or activity clientId are serialised even across server instances.
 */
async function createPgStore({ connectionString, seed = true, pool: givenPool } = {}) {
  const pool = givenPool ?? new Pool({
    connectionString,
    // Neon and other hosted Postgres require TLS; certificates are checked.
    ssl: /localhost|127\.0\.0\.1/.test(connectionString) ? false : { rejectUnauthorized: true },
    max: 5,
    idleTimeoutMillis: 30_000,
  });
  pool.on('error', (err) => console.error(`[pg] idle client error: ${err.code ?? err.name}`));

  await pool.query(SCHEMA);
  if (seed) await seedIfEmpty(pool);

  function opsFor(db) {
    return {
      async findByPhone(phone) {
        const contact = await db.query('SELECT data FROM contacts WHERE phone = $1', [phone]);
        if (contact.rows[0]) return { type: 'CONTACT', ...contact.rows[0].data };
        const lead = await db.query('SELECT data FROM leads WHERE phone = $1', [phone]);
        if (lead.rows[0]) return { type: 'LEAD', ...lead.rows[0].data };
        return null;
      },
      leadExists: async (id) => (await db.query('SELECT 1 FROM leads WHERE id = $1', [id])).rowCount > 0,
      contactExists: async (id) => (await db.query('SELECT 1 FROM contacts WHERE id = $1', [id])).rowCount > 0,
      async addLead(lead) {
        try {
          await db.query('INSERT INTO leads (id, phone, data) VALUES ($1, $2, $3)', [lead.id, lead.phone, lead]);
        } catch (err) {
          if (err.code === UNIQUE_VIOLATION) throw phoneExists();
          throw err;
        }
        return lead;
      },
      async getActivity(clientId) {
        const res = await db.query('SELECT data FROM activities WHERE client_id = $1', [clientId]);
        return res.rows[0]?.data ?? null;
      },
      async putActivity(activity) {
        await db.query(
          `INSERT INTO activities (client_id, data) VALUES ($1, $2)
           ON CONFLICT (client_id) DO UPDATE SET data = EXCLUDED.data`,
          [activity.clientId, activity],
        );
        return activity;
      },
      async getIdempotent(key) {
        const res = await db.query('SELECT record FROM idempotency WHERE key = $1', [key]);
        return res.rows[0]?.record ?? null;
      },
      async putIdempotent(key, record) {
        await db.query('INSERT INTO idempotency (key, record) VALUES ($1, $2) ON CONFLICT (key) DO NOTHING', [key, record]);
      },
      async nextId(prefix) {
        const res = await db.query(
          `INSERT INTO counters (prefix, value) VALUES ($1, 1)
           ON CONFLICT (prefix) DO UPDATE SET value = counters.value + 1 RETURNING value`,
          [prefix],
        );
        return `${prefix}_${String(res.rows[0].value).padStart(5, '0')}`;
      },
    };
  }

  return {
    ...opsFor(pool),
    async snapshot() {
      const [contacts, leads, activities, idempotency, counters] = await Promise.all([
        pool.query('SELECT data FROM contacts ORDER BY id'),
        pool.query('SELECT data FROM leads ORDER BY id'),
        pool.query("SELECT client_id, data FROM activities ORDER BY data->>'serverId'"),
        pool.query('SELECT key, record FROM idempotency'),
        pool.query('SELECT prefix, value FROM counters ORDER BY prefix'),
      ]);
      return {
        contacts: contacts.rows.map((r) => r.data),
        leads: leads.rows.map((r) => r.data),
        activities: Object.fromEntries(activities.rows.map((r) => [r.client_id, r.data])),
        idempotency: Object.fromEntries(idempotency.rows.map((r) => [r.key, r.record])),
        counters: Object.fromEntries(counters.rows.map((r) => [r.prefix, r.value])),
      };
    },
    async reset() {
      await pool.query('TRUNCATE contacts, leads, activities, idempotency, counters');
      if (seed) await seedIfEmpty(pool);
    },
    async transaction(lockKey, fn) {
      const client = await pool.connect();
      try {
        await client.query('BEGIN');
        await client.query('SELECT pg_advisory_xact_lock(hashtext($1))', [lockKey]);
        const result = await fn(opsFor(client));
        await client.query('COMMIT');
        return result;
      } catch (err) {
        await client.query('ROLLBACK').catch(() => {});
        throw err;
      } finally {
        client.release();
      }
    },
    close: () => pool.end(),
    flushNow() {},
  };
}

async function seedIfEmpty(pool) {
  const client = await pool.connect();
  try {
    await client.query('BEGIN');
    // Several instances may start at once: only one seeds.
    await client.query("SELECT pg_advisory_xact_lock(hashtext('seed'))");
    const { rows } = await client.query('SELECT (SELECT count(*) FROM contacts) + (SELECT count(*) FROM leads) AS n');
    if (Number(rows[0].n) === 0) {
      const data = seedData();
      for (const c of data.contacts) {
        await client.query('INSERT INTO contacts (id, phone, data) VALUES ($1, $2, $3)', [c.id, c.phone, c]);
      }
      for (const l of data.leads) {
        await client.query('INSERT INTO leads (id, phone, data) VALUES ($1, $2, $3)', [l.id, l.phone, l]);
      }
      for (const [prefix, value] of Object.entries(data.counters)) {
        await client.query(
          'INSERT INTO counters (prefix, value) VALUES ($1, $2) ON CONFLICT (prefix) DO UPDATE SET value = GREATEST(counters.value, $2)',
          [prefix, value],
        );
      }
    }
    await client.query('COMMIT');
  } catch (err) {
    await client.query('ROLLBACK').catch(() => {});
    throw err;
  } finally {
    client.release();
  }
}

module.exports = { createPgStore };
