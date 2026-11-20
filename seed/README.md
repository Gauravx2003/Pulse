# Seed data

`seed/extracted/*.json` is the demo data of Pulse, extracted from the prototype
(`reference/2-prototype/src/app.js`) so seeded numbers match the prototype oracle.

```bash
node seed/extract.mjs          # rewrite seed/extracted/ (deterministic, safe to re-run)
node --test seed/extract.test.mjs
```

- Each file is a JSON array of rows. Keyed prototype maps are flattened, for example
  `ATT[person][date]` becomes `attendance.json` rows `{person, date, st, in, out}`.
- Rows keep the prototype's own keys (`ps`, `S43`, `8.2`, …); loaders use them as `external_id`.
- `culture_signals.json` holds the 12 culture parameters the prototype generates with its hash
  function, for every person and the seeded quarter `2026-Q3`, with raw value, display string and
  source label. They load into `culture_signals` with `is_simulated = true` and are never recomputed.
- CI fails when `seed/extracted/` differs from a fresh extraction: re-run the tool and commit.
- `lib/load-prototype.mjs` runs `app.js` in a Node `vm` sandbox (DOM stubbed, clock and
  `Math.random` pinned) and returns `get(name)` for any binding inside it. The oracle harness
  (task 4.2) can reuse it.
