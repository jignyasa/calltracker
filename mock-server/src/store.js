'use strict';

const fs = require('node:fs');
const path = require('node:path');
const { seedData } = require('./seed');

/**
 * In-memory store with optional JSON-file persistence (atomic temp-file + rename, debounced).
 * Pass `file: null` to keep everything in memory (tests).
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

  return {
    get state() {
      return state;
    },
    findByPhone(phone) {
      const contact = state.contacts.find((c) => c.phone === phone);
      if (contact) return { type: 'CONTACT', ...contact };
      const lead = state.leads.find((l) => l.phone === phone);
      if (lead) return { type: 'LEAD', ...lead };
      return null;
    },
    leadExists: (id) => state.leads.some((l) => l.id === id),
    contactExists: (id) => state.contacts.some((c) => c.id === id),
    addLead(lead) {
      state.leads.push(lead);
      persist();
      return lead;
    },
    getActivity: (clientId) => state.activities[clientId] ?? null,
    putActivity(activity) {
      state.activities[activity.clientId] = activity;
      persist();
      return activity;
    },
    getIdempotent: (key) => state.idempotency[key] ?? null,
    putIdempotent(key, record) {
      state.idempotency[key] = record;
      persist();
    },
    nextId(prefix) {
      state.counters[prefix] = (state.counters[prefix] ?? 0) + 1;
      return `${prefix}_${String(state.counters[prefix]).padStart(5, '0')}`;
    },
    reset() {
      state = emptyState(seed);
      persist();
    },
    flushNow,
  };
}

function emptyState(seed) {
  const base = { contacts: [], leads: [], activities: {}, idempotency: {}, counters: {} };
  return seed ? { ...base, ...seedData() } : base;
}

module.exports = { createStore };
