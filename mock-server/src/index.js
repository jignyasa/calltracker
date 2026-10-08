'use strict';

const path = require('node:path');
const { createApp } = require('./app');
const { createStore } = require('./store');
const { createFaults } = require('./faults');

const port = Number(process.env.PORT ?? 3000);
const dataFile = process.env.DATA_FILE ?? path.join(__dirname, '..', 'data', 'db.json');

const store = createStore({ file: dataFile === 'memory' ? null : dataFile });
const faults = createFaults({
  latencyMs: Number(process.env.LATENCY_MS ?? 0),
  failureRate: Number(process.env.FAILURE_RATE ?? 0),
  itemFailureRate: Number(process.env.ITEM_FAILURE_RATE ?? 0),
});
const app = createApp({ store, faults, apiToken: process.env.API_TOKEN ?? 'dev-token' });

const server = app.listen(port, '0.0.0.0', () => {
  console.log(`Mock CRM listening on http://0.0.0.0:${port} (emulator: http://10.0.2.2:${port})`);
  console.log(`Data file: ${dataFile}; faults: ${JSON.stringify(faults.config)}`);
});

for (const signal of ['SIGINT', 'SIGTERM']) {
  process.on(signal, () => {
    store.flushNow();
    server.close(() => process.exit(0));
  });
}
