'use strict';

const crypto = require('node:crypto');
const express = require('express');
const { normalizePhone } = require('./phone');

const CALL_TYPES = ['INCOMING', 'OUTGOING', 'MISSED', 'REJECTED'];
const OUTCOMES = ['INTERESTED', 'NOT_INTERESTED', 'CALL_BACK', 'NO_ANSWER', 'WRONG_NUMBER', 'CONVERTED', 'OTHER'];
const MAX_BATCH = 100;
const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/**
 * @param store      memory/JSON store (`store.js`) or Postgres store (`pgStore.js`); same async interface
 * @param faults     fault injection (`faults.js`), or null to switch it off (hosted deployments)
 * @param adminToken when set, `/__admin/*` needs `Authorization: Bearer <token>` or `?key=<token>`
 * @param adminOpen  without a token: true = admin routes open (local dev), false = they don't exist (404)
 * @param rateLimit  `{ windowMs, max }` per client IP on /api, or null
 */
function createApp({
  store,
  faults = null,
  apiToken = 'dev-token',
  adminToken = null,
  adminOpen = true,
  rateLimit = null,
  log = true,
}) {
  const app = express();
  app.disable('x-powered-by');
  app.set('trust proxy', 1); // behind one reverse proxy (Render): req.ip is the client's address
  app.use(express.json({ limit: '1mb' }));

  if (log) {
    app.use((req, res, next) => {
      const started = Date.now();
      res.on('finish', () => console.log(`${req.method} ${maskUrl(req.originalUrl)} -> ${res.statusCode} (${Date.now() - started}ms)`));
      next();
    });
  }

  app.get('/health', (req, res) => res.json({ ok: true }));

  // ---- Admin (no API token, no faults): inspect state and control fault injection ----
  const admin = express.Router();
  admin.use((req, res, next) => {
    if (adminToken) {
      const given = req.get('Authorization') === `Bearer ${adminToken}` || req.query.key === adminToken;
      return given ? next() : res.status(401).json({ error: 'UNAUTHORIZED' });
    }
    return adminOpen ? next() : res.status(404).json({ error: 'NOT_FOUND', path: req.path });
  });
  admin.get('/state', async (req, res) => res.json(await store.snapshot()));
  if (faults) {
    admin.get('/faults', (req, res) => res.json(faults.config));
    admin.post('/faults', (req, res) => res.json(faults.update(req.body ?? {})));
  }
  admin.post('/reset', async (req, res) => {
    await store.reset();
    res.json({ ok: true });
  });
  app.use('/__admin', admin);

  // ---- CRM API ----
  const api = express.Router();
  if (rateLimit) api.use(createRateLimiter(rateLimit));
  if (faults) api.use(faults.middleware);
  api.use((req, res, next) => {
    if (req.get('Authorization') !== `Bearer ${apiToken}`) {
      return res.status(401).json({ error: 'UNAUTHORIZED' });
    }
    next();
  });

  api.get('/contacts/lookup', async (req, res) => {
    const phone = normalizePhone(req.query.phone);
    if (!phone) return res.status(400).json({ error: 'INVALID_PHONE' });
    res.json({ phone, match: await store.findByPhone(phone) });
  });

  api.post('/leads', async (req, res) => {
    const key = req.get('Idempotency-Key');
    if (!key || !UUID_RE.test(key)) return res.status(400).json({ error: 'IDEMPOTENCY_KEY_REQUIRED' });

    const body = req.body ?? {};
    const requestHash = hash(body);
    const phone = normalizePhone(body.phone);
    // One request per key at a time: a retry that arrives while the first is still running waits, then replays.
    const outcome = await store
      .transaction(`lead:${key}`, async (tx) => {
        const previous = await tx.getIdempotent(key);
        if (previous) {
          if (previous.requestHash !== requestHash) return { status: 422, body: { error: 'IDEMPOTENCY_KEY_REUSED' } };
          return { status: previous.status, body: previous.body, replayed: true };
        }

        const errors = [];
        if (!phone) errors.push('phone is invalid');
        if (typeof body.name !== 'string' || !body.name.trim()) errors.push('name is required');
        if (body.company != null && typeof body.company !== 'string') errors.push('company must be a string');
        if (errors.length) return { status: 400, body: { error: 'VALIDATION_FAILED', details: errors } };

        // Not cached under the key: the client may legitimately retry after resolving the conflict.
        if (await tx.findByPhone(phone)) return { phoneTaken: true };

        const lead = await tx.addLead({
          id: await tx.nextId('lead'),
          name: body.name.trim(),
          company: body.company?.trim() || null,
          phone,
          source: 'CALL',
          createdAt: Date.now(),
        });
        const response = { lead: { type: 'LEAD', ...lead } };
        await tx.putIdempotent(key, { requestHash, status: 201, body: response, at: Date.now() });
        return { status: 201, body: response };
      })
      .catch((err) => {
        // Another key created a lead for this number a moment ago: the unique index caught it (transaction rolled back).
        if (err.code === 'PHONE_EXISTS') return { phoneTaken: true };
        throw err;
      });

    if (outcome.phoneTaken) {
      return res.status(409).json({ error: 'PHONE_EXISTS', match: await store.findByPhone(phone) });
    }
    if (outcome.replayed) res.set('Idempotent-Replayed', 'true');
    res.status(outcome.status).json(outcome.body);
  });

  api.post('/activities/batch', async (req, res) => {
    const items = req.body?.items;
    if (!Array.isArray(items) || items.length === 0) return res.status(400).json({ error: 'ITEMS_REQUIRED' });
    if (items.length > MAX_BATCH) return res.status(413).json({ error: 'BATCH_TOO_LARGE', max: MAX_BATCH });

    // One item after another, each in its own transaction: one failing item never fails the others.
    const results = [];
    for (const item of items) results.push(await processActivity(item));
    res.json({ results });
  });

  api.get('/activities', async (req, res) => res.json({ items: Object.values((await store.snapshot()).activities) }));

  async function processActivity(raw) {
    const clientId = typeof raw?.clientId === 'string' ? raw.clientId : null;
    const { activity, errors } = await validateActivity(raw);
    if (errors.length) return { clientId, status: 'INVALID', errors };

    if (faults?.shouldFailItem()) return { clientId, status: 'ERROR', errors: ['transient item failure (injected)'] };

    const payloadHash = hash(activity);
    try {
      return await store.transaction(`act:${activity.clientId}`, async (tx) => {
        const existing = await tx.getActivity(activity.clientId);
        if (!existing) {
          const saved = await tx.putActivity({
            ...activity, serverId: await tx.nextId('act'), version: 1, payloadHash, receivedAt: Date.now(),
          });
          return { clientId, status: 'CREATED', serverId: saved.serverId, version: saved.version };
        }
        if (existing.payloadHash === payloadHash) {
          return { clientId, status: 'DUPLICATE', serverId: existing.serverId, version: existing.version };
        }
        if (activity.updatedAt > existing.updatedAt) {
          const saved = await tx.putActivity({
            ...existing, ...activity, version: existing.version + 1, payloadHash, receivedAt: Date.now(),
          });
          return { clientId, status: 'UPDATED', serverId: saved.serverId, version: saved.version };
        }
        const { payloadHash: _h, ...server } = existing;
        return { clientId, status: 'CONFLICT', serverId: existing.serverId, version: existing.version, server };
      });
    } catch (err) {
      console.error(`[activities] item failed: ${err.code ?? err.name}`);
      return { clientId, status: 'ERROR', errors: ['storage error, retry later'] };
    }
  }

  async function validateActivity(raw) {
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
    if (raw.leadId != null && !(await store.leadExists(raw.leadId))) errors.push('leadId does not exist');
    if (raw.contactId != null && !(await store.contactExists(raw.contactId))) errors.push('contactId does not exist');
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
    // Code/type only: a database error's message or detail can quote the values (phone numbers) involved.
    console.error(`[app] unhandled error on ${req.method} ${req.path}: ${err.code ?? err.name}`);
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

/** Hides the number in `?phone=` and the admin `?key=` so request logs never carry either. */
function maskUrl(url) {
  return url.replace(/([?&]key=)[^&]*/gi, '$1***').replace(/([?&]phone=)([^&]*)/gi, (_, prefix, value) => {
    let raw = value;
    try {
      raw = decodeURIComponent(value);
    } catch {
      // keep it encoded
    }
    return `${prefix}${raw.length > 4 ? '*'.repeat(raw.length - 4) + raw.slice(-4) : '****'}`;
  });
}

/** Fixed-window limit per client IP; answers 429 with Retry-After. Enough for a demo deployment, no extra dependency. */
function createRateLimiter({ windowMs, max }) {
  const hits = new Map();
  return (req, res, next) => {
    const now = Date.now();
    if (hits.size > 10_000) {
      for (const [ip, entry] of hits) if (now - entry.start >= windowMs) hits.delete(ip);
    }
    let entry = hits.get(req.ip);
    if (!entry || now - entry.start >= windowMs) {
      entry = { start: now, count: 0 };
      hits.set(req.ip, entry);
    }
    entry.count += 1;
    if (entry.count > max) {
      res.set('Retry-After', String(Math.ceil((entry.start + windowMs - now) / 1000)));
      return res.status(429).json({ error: 'RATE_LIMITED' });
    }
    next();
  };
}

module.exports = { createApp, maskUrl, CALL_TYPES, OUTCOMES, MAX_BATCH };
