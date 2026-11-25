// Presentation helpers for the Culture values screens. Every score and part comes from the server.

import { toneFor } from '@/kit/tones.js';

/** Badge tone of a 1–5 score, from the bands the server sends (`tones.score`, core.thresholds). */
export function scoreTone(score, bands) {
  return toneFor(score, bands);
}

export function fixed(value, digits = 1) {
  return value == null ? '—' : Number(value).toFixed(digits);
}

/** "≥ 5 → 5 · ≥ 4 → 4 …" for a parameter's four band thresholds. */
export function bandText(bands, higherIsBetter, unit = '') {
  if (!Array.isArray(bands) || bands.length !== 4) {
    return '';
  }

  const op = higherIsBetter ? '≥' : '≤';

  return bands.map((b, i) => `${op} ${b}${unit} → ${5 - i}`).join(' · ') + ' · else 1';
}

/** The override score choices: 1.00 to 5.00 in steps of 0.25. */
export const OVERRIDE_SCORES = Array.from({ length: 17 }, (_, i) => {
  const v = 1 + i * 0.25;

  return { value: String(v), label: v.toFixed(2) };
});
