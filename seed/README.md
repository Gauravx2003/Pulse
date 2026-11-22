# Seed data

`seed/extracted/*.json` is the demo data of Pulse, extracted from the prototype
(`reference/2-prototype/src/app.js`) so seeded numbers match the prototype oracle.

```bash
node seed/extract.mjs          # rewrite seed/extracted/ (deterministic, safe to re-run)
node seed/build-sql.mjs        # regenerate the base seed SQL from seed/extracted/
node --test seed/extract.test.mjs seed/build-sql.test.mjs
```

`build-sql.mjs` writes two seed files that the backend loads at startup (task 2.3):

- `backend/src/main/resources/db/seed/S100__roles_and_rules.sql`: built-in roles with their
  permission grants and the rule defaults as rule version 1 (every environment).
- `backend/src/main/resources/db/seed/S110__demo_org.sql`: demo accounts (password `demo123`,
  bcrypt cost 12 computed by pgcrypto at load time, so no hash is committed), people with level,
  department, joined date and CTC, reporting lines, holidays, sprints and releases.

They are generated: never edit them by hand. Like migrations, a seed file that has been applied
must not change, so after a merge regenerate only into a new `S1xx` file (or reset the dev DB).

Tables that mirror an external tool (the ones with `source`, `external_id`, `synced_at`) are not
seeded by SQL files: the backend's `SeedConnector` reads `seed/extracted/*.json` (packaged onto the
classpath as `seed/extracted/` by `backend/pom.xml`) through each module's `SeedMapper`s and writes
through `IngestService` at start-up while `PULSE_DEMO_SEED=true` (task 5.4, docs/backend-guide.md §7).
`S110` predates the connector and still inserts sprints, releases and holidays; the connector re-ingests
the same keys idempotently.

- Each file is a JSON array of rows. Keyed prototype maps are flattened, for example
  `ATT[person][date]` becomes `attendance.json` rows `{person, date, st, in, out}`.
- Rows keep the prototype's own keys (`ps`, `S43`, `8.2`, …); loaders use them as `external_id`.
- `culture_signals.json` holds the 12 culture parameters the prototype generates with its hash
  function, for every person and the seeded quarter `2026-Q3`, with raw value, display string and
  source label. They load into `culture_signals` with `is_simulated = true` and are never recomputed.
- CI fails when `seed/extracted/` differs from a fresh extraction: re-run the tool and commit.
- `lib/load-prototype.mjs` runs `app.js` in a Node `vm` sandbox (DOM stubbed, clock and
  `Math.random` pinned) and returns `get(name)` for any binding inside it. The oracle harness
  (task 4.2) reuses it through `lib/oracle-server.mjs`; see `docs/oracle.md`.
