'use strict';

const crypto = require('node:crypto');
const express = require('express');
const { normalizePhone } = require('./phone');

const CALL_TYPES = ['INCOMING', 'OUTGOING', 'MISSED', 'REJECTED'];
const OUTCOMES = ['INTERESTED', 'NOT_INTERESTED', 'CALL_BACK', 'NO_ANSWER', 'WRONG_NUMBER', 'CONVERTED', 'OTHER'];
const MAX_BATCH = 100;
const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

function createApp({ store, faults, apiToken = 'dev-token', log = true }) {
  const app = express();
  app.disable('x-powered-by');
  app.use(express.json({ limit: '1mb' }));

  if (log) {
    app.use((req, res, next) => {
      const started = Date.now();
      res.on('finish', () => console.log(`${req.method} ${req.originalUrl} -> ${res.statusCode} (${Date.now() - started}ms)`));
      next();
    });
  }

  app.get('/health', (req, res) => res.json({ ok: true }));

  // ---- Admin (no auth, no faults): inspect state and control fault injection ----
  app.get('/__admin/state', (req, res) => res.json(store.state));
  app.get('/__admin/faults', (req, res) => res.json(faults.config));
  app.post('/__admin/faults', (req, res) => res.json(faults.update(req.body ?? {})));
  app.post('/__admin/reset', (req, res) => {
    store.reset();
    res.json({ ok: true });
  });

  // ---- CRM API ----
  const api = express.Router();
  api.use(faults.middleware);
  api.use((req, res, next) => {
    if (req.get('Authorization') !== `Bearer ${apiToken}`) {
      return res.status(401).json({ error: 'UNAUTHORIZED' });
    }
    next();
  });

  api.get('/contacts/lookup', (req, res) => {
    const phone = normalizePhone(req.query.phone);
    if (!phone) return res.status(400).json({ error: 'INVALID_PHONE' });
    res.json({ phone, match: store.findByPhone(phone) });
  });

  api.post('/leads', (req, res) => {
    const key = req.get('Idempotency-Key');
    if (!key || !UUID_RE.test(key)) return res.status(400).json({ error: 'IDEMPOTENCY_KEY_REQUIRED' });

    const body = req.body ?? {};
    const requestHash = hash(body);
    const previous = store.getIdempotent(key);
    if (previous) {
      if (previous.requestHash !== requestHash) return res.status(422).json({ error: 'IDEMPOTENCY_KEY_REUSED' });
      return res.status(previous.status).set('Idempotent-Replayed', 'true').json(previous.body);
    }

    const errors = [];
    const phone = normalizePhone(body.phone);
    if (!phone) errors.push('phone is invalid');
    if (typeof body.name !== 'string' || !body.name.trim()) errors.push('name is required');
    if (body.company != null && typeof body.company !== 'string') errors.push('company must be a string');
    if (errors.length) return res.status(400).json({ error: 'VALIDATION_FAILED', details: errors });

    const existing = store.findByPhone(phone);
    if (existing) {
      // Not cached under the key: the client may legitimately retry after resolving the conflict.
      return res.status(409).json({ error: 'PHONE_EXISTS', match: existing });
    }

    const lead = store.addLead({
      id: store.nextId('lead'),
      name: body.name.trim(),
      company: body.company?.trim() || null,
      phone,
      source: 'CALL',
      createdAt: Date.now(),
    });
    const response = { lead: { type: 'LEAD', ...lead } };
    store.putIdempotent(key, { requestHash, status: 201, body: response, at: Date.now() });
    res.status(201).json(response);
  });

  api.post('/activities/batch', (req, res) => {
    const items = req.body?.items;
    if (!Array.isArray(items) || items.length === 0) return res.status(400).json({ error: 'ITEMS_REQUIRED' });
    if (items.length > MAX_BATCH) return res.status(413).json({ error: 'BATCH_TOO_LARGE', max: MAX_BATCH });

    const results = items.map((item) => processActivity(item));
    res.json({ results });
  });

  api.get('/activities', (req, res) => res.json({ items: Object.values(store.state.activities) }));

  function processActivity(raw) {
    const clientId = typeof raw?.clientId === 'string' ? raw.clientId : null;
    const { activity, errors } = validateActivity(raw);
    if (errors.length) return { clientId, status: 'INVALID', errors };

    if (faults.shouldFailItem()) return { clientId, status: 'ERROR', errors: ['transient item failure (injected)'] };

    const payloadHash = hash(activity);
    const existing = store.getActivity(activity.clientId);
    if (!existing) {
      const saved = store.putActivity({ ...activity, serverId: store.nextId('act'), version: 1, payloadHash, receivedAt: Date.now() });
      return { clientId, status: 'CREATED', serverId: saved.serverId, version: saved.version };
    }
    if (existing.payloadHash === payloadHash) {
      return { clientId, status: 'DUPLICATE', serverId: existing.serverId, version: existing.version };
    }
    if (activity.updatedAt > existing.updatedAt) {
      const saved = store.putActivity({ ...existing, ...activity, version: existing.version + 1, payloadHash, receivedAt: Date.now() });
      return { clientId, status: 'UPDATED', serverId: saved.serverId, version: saved.version };
    }
    const { payloadHash: _h, ...server } = existing;
    return { clientId, status: 'CONFLICT', serverId: existing.serverId, version: existing.version, server };
  }

  function validateActivity(raw) {
    const errors = [];
    if (!raw || typeof raw !== 'object') return { activity: null, errors: ['item must be an object'] };

    if (typeof raw.clientId !== 'string' || !UUID_RE.test(raw.clientId)) errors.push('clientId must be a UUID');
    const phone = normalizePhone(raw.phone);
    if (!phone) errors.push('phone is invalid');
    if (!CALL_TYPES.includes(raw.callType)) errors.push(`callType must be one of ${CALL_TYPES.join(', ')}`);
    if (!isEpochMs(raw.startedAt)) errors.push('startedAt must be epoch millis');
    if (!isEpochMs(raw.endedAt) || raw.endedAt < raw.startedAt) errors.push('endedAt must be epoch millis >= startedAt');
    if (!Number.isInteger(raw.durationSec) || raw.durationSec < 0) errors.push('durationSec must be a non-negative integer');
    if (!isEpochMs(raw.updatedAt)) errors.push('updatedAt must be epoch millis');
    if (raw.simSlot != null && !Number.isInteger(raw.simSlot)) errors.push('simSlot must be an integer');
    if (raw.outcome != null && !OUTCOMES.includes(raw.outcome)) errors.push(`outcome must be one of ${OUTCOMES.join(', ')}`);
    if (raw.followUpAt != null && !isEpochMs(raw.followUpAt)) errors.push('followUpAt must be epoch millis');
    for (const field of ['notes', 'nextAction', 'remarks']) {
      if (raw[field] != null && (typeof raw[field] !== 'string' || raw[field].length > 5000)) {
        errors.push(`${field} must be a string up to 5000 chars`);
      }
    }
    if (raw.leadId != null && !store.leadExists(raw.leadId)) errors.push('leadId does not exist');
    if (raw.contactId != null && !store.contactExists(raw.contactId)) errors.push('contactId does not exist');
    if (errors.length) return { activity: null, errors };

    return {
      errors,
      activity: {
        clientId: raw.clientId.toLowerCase(),
        phone,
        callType: raw.callType,
        startedAt: raw.startedAt,
        endedAt: raw.endedAt,
        durationSec: raw.durationSec,
        simSlot: raw.simSlot ?? null,
        leadId: raw.leadId ?? null,
        contactId: raw.contactId ?? null,
        notes: raw.notes ?? null,
        outcome: raw.outcome ?? null,
        followUpAt: raw.followUpAt ?? null,
        nextAction: raw.nextAction ?? null,
        remarks: raw.remarks ?? null,
        updatedAt: raw.updatedAt,
      },
    };
  }

  app.use('/api/v1', api);

  app.use((req, res) => res.status(404).json({ error: 'NOT_FOUND', path: req.path }));
  // eslint-disable-next-line no-unused-vars
  app.use((err, req, res, next) => {
    if (err.type === 'entity.parse.failed') return res.status(400).json({ error: 'MALFORMED_JSON' });
    if (err.type === 'entity.too.large') return res.status(413).json({ error: 'PAYLOAD_TOO_LARGE' });
    console.error(err);
    res.status(500).json({ error: 'INTERNAL' });
  });

  return app;
}

function isEpochMs(v) {
  return Number.isInteger(v) && v > 946684800000; // after 2000-01-01
}

function hash(value) {
  return crypto.createHash('sha256').update(stableStringify(value)).digest('hex');
}

function stableStringify(value) {
  if (Array.isArray(value)) return `[${value.map(stableStringify).join(',')}]`;
  if (value && typeof value === 'object') {
    return `{${Object.keys(value).sort().map((k) => `${JSON.stringify(k)}:${stableStringify(value[k])}`).join(',')}}`;
  }
  return JSON.stringify(value);
}

module.exports = { createApp, CALL_TYPES, OUTCOMES, MAX_BATCH };
