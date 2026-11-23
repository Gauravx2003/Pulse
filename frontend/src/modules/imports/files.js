// File transfer for the CSV pipeline: the shared JSON client can't send a CSV body or save a file, so these
// call fetch directly with the session cookie and, for writes, the CSRF token from GET /me.
import { session } from '@/shell/session.js';

/** POSTs a CSV file to /api/v1/imports/:type/check; resolves to the check result or throws an Error with the server message. */
export async function checkCsv(type, mode, file) {
  const res = await fetch(`/api/v1/imports/${type}/check?mode=${encodeURIComponent(mode)}&fileName=${encodeURIComponent(file.name)}`, {
    method: 'POST',
    credentials: 'same-origin',
    headers: { 'Content-Type': 'text/csv', 'X-CSRF-Token': session.me?.csrfToken ?? '', Accept: 'application/json' },
    body: await file.text(),
  });

  const body = await res.json().catch(() => ({}));

  if (!res.ok) {
    const err = new Error(body?.error?.message ?? `Check failed (${res.status})`);
    err.code = body?.error?.code;
    throw err;
  }

  return body;
}

/** Downloads a CSV from the API (template or export) and saves it under the server's file name. */
export async function download(path, fallbackName) {
  const res = await fetch(`/api/v1${path}`, { credentials: 'same-origin' });

  if (!res.ok) {
    const body = await res.json().catch(() => ({}));
    throw new Error(body?.error?.message ?? `Download failed (${res.status})`);
  }

  const name = /filename="([^"]+)"/.exec(res.headers.get('Content-Disposition') ?? '')?.[1] ?? fallbackName;
  const url = URL.createObjectURL(await res.blob());
  const a = document.createElement('a');

  a.href = url;
  a.download = name;
  document.body.append(a);
  a.click();
  a.remove();
  URL.revokeObjectURL(url);
}
