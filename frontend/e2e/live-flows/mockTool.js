import { readFileSync, existsSync } from 'node:fs';
import { createServer } from 'node:http';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

/**
 * A mock live tool for the Playwright flows (task 26.3, change pulse-v2-live-connectors). It serves the
 * recorded fixtures of `backend/src/test/resources/live-fixtures/<folder>/` with the same `routes.json`
 * rules as the backend's `LiveMockServer` (docs/live-connectors.md section 2), so the backend under test
 * talks to it exactly as it would to the tool:
 *  - match on method (GET), exact path and every listed query parameter ("*" = present)
 *  - `file` or inline `body`, `status` (200), `headers`; `{{baseUrl}}` is replaced by the mock's URL
 *  - `errors.<status>` is the recorded answer used when a fault asks for that status
 *
 * It listens on 127.0.0.1 with a random port, so the backend needs `PULSE_LIVE_ALLOW_LOOPBACK=true`.
 * Faults and holds let a flow script a 401 or keep a sync running.
 */

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '../../../backend/src/test/resources/live-fixtures');

/** Reads and checks `<folder>/routes.json`; throws naming the missing file or bad route. */
export function loadFixtures(folder) {
  const dir = join(ROOT, folder);
  const routesFile = join(dir, 'routes.json');

  if (!existsSync(routesFile)) {
    throw new Error(`No routes.json in live-fixtures/${folder}`);
  }

  const spec = JSON.parse(readFileSync(routesFile, 'utf8'));
  const read = (file) => {
    const path = join(dir, file);

    if (!existsSync(path)) {
      throw new Error(`live-fixtures/${folder}: missing file ${file}`);
    }

    return readFileSync(path, 'utf8');
  };

  const routes = (spec.routes ?? []).map((r) => {
    if (!r.path?.startsWith('/')) {
      throw new Error(`live-fixtures/${folder}: route path must start with "/": ${r.path}`);
    }

    return { ...r, method: r.method ?? 'GET', text: r.file ? read(r.file) : JSON.stringify(r.body ?? {}) };
  });

  const errors = Object.fromEntries(
    Object.entries(spec.errors ?? {}).map(([status, e]) => [status, { ...e, text: e.file ? read(e.file) : JSON.stringify(e.body ?? {}) }]),
  );

  return { routes, errors };
}

/**
 * Starts the mock. Returns `{ baseUrl, requests, fail(status, { path, times }), hold(path), reset(), close() }`.
 *  - `fail(401)` answers the recorded 401 to every request until `reset()`; `times` limits it, `path`
 *    limits it to one path.
 *  - `hold('/rest/api/2/search')` parks matching requests until the returned `release()` is called.
 *  - `requests` lists `{ method, path, query, authorization }` since the last `reset()`; never print the
 *    authorization, assert on it.
 */
export async function startMockTool(folder) {
  const { routes, errors } = loadFixtures(folder);
  const requests = [];
  let faults = [];
  let holds = [];
  let baseUrl = '';

  const fill = (text) => text.replaceAll('{{baseUrl}}', baseUrl);

  const send = (res, status, headers, text) => {
    const out = { 'Content-Type': 'application/json', ...Object.fromEntries(Object.entries(headers ?? {}).map(([k, v]) => [k, fill(String(v))])) };

    res.writeHead(status, out);
    res.end(fill(text));
  };

  const server = createServer(async (req, res) => {
    const url = new URL(req.url, baseUrl);
    const query = Object.fromEntries(url.searchParams);

    requests.push({ method: req.method, path: url.pathname, query, authorization: req.headers.authorization ?? null });

    const fault = faults.find((f) => (!f.path || f.path === url.pathname) && f.times !== 0);

    if (fault) {
      fault.times -= 1;

      const e = errors[String(fault.status)];

      return send(res, fault.status, e?.headers, e?.text ?? JSON.stringify({ errorMessages: [`Mock ${fault.status}`], errors: {} }));
    }

    const hold = holds.find((h) => h.path === url.pathname);

    if (hold) {
      await hold.released;
    }

    const route = routes.find(
      (r) =>
        r.method === req.method &&
        r.path === url.pathname &&
        Object.entries(r.query ?? {}).every(([k, v]) => (v === '*' ? k in query : query[k] === v)),
    );

    if (!route) {
      return send(res, 404, {}, JSON.stringify({ errorMessages: [`No fixture for ${req.method} ${url.pathname}`], errors: {} }));
    }

    return send(res, route.status ?? 200, route.headers, route.text);
  });

  await new Promise((ok) => server.listen(0, '127.0.0.1', ok));
  baseUrl = `http://127.0.0.1:${server.address().port}`;

  return {
    baseUrl,
    requests,
    fail(status, { path = null, times = -1 } = {}) {
      faults.push({ status, path, times });
    },
    hold(path) {
      let release;
      const released = new Promise((ok) => {
        release = ok;
      });

      const entry = { path, released, release };

      holds.push(entry);

      return () => {
        holds = holds.filter((h) => h !== entry);
        release();
      };
    },
    reset() {
      faults = [];
      requests.length = 0;
    },
    close() {
      holds.forEach((h) => h.release());
      holds = [];
      server.closeAllConnections();

      return new Promise((ok) => server.close(ok));
    },
  };
}
