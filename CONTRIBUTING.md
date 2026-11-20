# Contributing to Pulse

Pulse v1 is built in one week by three people working in parallel, one **track** each. The rules below keep the tracks out of each other's files so daily merges stay conflict-free. The full plan lives in `openspec/changes/pulse-v1-baseline/` (`tasks.md` for the work and `design.md` for the decisions, especially decision 10).

## The three tracks

| Track | Owner | Scope | Task groups | Migration and seed range |
|---|---|---|---|---|
| 1 | @Gauravx2003 | Platform, auth, DB schema and base seed, calculation core, shared admin (users, rules, integrations, notifications), UI kit and app shell | 1–6 | `V100–V199`, `S100–S199` |
| 2 | @ROMIL05 | Work items, utilization, pull requests, effort, bugs and support, action center, progress, OKRs, road map | 7–15 | `V200–V299`, `S200–S299` |
| 3 | @Krupen08 | CSV engine and data imports, attendance, customer visits, culture, reviews (incl. 1:1s, kudos, 360°), training, appraisal | 16–22 | `V300–V399`, `S300–S399` |

Groups 23 (integration checks) and 24 (live GitHub and Jira, Phase 2) are shared. Nobody starts group 24 before Phase 1 acceptance (23.1–23.4) passes.

## Who owns which directory

`.github/CODEOWNERS` is the source of truth, and `scripts/check-codeowners.sh` fails CI when a module directory has no owner.

- **Modules.** `backend/modules/<id>/` and `frontend/src/modules/<id>/` belong to exactly one track. Never edit another track's module directory. Ask its owner instead.
  - Track 1: `users`, `rules`, `integrations`, `notifications` (and `sample`, to be deleted)
  - Track 2: `work`, `utilization`, `pull_requests`, `effort`, `bugs`, `action_center`, `progress`, `okrs`, `roadmap`
  - Track 3: `imports`, `attendance`, `visits`, `culture`, `reviews`, `training`, `appraisal`

  Adding a new module id? Add its CODEOWNERS lines in a Track 1 PR first.
- **Calculation library.** `backend/calc/core/` belongs to Track 1, `backend/calc/work/` to Track 2 and `backend/calc/people/` to Track 3.
- **Shared and root files** belong to Track 1 only. That covers `pom.xml`, `package.json`, `docker-compose.yml`, `.github/`, `scripts/`, `backend/src/` (platform code), the frontend shell outside `src/modules/`, `README`, `CONTRIBUTING.md` and `docs/`. If you need a change there, open an issue or ask Track 1.
- **Cross-track data** is read through the owning module's service interface, never by querying its tables.

## How a module plugs in (no shared registry)

- **Backend.** Create `backend/modules/<id>/` with a public no-arg class `com.pulse.modules.<id>.<Name>Module implements PulseModule`. Routes mount under `/api/v1/<id>`. Permissions, menu entries, jobs and action providers are declared on that class. See `backend/modules/sample/`.
- **Frontend.** `frontend/src/modules/<id>/index.js` is picked up by `import.meta.glob`. See `frontend/src/modules/sample/`.
- **Migrations and seeds.** Put them in `backend/modules/<id>/migrations/V<n>__<description>.sql` and `backend/modules/<id>/seed/S<n>__<description>.sql`, with `<n>` inside your track's range. They run at startup in number order across all modules.
  - Migrations are **forward-only**. Never edit a file that has been merged: the runner refuses to start when an applied script's checksum changes. Add a new version instead.
  - Leave gaps (Track 2 uses V200 for the data layer, V210 for OKRs and V220 for the road map) so later fixes have room.
  - `scripts/check-migration-versions.sh` fails CI on duplicate numbers, bad names and out-of-range versions.

Backend code must follow the "Vert.x & Java Coding Standards" in `design.md` decision 1:
- two verticle classes only
- nothing blocks the event loop
- reactive `PgPool` with `Tuple` parameters
- errors go through `ctx.fail` to the central failure handler
- `JsonObject`/`JsonArray` instead of DTO classes
- SQL lives in repository classes

## Branches and merging

- **One branch per track:** `track-1`, `track-2`, `track-3`. Short-lived feature branches off your track branch are fine.
- **Merge to `main` at least once a day** through a pull request, after rebasing on the latest `main`. Small daily merges keep conflicts small.
- **Day 1 contract freeze.** Track 1 lands groups 1–3, tasks 2.1–2.3, 4.10, 4.11, the `ConnectorPort`/`SeedConnector`/`IngestService` part of 5.4 and 6.1–6.4 by the end of Day 1. Until then, Tracks 2 and 3 use stubs and then rebase onto the frozen contracts. After the freeze, contracts change only with Track 1's agreement.
- Every pull request needs green CI and a review from the code owner of each directory it touches.
- Mark a task `[x]` in `tasks.md` only in the PR that completes it, and only when its "verify" step passes.

## Pull request checklist

Copy this into the PR description:

```
- [ ] Only my track's directories are changed (or I have the owning track's approval).
- [ ] No formula or threshold constant outside `calc/`; modules call `CalcService`.
- [ ] Oracle comparison included for any new formula (harness from task 4.2, against `reference/2-prototype/src/app.js`).
- [ ] New migrations/seeds use my track's range; no merged migration was edited.
- [ ] Every new route declares its permission and reads through the policy service (no hand-written visibility filters).
- [ ] No hex colors in `.vue` files; tokens only.
- [ ] Screens match their "Screen detail" block in `reference/1-spec/Pulse-Product-and-Development-Specification.md`.
- [ ] Tests added; `mvn test` and the frontend build pass locally.
- [ ] Task checkboxes in `tasks.md` updated for the tasks this PR completes.
```

## Running locally

```bash
docker compose up -d postgres          # PostgreSQL 16 on :5432 (user pulse / pulse_secret_dev)
cd backend && mvn exec:java            # runs migrations + seeds, then serves :8088
cd frontend && npm install && npm run dev
```

The backend reads `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD` and `PULSE_DB_POOL_SIZE`. The database tests in `MigrationRunnerTest` use the same variables and are skipped when no database is reachable.
