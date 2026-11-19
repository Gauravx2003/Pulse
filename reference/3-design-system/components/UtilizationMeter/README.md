# UtilizationMeter

A horizontal bar of assigned effort against available capacity, with a fixed ink mark at 100%.

**Consumer provides:** `value` (percent), `max` (default 140), person or scope label. Fill color is derived from the band — never passed in.

| Band | Range | Fill token | Badge word |
|---|---|---|---|
| Under | < 60% | `info` | Under |
| Healthy | 60–85% | `ok` | Healthy |
| At capacity | 85–100% | `accent` | At capacity |
| Over | 100–120% | `warn` | Over |
| Critical | > 120% | `danger` | Critical |

- Track is `surface-sunken`, height `space-2`, corners `radius-sm`.
- Always print the percent to the right in `tabular`; the color is a secondary cue.
- The 100% mark is `ink` so it stays visible over any fill.
- Clamp the bar at `max`, but print the true value (e.g. 162%).
