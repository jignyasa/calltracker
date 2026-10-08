'use strict';

/**
 * Fault injection so the app's retry / partial-failure / timeout paths can be exercised.
 *
 * Global config (env vars at start-up, or POST /__admin/faults at runtime):
 *   latencyMs        fixed delay added to every /api request
 *   failureRate      0..1 probability that an /api request returns 503
 *   itemFailureRate  0..1 probability that a single batch item returns ERROR (transient)
 *   forceStatus      HTTP status returned for the next `forceCount` /api requests
 *
 * Per request header `X-Mock-Fault`: 500 | 503 | 429 | timeout | drop
 */
function createFaults(initial = {}) {
  const config = {
    latencyMs: 0,
    failureRate: 0,
    itemFailureRate: 0,
    forceStatus: null,
    forceCount: 0,
    ...initial,
  };

  function middleware(req, res, next) {
    const run = () => {
      const header = req.get('X-Mock-Fault');
      if (header === 'drop') return req.socket.destroy();
      if (header === 'timeout') return; // never respond; client must time out
      if (header && /^\d{3}$/.test(header)) return fail(res, Number(header), 'forced by X-Mock-Fault');

      if (config.forceStatus && config.forceCount > 0) {
        config.forceCount -= 1;
        return fail(res, config.forceStatus, 'forced by admin config');
      }
      if (config.failureRate > 0 && Math.random() < config.failureRate) {
        return fail(res, 503, 'random failure');
      }
      next();
    };
    config.latencyMs > 0 ? setTimeout(run, config.latencyMs) : run();
  }

  function fail(res, status, reason) {
    if (status === 429 || status === 503) res.set('Retry-After', '5');
    res.status(status).json({ error: 'INJECTED_FAULT', status, reason });
  }

  return {
    config,
    middleware,
    shouldFailItem: () => config.itemFailureRate > 0 && Math.random() < config.itemFailureRate,
    update(patch) {
      for (const key of Object.keys(config)) {
        if (patch[key] !== undefined) config[key] = patch[key];
      }
      return config;
    },
  };
}

module.exports = { createFaults };
