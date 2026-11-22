/**
 * The one API client (design.md decision 9; spec platform/app-shell-ui, "Feedback patterns"). Every
 * screen calls the backend through it: same-origin cookie session, the CSRF header on writes, and one
 * place that reacts to 401, 403, 409 and 429 with the error contract of spec platform/api-conventions
 * ({ error: { code, message, requestId, field?, retryAfter?, currentVersion? } }).
 */
export const API_PREFIX = '/api/v1';

const WRITE = new Set(['POST', 'PUT', 'PATCH', 'DELETE']);

let csrfToken = null;

/** Handlers the shell installs (src/shell/apiHooks.js); each receives the ApiError. */
const hooks = { 401: null, 403: null, 409: null, 429: null };

export function setCsrfToken(token) {
  csrfToken = token || null;
}

export function onApiError(status, handler) {
  hooks[status] = handler;
}

export class ApiError extends Error {
  constructor(status, error) {
    super(error?.message || `Request failed (${status})`);
    this.name = 'ApiError';
    this.status = status;
    this.code = error?.code ?? 'unknown_error';
    this.field = error?.field ?? null;
    this.retryAfter = error?.retryAfter ?? null;
    this.currentVersion = error?.currentVersion ?? null;
    this.requestId = error?.requestId ?? null;
    this.details = error ?? {};
  }
}

/**
 * @param {string} method
 * @param {string} path     relative to /api/v1, e.g. '/me'
 * @param {object} [opts]
 * @param {object} [opts.body]   JSON body
 * @param {object} [opts.query]  query parameters; null and undefined are left out
 * @param {boolean} [opts.quiet] do not run the shell hooks (the caller shows the error itself)
 * @param {AbortSignal} [opts.signal]
 */
export async function request(method, path, opts = {}) {
  const headers = { Accept: 'application/json' };

  if (opts.body !== undefined) {
    headers['Content-Type'] = 'application/json';
  }

  if (WRITE.has(method) && csrfToken) {
    headers['X-CSRF-Token'] = csrfToken;
  }

  let res;

  try {
    res = await fetch(API_PREFIX + path + queryString(opts.query), {
      method,
      headers,
      credentials: 'same-origin',
      body: opts.body === undefined ? undefined : JSON.stringify(opts.body),
      signal: opts.signal,
    });
  } catch (e) {
    if (e?.name === 'AbortError') {
      throw e;
    }

    throw new ApiError(0, { code: 'network_error', message: 'Pulse could not be reached. Check your connection.' });
  }

  if (res.status === 204) {
    return null;
  }

  const data = await res.json().catch(() => null);

  if (res.ok) {
    return data;
  }

  const err = new ApiError(res.status, data?.error);

  if (!opts.quiet && hooks[res.status]) {
    hooks[res.status](err);
  }

  throw err;
}

function queryString(query) {
  if (!query) {
    return '';
  }

  const params = new URLSearchParams();

  Object.entries(query).forEach(([k, v]) => {
    if (v !== null && v !== undefined && v !== '') {
      params.append(k, String(v));
    }
  });

  const s = params.toString();

  return s ? `?${s}` : '';
}

export const api = {
  get: (path, opts) => request('GET', path, opts),
  post: (path, body, opts) => request('POST', path, { ...opts, body }),
  put: (path, body, opts) => request('PUT', path, { ...opts, body }),
  patch: (path, body, opts) => request('PATCH', path, { ...opts, body }),
  delete: (path, opts) => request('DELETE', path, opts),
};
