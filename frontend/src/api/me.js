/** Session bootstrap: user, role, permissions and menu (spec: api-conventions, "Session and menu bootstrap"). */
export async function fetchMe() {
  const res = await fetch('/api/v1/me', { credentials: 'same-origin' });
  if (!res.ok) throw new Error(`GET /api/v1/me failed: ${res.status}`);
  return res.json();
}
