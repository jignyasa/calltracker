'use strict';

const { test, describe, before, after, beforeEach } = require('node:test');
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const { createApp } = require('../src/app');
const { createStore } = require('../src/store');
const { createFaults } = require('../src/faults');
const { normalizePhone } = require('../src/phone');

const AUTH = { Authorization: 'Bearer test-token' };
let server;
let base;
let store;
let faults;

before(async () => {
  store = createStore({ file: null });
  faults = createFaults();
  const app = createApp({ store, faults, apiToken: 'test-token', log: false });
  await new Promise((resolve) => {
    server = app.listen(0, '127.0.0.1', resolve);
  });
  base = `http://127.0.0.1:${server.address().port}`;
});

after(() => server.close());

beforeEach(() => {
  store.reset();
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
    assert.equal(store.state.leads.filter((l) => l.phone === '+919000000003').length, 1);
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
    assert.equal(Object.keys(store.state.activities).length, 1);
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
