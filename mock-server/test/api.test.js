'use strict';

const { test, describe, before, after, beforeEach } = require('node:test');
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const { createApp, maskUrl } = require('../src/app');
const { createStore } = require('../src/store');
const { createPgStore } = require('../src/pgStore');
const { createFaults } = require('../src/faults');
const { normalizePhone } = require('../src/phone');

const AUTH = { Authorization: 'Bearer test-token' };

async function listen(app) {
  const server = await new Promise((resolve) => {
    const s = app.listen(0, '127.0.0.1', () => resolve(s));
  });
  return { server, base: `http://127.0.0.1:${server.address().port}` };
}

function activity(overrides = {}) {
  const startedAt = Date.now() - 120_000;
  return {
    clientId: crypto.randomUUID(),
    phone: '+919876543210',
    callType: 'OUTGOING',
    startedAt,
    endedAt: startedAt + 60_000,
    durationSec: 60,
    contactId: 'con_00001',
    notes: 'Discussed pricing',
    outcome: 'INTERESTED',
    updatedAt: Date.now(),
    ...overrides,
  };
}

describe('phone normalization', () => {
  test('Indian formats normalize to E.164', () => {
    for (const input of ['9876543210', '09876543210', '+91 98765 43210', '919876543210', '0091-9876543210']) {
      assert.equal(normalizePhone(input), '+919876543210', input);
    }
    assert.equal(normalizePhone('abc'), null);
  });
});

/** The API contract, run against each store. Postgres only when TEST_DATABASE_URL is set (e.g. a Neon test branch). */
const stores = [['memory store', async () => createStore({ file: null })]];
if (process.env.TEST_DATABASE_URL) {
  stores.push(['Postgres store', async () => createPgStore({ connectionString: process.env.TEST_DATABASE_URL })]);
}

for (const [storeName, makeStore] of stores) {
  describe(`API on the ${storeName}`, () => {
    let server;
    let base;
    let store;
    let faults;

    before(async () => {
      store = await makeStore();
      faults = createFaults();
      ({ server, base } = await listen(createApp({ store, faults, apiToken: 'test-token', log: false })));
    });

    after(async () => {
      server.close();
      await store.close();
    });

    beforeEach(async () => {
      await store.reset();
      faults.update({ latencyMs: 0, failureRate: 0, itemFailureRate: 0, forceStatus: null, forceCount: 0 });
    });

    async function call(method, path, { body, headers = {} } = {}) {
      const res = await fetch(base + path, {
        method,
        headers: { 'Content-Type': 'application/json', ...AUTH, ...headers },
        body: body === undefined ? undefined : JSON.stringify(body),
      });
      return { status: res.status, headers: res.headers, json: await res.json() };
    }

    describe('auth', () => {
      test('rejects missing bearer token', async () => {
        const res = await fetch(`${base}/api/v1/contacts/lookup?phone=9876543210`);
        assert.equal(res.status, 401);
      });
    });

    describe('lookup', () => {
      test('finds a seeded contact from a local-format number', async () => {
        const { status, json } = await call('GET', '/api/v1/contacts/lookup?phone=098765%2043210');
        assert.equal(status, 200);
        assert.equal(json.match.type, 'CONTACT');
        assert.equal(json.match.id, 'con_00001');
      });

      test('returns match:null for unknown numbers', async () => {
        const { status, json } = await call('GET', '/api/v1/contacts/lookup?phone=9000000001');
        assert.equal(status, 200);
        assert.equal(json.match, null);
      });
    });

    describe('create lead', () => {
      test('requires an Idempotency-Key', async () => {
        const { status } = await call('POST', '/api/v1/leads', { body: { name: 'X', phone: '9000000002' } });
        assert.equal(status, 400);
      });

      test('replaying the same key returns the same lead without creating a duplicate', async () => {
        const key = crypto.randomUUID();
        const body = { name: 'Kiran Desai', phone: '9000000003', company: 'Desai & Co' };
        const first = await call('POST', '/api/v1/leads', { body, headers: { 'Idempotency-Key': key } });
        const second = await call('POST', '/api/v1/leads', { body, headers: { 'Idempotency-Key': key } });
        assert.equal(first.status, 201);
        assert.equal(second.status, 201);
        assert.equal(second.headers.get('idempotent-replayed'), 'true');
        assert.equal(second.json.lead.id, first.json.lead.id);
        assert.equal((await store.snapshot()).leads.filter((l) => l.phone === '+919000000003').length, 1);
      });

      test('the same request sent twice at once creates one lead and replays it', async () => {
        const key = crypto.randomUUID();
        const body = { name: 'Asha Iyer', phone: '9000000005' };
        const both = await Promise.all([
          call('POST', '/api/v1/leads', { body, headers: { 'Idempotency-Key': key } }),
          call('POST', '/api/v1/leads', { body, headers: { 'Idempotency-Key': key } }),
        ]);
        assert.deepEqual(both.map((r) => r.status), [201, 201]);
        assert.equal(both[0].json.lead.id, both[1].json.lead.id);
        assert.equal((await store.snapshot()).leads.filter((l) => l.phone === '+919000000005').length, 1);
      });

      test('two keys racing for one number: one lead, the other gets 409 with it', async () => {
        const body = { name: 'Raced', phone: '9000000006' };
        const both = await Promise.all([
          call('POST', '/api/v1/leads', { body, headers: { 'Idempotency-Key': crypto.randomUUID() } }),
          call('POST', '/api/v1/leads', { body, headers: { 'Idempotency-Key': crypto.randomUUID() } }),
        ]);
        assert.deepEqual(both.map((r) => r.status).sort(), [201, 409]);
        const created = both.find((r) => r.status === 201).json.lead;
        assert.equal(both.find((r) => r.status === 409).json.match.id, created.id);
        assert.equal((await store.snapshot()).leads.filter((l) => l.phone === '+919000000006').length, 1);
      });

      test('reusing a key with a different body is rejected', async () => {
        const key = crypto.randomUUID();
        await call('POST', '/api/v1/leads', { body: { name: 'A', phone: '9000000004' }, headers: { 'Idempotency-Key': key } });
        const res = await call('POST', '/api/v1/leads', { body: { name: 'B', phone: '9000000004' }, headers: { 'Idempotency-Key': key } });
        assert.equal(res.status, 422);
      });

      test('existing phone returns 409 with the match', async () => {
        const res = await call('POST', '/api/v1/leads', {
          body: { name: 'Dup', phone: '9876543210' },
          headers: { 'Idempotency-Key': crypto.randomUUID() },
        });
        assert.equal(res.status, 409);
        assert.equal(res.json.match.id, 'con_00001');
      });
    });

    describe('activity batch', () => {
      test('mixed batch returns per-item results', async () => {
        const good = activity();
        const bad = activity({ callType: 'VIDEO' });
        const { status, json } = await call('POST', '/api/v1/activities/batch', { body: { items: [good, bad] } });
        assert.equal(status, 200);
        assert.equal(json.results[0].status, 'CREATED');
        assert.equal(json.results[1].status, 'INVALID');
        assert.equal(json.results[1].clientId, bad.clientId);
      });

      test('re-sending the same item is a DUPLICATE with the same serverId', async () => {
        const item = activity();
        const first = await call('POST', '/api/v1/activities/batch', { body: { items: [item] } });
        const second = await call('POST', '/api/v1/activities/batch', { body: { items: [item] } });
        assert.equal(second.json.results[0].status, 'DUPLICATE');
        assert.equal(second.json.results[0].serverId, first.json.results[0].serverId);
        assert.equal(Object.keys((await store.snapshot()).activities).length, 1);
      });

      test('the same item in two batches at once is stored once', async () => {
        const item = activity();
        const both = await Promise.all([
          call('POST', '/api/v1/activities/batch', { body: { items: [item] } }),
          call('POST', '/api/v1/activities/batch', { body: { items: [item] } }),
        ]);
        assert.deepEqual(both.map((r) => r.json.results[0].status).sort(), ['CREATED', 'DUPLICATE']);
        assert.equal(both[0].json.results[0].serverId, both[1].json.results[0].serverId);
        assert.equal(Object.keys((await store.snapshot()).activities).length, 1);
      });

      test('newer edit updates, stale edit conflicts', async () => {
        const item = activity();
        await call('POST', '/api/v1/activities/batch', { body: { items: [item] } });

        const newer = { ...item, notes: 'Follow up next week', updatedAt: item.updatedAt + 1000 };
        const updated = await call('POST', '/api/v1/activities/batch', { body: { items: [newer] } });
        assert.equal(updated.json.results[0].status, 'UPDATED');
        assert.equal(updated.json.results[0].version, 2);

        const stale = { ...item, notes: 'old text edited offline', updatedAt: item.updatedAt + 500 };
        const conflict = await call('POST', '/api/v1/activities/batch', { body: { items: [stale] } });
        assert.equal(conflict.json.results[0].status, 'CONFLICT');
        assert.equal(conflict.json.results[0].server.notes, 'Follow up next week');
      });

      test('unknown leadId is INVALID (permanent)', async () => {
        const { json } = await call('POST', '/api/v1/activities/batch', {
          body: { items: [activity({ contactId: null, leadId: 'lead_99999' })] },
        });
        assert.equal(json.results[0].status, 'INVALID');
      });

      test('rejects oversized batches', async () => {
        const items = Array.from({ length: 101 }, () => activity());
        const { status } = await call('POST', '/api/v1/activities/batch', { body: { items } });
        assert.equal(status, 413);
      });
    });

    describe('fault injection', () => {
      test('X-Mock-Fault header forces a status', async () => {
        const { status, headers } = await call('GET', '/api/v1/contacts/lookup?phone=9876543210', {
          headers: { 'X-Mock-Fault': '503' },
        });
        assert.equal(status, 503);
        assert.equal(headers.get('retry-after'), '5');
      });

      test('forceStatus applies to the next N requests only', async () => {
        await fetch(`${base}/__admin/faults`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ forceStatus: 500, forceCount: 1 }),
        });
        const first = await call('GET', '/api/v1/contacts/lookup?phone=9876543210');
        const second = await call('GET', '/api/v1/contacts/lookup?phone=9876543210');
        assert.equal(first.status, 500);
        assert.equal(second.status, 200);
      });

      test('itemFailureRate=1 makes every item a transient ERROR', async () => {
        faults.update({ itemFailureRate: 1 });
        const { json } = await call('POST', '/api/v1/activities/batch', { body: { items: [activity()] } });
        assert.equal(json.results[0].status, 'ERROR');
      });
    });
  });
}

describe('hosted mode (DATABASE_URL set): admin locked, faults off, rate limit', () => {
  const instances = [];
  async function start(options) {
    const instance = await listen(createApp({ store: createStore({ file: null }), apiToken: 'test-token', log: false, ...options }));
    instances.push(instance);
    return instance.base;
  }
  after(() => instances.forEach(({ server }) => server.close()));

  test('without ADMIN_TOKEN the admin routes do not exist', async () => {
    const base = await start({ adminOpen: false });
    assert.equal((await fetch(`${base}/__admin/state`)).status, 404);
    assert.equal((await fetch(`${base}/__admin/reset`, { method: 'POST' })).status, 404);
  });

  test('with ADMIN_TOKEN they need it, as a header or ?key=', async () => {
    const base = await start({ adminOpen: false, adminToken: 'secret-admin' });
    assert.equal((await fetch(`${base}/__admin/state`)).status, 401);
    assert.equal((await fetch(`${base}/__admin/state?key=wrong`)).status, 401);
    assert.equal((await fetch(`${base}/__admin/state?key=secret-admin`)).status, 200);
    const viaHeader = await fetch(`${base}/__admin/state`, { headers: { Authorization: 'Bearer secret-admin' } });
    assert.equal(viaHeader.status, 200);
    assert.equal((await viaHeader.json()).contacts.length, 3);
  });

  test('with faults off the X-Mock-Fault header is ignored and /__admin/faults is gone', async () => {
    const base = await start({ faults: null, adminToken: 'secret-admin' });
    const res = await fetch(`${base}/api/v1/contacts/lookup?phone=9876543210`, { headers: { ...AUTH, 'X-Mock-Fault': '503' } });
    assert.equal(res.status, 200);
    const admin = await fetch(`${base}/__admin/faults?key=secret-admin`, { method: 'POST' });
    assert.equal(admin.status, 404);
  });

  test('the rate limit answers 429 with Retry-After', async () => {
    const base = await start({ rateLimit: { windowMs: 60_000, max: 2 } });
    const statuses = [];
    for (let i = 0; i < 3; i++) {
      const res = await fetch(`${base}/api/v1/contacts/lookup?phone=9876543210`, { headers: AUTH });
      statuses.push(res.status);
      if (res.status === 429) assert.ok(Number(res.headers.get('retry-after')) > 0);
    }
    assert.deepEqual(statuses, [200, 200, 429]);
  });

  test('request logs mask the phone number and the admin key', () => {
    assert.equal(maskUrl('/__admin/state?key=secret-admin'), '/__admin/state?key=***');
    assert.equal(maskUrl('/api/v1/contacts/lookup?phone=%2B919876543210'), '/api/v1/contacts/lookup?phone=*********3210');
    assert.equal(maskUrl('/api/v1/contacts/lookup?x=1&phone=98765'), '/api/v1/contacts/lookup?x=1&phone=*8765');
    assert.equal(maskUrl('/api/v1/activities/batch'), '/api/v1/activities/batch');
  });
});
