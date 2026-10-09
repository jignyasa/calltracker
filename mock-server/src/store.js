'use strict';

const fs = require('node:fs');
const path = require('node:path');
const { seedData } = require('./seed');

/**
 * In-memory store with optional JSON-file persistence (atomic temp-file + rename, debounced).
 * Pass `file: null` to keep everything in memory (tests). Same async interface as the Postgres store
 * (`pgStore.js`), so the routes don't know which one they run on.
 */
function createStore({ file = null, seed = true } = {}) {
  let state = emptyState(seed);
  let flushTimer = null;

  if (file && fs.existsSync(file)) {
    try {
      state = { ...emptyState(false), ...JSON.parse(fs.readFileSync(file, 'utf8')) };
    } catch (err) {
      console.warn(`[store] could not read ${file}, starting fresh: ${err.message}`);
    }
  }

  function persist() {
    if (!file) return;
    clearTimeout(flushTimer);
    flushTimer = setTimeout(flushNow, 50);
  }

  function flushNow() {
    if (!file) return;
    clearTimeout(flushTimer);
    fs.mkdirSync(path.dirname(file), { recursive: true });
    const tmp = `${file}.tmp`;
    fs.writeFileSync(tmp, JSON.stringify(state, null, 2));
    fs.renameSync(tmp, file);
  }

  const ops = {
    async findByPhone(phone) {
      const contact = state.contacts.find((c) => c.phone === phone);
      if (contact) return { type: 'CONTACT', ...contact };
      const lead = state.leads.find((l) => l.phone === phone);
      if (lead) return { type: 'LEAD', ...lead };
      return null;
    },
    leadExists: async (id) => state.leads.some((l) => l.id === id),
    contactExists: async (id) => state.contacts.some((c) => c.id === id),
    async addLead(lead) {
      if (state.leads.some((l) => l.phone === lead.phone)) throw phoneExists();
      state.leads.push(lead);
      persist();
      return lead;
    },
    getActivity: async (clientId) => state.activities[clientId] ?? null,
    async putActivity(activity) {
      state.activities[activity.clientId] = activity;
      persist();
      return activity;
    },
    getIdempotent: async (key) => state.idempotency[key] ?? null,
    async putIdempotent(key, record) {
      state.idempotency[key] ??= record;
      persist();
    },
    async nextId(prefix) {
      state.counters[prefix] = (state.counters[prefix] ?? 0) + 1;
      persist();
      return `${prefix}_${String(state.counters[prefix]).padStart(5, '0')}`;
    },
  };

  const locks = new Map();

  return {
    ...ops,
    async snapshot() {
      return structuredClone(state);
    },
    async reset() {
      state = emptyState(seed);
      persist();
    },
    /** Runs `fn(tx)` with requests for the same `lockKey` one at a time (the Postgres store uses an advisory lock). */
    async transaction(lockKey, fn) {
      const previous = locks.get(lockKey) ?? Promise.resolve();
      let release;
      const current = new Promise((resolve) => {
        release = resolve;
      });
      const chained = previous.then(() => current);
      locks.set(lockKey, chained);
      await previous;
      try {
        return await fn(ops);
      } finally {
        release();
        if (locks.get(lockKey) === chained) locks.delete(lockKey);
      }
    },
    async close() {
      flushNow();
    },
    flushNow,
  };
}

function emptyState(seed) {
  const base = { contacts: [], leads: [], activities: {}, idempotency: {}, counters: {} };
  return seed ? { ...base, ...seedData() } : base;
}

/** Thrown by `addLead` when the phone already has a lead (the Postgres store maps its unique-index error to this). */
function phoneExists() {
  return Object.assign(new Error('phone already has a lead'), { code: 'PHONE_EXISTS' });
}

module.exports = { createStore, phoneExists };
