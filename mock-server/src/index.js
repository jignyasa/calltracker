'use strict';

const path = require('node:path');
const { createApp } = require('./app');
const { createStore } = require('./store');
const { createPgStore } = require('./pgStore');
const { createFaults } = require('./faults');

/*
 * Local (default): JSON file in data/, fault injection on, /__admin open.
 * Hosted (DATABASE_URL set, e.g. Render + Neon): Postgres, fault injection off unless ENABLE_FAULTS=true,
 * /__admin only with ADMIN_TOKEN (404 without it), rate limit on /api.
 */
async function main() {
  const port = Number(process.env.PORT ?? 3000);
  const databaseUrl = process.env.DATABASE_URL || null;
  const hosted = databaseUrl != null;
  const dataFile = process.env.DATA_FILE ?? path.join(__dirname, '..', 'data', 'db.json');

  const store = hosted
    ? await createPgStore({ connectionString: databaseUrl })
    : createStore({ file: dataFile === 'memory' ? null : dataFile });

  const faultsOn = process.env.ENABLE_FAULTS != null ? process.env.ENABLE_FAULTS === 'true' : !hosted;
  const faults = faultsOn
    ? createFaults({
      latencyMs: Number(process.env.LATENCY_MS ?? 0),
      failureRate: Number(process.env.FAILURE_RATE ?? 0),
      itemFailureRate: Number(process.env.ITEM_FAILURE_RATE ?? 0),
    })
    : null;

  const app = createApp({
    store,
    faults,
    apiToken: process.env.API_TOKEN ?? 'dev-token',
    adminToken: process.env.ADMIN_TOKEN || null,
    adminOpen: !hosted,
    rateLimit: hosted ? { windowMs: 60_000, max: Number(process.env.RATE_LIMIT_PER_MIN ?? 120) } : null,
  });

  const server = app.listen(port, '0.0.0.0', () => {
    console.log(`Mock CRM listening on http://0.0.0.0:${port} (emulator: http://10.0.2.2:${port})`);
    console.log(`Storage: ${hosted ? 'Postgres' : dataFile}; faults: ${faults ? JSON.stringify(faults.config) : 'off'}; ` +
      `admin: ${process.env.ADMIN_TOKEN ? 'token' : hosted ? 'off' : 'open'}`);
  });

  for (const signal of ['SIGINT', 'SIGTERM']) {
    process.on(signal, () => {
      server.close(async () => {
        await store.close();
        process.exit(0);
      });
    });
  }
}

main().catch((err) => {
  console.error(`[start] failed: ${err.code ?? err.name}: ${err.message}`);
  process.exit(1);
});
