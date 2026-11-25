/**
 * Presentation mappings shared by the kit. These map a word or a tone to a design-system class; they
 * hold no thresholds. Bands (Under, Healthy, At capacity, Over, Critical) are computed by the backend
 * from the configurable rules (CalcService) and arrive as a tone, so no band limit lives in the UI
 * (design.md decision 5).
 */

/** Badge and status tones of bundle.css; '' is neutral. */
export const TONES = ['ok', 'info', 'brand', 'accent', 'warn', 'danger'];

/** Utilization band tones, lowest to highest: Under, Healthy, At capacity, Over, Critical. */
export const BAND_TONES = ['info', 'ok', 'accent', 'warn', 'danger'];

/**
 * The tone of a value in a band list from the backend (`[{min, tone}]`, highest `min` first, the last
 * without `min`): the first band the value reaches. The limits come from CalcService
 * (`core.thresholds`, `work.thresholds`); '' for no value or no bands.
 */
export function toneFor(value, bands) {
  if (value == null || !Array.isArray(bands)) {
    return '';
  }

  const band = bands.find((b) => b.min == null || Number(value) >= b.min);

  return band?.tone ?? '';
}

/** Heatmap cell class for a band tone (`u0` is leave or no data). */
export function heatClass(tone) {
  const i = BAND_TONES.indexOf(tone);
  return i < 0 ? 'u0' : `u${i + 1}`;
}

/** Jira status to badge tone (SprintTaskTable / Badge README). */
const STATUS_TONES = { 'to do': '', 'in progress': 'info', 'in review': 'brand', done: 'ok' };

export function statusTone(status) {
  return STATUS_TONES[String(status ?? '').toLowerCase()] ?? '';
}

/** The 11 DO culture values in their fixed display order (CultureValues README). */
export const CULTURE_VALUES = [
  'Outcome Ownership',
  'Moral & Ethics',
  'Decision Making',
  'Problem Solving',
  'Risk Appetite',
  'Adaptability',
  'Agility in Execution',
  'Collaboration',
  'Resilience',
  'Resourcefulness',
  'Discipline',
];
