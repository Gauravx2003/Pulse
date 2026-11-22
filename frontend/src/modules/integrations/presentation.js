/** Presentation of the Integrations screen: tile tones and labels. No rule lives here. */
export const STATE_TONES = { connected: 'ok', token_expiring: 'accent', sync_failed: 'danger', not_connected: '' };

export const ACTION_LABELS = { settings: 'Settings', fix: 'Fix', connect: 'Connect' };

export const SYNC_MODE_LABELS = {
  webhooks_hourly: 'Webhooks + hourly',
  hourly: 'Hourly poll only',
  nightly: 'Nightly only',
};

/** Jira boards offered by the board picker (reference 11.3). */
export const JIRA_BOARDS = ['PLAT', 'NET', 'UI'];

/** HTML input type for a catalogue field type; secrets are password inputs. */
export function inputType(fieldType) {
  return { secret: 'password', url: 'url', email: 'email', textarea: 'textarea' }[fieldType] ?? 'text';
}
