# Design

## Context

Pulse is built from the reference package in `reference/` (spec, prototype v11, design system v30). Stack is fixed: Vue 3 SPA, Vert.x backend, PostgreSQL. See proposal.md for motivation and the capability specs for requirements. This document covers how, and where this change departs from the reference.

**Delivery constraint:** three interns build a full working v1 in one week, in parallel. This drives a *lean v1*: security that is standard and enforced in the service layer and rich seed data. Everything is built on seed data first; then two live read-only connectors for the demo (GitHub with a personal access token, Jira Cloud with an API token) are added later in the same week. Controls that were drafted for a larger production build (MFA, KMS, row-level security, audit hash chain, live OAuth and webhooks) are deferred, not forgotten; see "Deferred hardening".

## Goals / Non-Goals

**Goals:**
- One calculation library whose output is checked against the prototype oracle, so the same number appears on every screen.
- Every permission and visibility rule enforced server-side in one service layer.
- Three tracks that touch disjoint files, so they can run in parallel without merge conflicts.
- A demo that runs end to end on seeded data, including every role's demo account, and shows real GitHub pull requests and Jira issues flowing in beside it.

**Non-Goals (v1):**
- SSO, MFA, KMS or envelope encryption, PostgreSQL row-level security, audit hash chain.
- Live TFS, Slack, HRMS or PagerDuty connections, OAuth flows and webhooks. (Live GitHub and Jira Cloud read-only connectors are in scope; see Decision 6.)
- Payroll, leave approval, multi-tenancy (single organization per deployment).
- Production-hardening for external exposure beyond the baseline below.

## Decisions

### 1. Architecture: modular monolith
One Vert.x deployable, one PostgreSQL database, modules per capability group. Heavy work (BCrypt, CSV checks, seed reading) runs via `vertx.executeBlocking`; see the Vert.x & Java Coding Standards below. Single deployed process in v1 (one JVM, one container), so no cross-process job locking.
- *Why:* one-number-everywhere needs strong consistency; a three-person team cannot operate distributed services.
- *Rejected:* microservices, separate calculation service.

```
 Vue 3 SPA --HTTPS--> [Vert.x router]
                        | session | CSRF | validation | rate-limit
                        v
                 [policy service]  <- every request: permission + visibility
                        v
        +---------------------------+     +---------------------+
        | domain modules (per spec) |<--->| calculation library |
        +-------------+-------------+     +---------------------+
                      v
                 PostgreSQL (schema, seed, audit table)
```

#### Vert.x & Java Coding Standards (strict, binding on all three tracks' `/opsx:apply` runs)

1. **Verticle topology.** Exactly two verticle *classes*: `HttpServerVerticle` (mounts `/api/v1` sub-routers from self-registering modules) and `SchedulerVerticle` (in-process cron and periodic jobs). Never deploy a verticle per module. Modules are plain Java classes (Router, Service, Repository) that share one `PgPool` and the `CalcService`.
   - **Instances, not classes, give parallelism.** `HttpServerVerticle` is deployed with `DeploymentOptions.setInstances(n)`, where `n` defaults to `Runtime.getRuntime().availableProcessors()` and can be overridden by config (`PULSE_HTTP_INSTANCES`). All instances listen on the same port and Vert.x round-robins connections across their event loops. Each instance mounts every module.
   - **`SchedulerVerticle` always runs as exactly 1 instance.** More instances would fire every job more than once.
   - **One shared `PgPool` across instances.** Create it with `PoolOptions.setShared(true)` and a fixed pool name, so N HTTP instances reuse one connection pool instead of opening N pools. Size it with `PULSE_DB_POOL_SIZE` and keep it below the PostgreSQL `max_connections` limit.
   - **No per-instance mutable state.** Handlers and services stay stateless. Anything that must be consistent across instances (sessions, sign-in lockout counters, per-IP rate-limit counters, caches that are written to) lives in PostgreSQL or in `vertx.sharedData()`, never in a field of a verticle or module object.
2. **Zero event-loop blocking.**
   - HTTP handlers and services must never do synchronous I/O, `Thread.sleep()`, JDBC, or heavy CPU loops.
   - BCrypt hashing and verification, CSV parsing and validation (up to 5,000 rows), and seed-file reading always run inside `vertx.executeBlocking(() -> ...)`.
3. **Reactive DB and Future composition.**
   - Use only `io.vertx.pgclient.PgPool` (reactive SQL client) with parameterized `Tuple` queries. No string-concatenated SQL.
   - Chain multiple queries with `Future.all(...)` and `.compose(...)`, never nested callbacks.
   - Transactions use `pool.withTransaction(conn -> ...)`.
4. **Error and context propagation.** Every route failure goes through `ctx.fail(statusCode, exception)` to one central `FailureHandler`, which returns the standard JSON error contract for 400, 403, 404, 409 and 422. Handlers do not write error responses themselves.

### 2. Authorization: service-layer policy, no RLS
Every route declares its required permissions. A single policy service resolves visibility (self, direct report, all.view, mentor, account owner) and is the only code that builds visibility filters; domain modules call it and never write their own. Forbidden and nonexistent records return the same response. Contract tests per role per endpoint are mandatory.
- *Why:* fits the one-week budget; tests, not database policies, are the safety net.
- *Trade-off:* one missed filter is a data leak. Mitigated by routing all reads through repository helpers that require a policy scope argument.

### 3. Authentication and sessions
Email and password against bcrypt hashes (cost 12). Server-side sessions in PostgreSQL with an opaque id in an HttpOnly, Secure, SameSite=Strict cookie, 12 h lifetime, CSRF token on writes, immediate revocation on deactivation, password change or permission change. Lockout after 5 failures for 15 minutes plus a per-IP rate limit on sign-in. Password reset and set-password invite links are single-use and expiring; in v1 the mail goes to an outbox table with optional SMTP.
- *Passwords:* user-chosen passwords need at least 8 characters and must not be the email or name. **Demo accounts** (`hr@`, `rahul@`, `priya@` ... password `demo123`) are created by the demo seed, which bypasses the policy and is switched on only by `PULSE_DEMO_SEED=true`. The sign-in screen lists demo cards only when that flag is on.
- *Alternatives:* JWT (rejected: revocation); MFA (deferred).

### 4. Data model conventions
UUID primary keys; `created_at`, `updated_at`, `version` on editable rows; effective-dated tables for reporting lines and rule versions; deactivation distinct from deletion. Migrations are versioned per track (see Parallel build). The app role cannot run DDL.

### 5. Calculation library and oracle verification
All formulas of reference section 12 live in one library, structured as one file per formula family, as pure functions over plain data, plus a thin `CalcService` that loads data from PostgreSQL and calls them. Closed periods, published 360° cycles and submitted appraisals store their inputs and rule version so they stay reproducible.

**Library layout and ownership** (one folder per track, so no merge conflicts):

| Folder | Owner | Contents |
|---|---|---|
| `calc/core/` | Track 1 | period and working-day calendar, `index`, `valData`/`cult`, `discMetrics`, `attStats`, `trRating`, `aprRecommend`, `CalcService`, formula registry, result guard, snapshots and rule versions |
| `calc/work/` | Track 2 | workload and utilization, stale and sprint metrics, PR standard, effort and extra-mile, bugs and support stats, OKR pillar (OKRs belong to Track 2) |
| `calc/people/` | Track 3 | visit points and visit stats, 360° results aggregation (anonymity merge, hidden strengths, blind spots) |

Each sub-folder exports a formula-module descriptor that `CalcService` discovers by directory scan (the same self-registering pattern as modules), so Tracks 2 and 3 add formulas without editing `calc/core`. Everything is exposed **only** through the `CalcService` interface; no formula, threshold constant or band table lives in `backend/modules/*`, and screens and endpoints read numbers from `CalcService`. This is verified by a PR checklist item and by the cross-screen consistency test (tasks.md 23.2); no custom CI script.

**Oracle rule:** each ported formula is verified against `reference/2-prototype/src/app.js` by running the same fixture through the prototype function and the port and comparing results to the displayed precision. Oracle functions: `index` (line 190), `cult` (the later definition at ~2251 supersedes the one at 448), `valData` (~2202), `discMetrics` (~703), `attStats` (~2301), `trRating` (~1652), `aprRecommend` (~996), plus their helpers (`valueBonus`, `discScore`, `aprParts`, `aprBand`, `aprRules`, `monthStats`, `qStats`, `ciStats`, `visitPts`, `vStats`, `prStats`, `mentorStats`, `perMetrics`). A harness loads `app.js` in a sandboxed Node context and exposes these functions to tests.
- *Why:* the prototype is the only executable statement of the formulas; if the port and the prototype disagree, the spec section 12 text decides and the difference is recorded.

### 6. Seed data and external tools
TFS, Slack and HRMS data come from rich pre-seeded PostgreSQL tables, derived from the prototype's sample data. Jira and GitHub are seeded the same way and additionally have live read-only connectors. The Integrations screen shows connector tiles and settings forms. Until Phase 2 lands, every tool is seeded. For the seeded tools **Sync now** runs a simulation that bumps "synced N min ago", writes a sync log entry and re-derives aggregates from seed, with no network call. For GitHub and Jira, **Test connection** makes a real authenticated request and **Sync now** runs the real `fetchChanges(since)`. No webhooks and no OAuth in v1. Credential forms of seeded tools store only masked placeholders. Nudge and kudos-to-Slack write to a notifications outbox.
- *Live connectors (GitHub and Jira Cloud), built in Phase 2:* v1 is first built and accepted entirely on seed data (tasks groups 1 to 23). The live connectors are added later in the same week (tasks group 24), only after that acceptance passes, behind the `ConnectorPort` seam so no Phase 1 contract or formula changes. Their tables (`external_accounts`, `sync_state`, `integration_connections`) arrive in migration V190, not in the base schema. Both are `ConnectorPort` implementations built on the Vert.x `WebClient`, read-only (GET only), and write only through `IngestService`.
  - `GitHubConnector` takes a PAT and owner/repo, reads `/repos/{owner}/{repo}/pulls?state=all` and `/repos/{owner}/{repo}/commits`, aggregates commits per author per day into `commit_days`, and upserts with `source = 'github'`, `external_id` = GitHub id. Pull requests have no `since` filter, so it pages `sort=updated&direction=desc` and stops at the stored cursor; commits use `since`.
  - `JiraConnector` takes site URL, email and API token (basic auth), reads `/rest/api/3/search/jql` with a `updated >= cursor` JQL over the configured boards and follows `nextPageToken`, and upserts issues with `source = 'jira'`, `external_id` = issue key. The sprint field maps to a `sprints` row upserted with `source = 'jira'`.
  - *Test connection:* GitHub `GET /user` plus `GET /repos/{owner}/{repo}` (scopes from `X-OAuth-Scopes`), Jira `GET /rest/api/3/myself`. Pass only on HTTP 200.
  - *Sync now:* runs `fetchChanges(since)`, upserts through `IngestService`, writes `sync_log`, advances the cursor in `sync_state`, then publishes the same cache-invalidation event that CSV commits use so `CalcService` recomputes. A 401, 429 or 5xx stops the run, keeps rows already written and sets the tile to "Sync failed · code". Live sync runs only through Sync now in v1.
  - *Person matching:* live authors and assignees are matched to people through `external_accounts` (work email first, manual link otherwise). Unmatched records are stored with a null person and stay out of per-person figures until linked.
  - *Secrets:* the PAT and API token are stored AES-256-GCM encrypted under `PULSE_SECRET_KEY` and are never returned, logged or audited. This replaces "masked placeholder only" for these two tools; KMS stays deferred. `integration_connections` (tool, non-secret config json, secret ciphertext, last 4, state, rotated_at) holds them.
  - *Outbound safety:* GitHub requests go only to `https://api.github.com`; the Jira site URL must be `https` and end in `.atlassian.net`; redirects are not followed; every request has connect and read timeouts. These are the minimum SSRF guards now that something makes outbound calls.
  - *Coexistence with seed:* `SeedConnector` still runs at startup whenever `PULSE_DEMO_SEED=true` (`source = 'seed'`), so demo users and all screens stay populated. Live rows live beside them under their own `source`; the unique index on (source, external_id) means the two never collide, a live sync never deletes seed rows, and `CalcService` reads every source together. If the live repository or project belongs to the same people as the seed, their figures include both; the demo runbook says so.
- *Culture reported inputs:* 12 of the 35 culture data parameters have no source table in v1 (the prototype generates them from a hash). They live in `culture_signals` (person_id, quarter, param_key, raw_value, display_value, source_label, is_simulated), extracted by the seed tool from the prototype's `valData`, read by `calc/core` and shown in the detail view with their source label as reported inputs; a live connector can fill the same table later.
- *Seed is the first connector (seam for live data):* tables that mirror an external tool carry `source` (default `'seed'`), `external_id` and `synced_at` with a unique index on (source, external_id). A `ConnectorPort` interface (`fetchChanges(since)` returning normalized records) has three v1 implementations: `SeedConnector`, which reads the extracted seed JSON, and the live `GitHubConnector` and `JiraConnector` described above. An `IngestService` is the only write path to those tables (idempotent upsert by source and external id), used by the seed loader and by **Sync now**. Every connector writes through the same service, so calculation and screens do not change. Port, `SeedConnector` and `IngestService` belong to Track 1 (task 5.4); the columns are added by the track that owns each table (tasks 2.1, 7.1, 17.1).
- *Salary/CTC* lives in the people seed table and an optional `ctc` column in the Users CSV import; it is returned only through the appraisal service to `appraisal.manage` and `appraisal.approve` holders and is excluded from logs and from every export except the appraisal export.
- *Today:* the clock is the server clock in **Asia/Kolkata**; a `PULSE_TODAY` setting (ignored in production profile) pins "today" to 2026-09-28 so demo data and tests match the prototype. Dates display as "28 Sep", times in 24 h.

### 7. Audit log
A simple append-only PostgreSQL table written in the same transaction as the action. The application never issues UPDATE or DELETE on it, and the migration revokes those rights from the app role. No hash chain. Events listed in platform/audit-log still apply.

### 8. CSV engine
Streaming parser with size limits, staging tables bound to user, type and check id, commit in one transaction with before-images for undo, formula-safe exports.

### 9. Frontend
Vue 3 SPA. Reuse `reference/3-design-system` directly: copy `tokens.css` and `components/bundle.css` into the app as the only styling base and wrap the documented components (Badge, StatTile, UtilizationMeter, TeamHeatmap, SprintTaskTable, CultureValues, IntegrationTile, RoadmapTimeline and so on) as Vue components without restyling. Colors, spacing, radius and type come from tokens only; light and dark themes come from the token themes. Menu derives only from `GET /me`. User content renders as text; CSP without inline scripts. One API client handles 401, 403, 409 and 429.

### 10. Parallel build: three tracks, disjoint files
- **Ownership by directory.** Backend `backend/modules/<module>/` and frontend `frontend/src/modules/<module>/` belong to exactly one track.
- **Self-registering modules.** A module exports its routes, permissions, menu entries and migration/seed locations; the shell discovers modules by directory scan (backend) and `import.meta.glob` (frontend), so nobody edits a shared registry.
- **Migrations per track:** Track 1 uses `V100–V199`, Track 2 `V200–V299`, Track 3 `V300–V399`, so numbers never collide. Seed files follow the same ranges and load in number order.
- **Two phases.** Phase 1 builds everything on seed data. Phase 2 (live GitHub and Jira, tasks group 24) starts only after Phase 1 acceptance and must not change Phase 1 contracts.
- **Contract freeze at the end of Day 1.** Track 1 publishes the skeleton, policy API, session middleware, API client, UI kit, base schema (people, roles, rules, calendar) and base seed. Tracks 2 and 3 build against this and may not change it without Track 1's agreement.
- **Calculation library folders:** `calc/core/` belongs to Track 1, `calc/work/` to Track 2 and `calc/people/` to Track 3 (decision 5).
- **Shared files** (root build files, `README`, CI) are edited only by Track 1.
- **Cross-track data** is read through the owning module's service interface, not by querying its tables.

## Deviations from the reference

| Topic | Reference | This design | Why |
|---|---|---|---|
| Sign-in | SSO target, password pilot | Password only, bcrypt, server sessions | Locked decision |
| Passwords | Temp password min 6 | Invite link; 8-char policy; demo seed exempt | Avoid emailed passwords while keeping demo accounts |
| MFA, KMS, RLS, audit hash chain | Not in reference; drafted in earlier specs | Deferred | One-week lean v1 |
| External tools | Live connectors for all tools | Live read-only GitHub (PAT) and Jira Cloud (API token); TFS, Slack, HRMS and the rest seeded with a Sync now simulation | One-week lean v1 with a live demo |
| Salary source | Unspecified | People seed and optional `ctc` CSV column | Locked decision |
| Time zone | Unspecified | Asia/Kolkata, "today" pinnable for demo | Locked decision |
| Rule changes | Apply instantly everywhere | Live and future only; closed periods frozen | Reproducible appraisals |
| Manager data | Single column | Effective-dated lines | Visibility over time |
| Check-in visibility | Contradictory | Direct manager only | Least privilege |
| 360° retention | §13 vs locked decision | Reviewer link removed at once; subject comments scrubbed and unlinked | Reconciles both |
| Appraisal effective date | 1 Oct constant | Per-cycle field | 1 Oct 2026 is past |
| Attendance phase | Phase 4 | Same week, Track 3 first | Capacity and rule h depend on it |
| Delivery and Quality pillars | Computed from sprint metrics, reopen rate and review findings (§12) | Read from seeded `pillars` values, as in the prototype (`app.js`); Growth blends the seeded value with the training score, Culture and OKR are computed | One-week lean v1; computing D and Q from live data needs the connector history that v1 does not have |

## Risks / Trade-offs

- **Dropped controls vs "production grade"** → This build is a demo-grade v1 with a standard security baseline (hashed passwords, CSRF, CSP, validation, rate limits, server-side RBAC, audit table). It must not be exposed to real salary or 360° data until the items under "Deferred hardening" are done.
- **Demo password in a real environment** → The demo seed is behind `PULSE_DEMO_SEED`; startup in a production profile refuses to run with it on.
- **A missed visibility check leaks data** → Policy service is the only filter builder, repositories require a scope, per-role contract tests gate each module.
- **Seed realism drives every screen** → Seed extraction from the prototype is an explicit task per track; formulas are checked against the oracle so seeded numbers match the prototype.
- **Parallel work breaks on shared contracts** → Day 1 contract freeze, directory ownership, per-track migration ranges.
- **Track 1 is a bottleneck** (auth, schema, calculation, UI kit) → Day 1 deliverables are ordered by what Tracks 2 and 3 need first; domain-local formulas are ported by the owning track using Track 1's oracle harness.
- **Pinned "today"** → `PULSE_TODAY` is ignored in the production profile.
- **Free-text name scrubbing is imperfect** → Retained 360° comments after deletion are best-effort scrubbed, used only in aggregate, with a full-removal path.

## Deferred hardening (not in the one-week build)

MFA for privileged roles; KMS-backed envelope encryption and field-level salary encryption; PostgreSQL row-level security; audit hash chain with verification; live OAuth, webhooks, scheduled live polling and real connectors for TFS, Slack, HRMS and PagerDuty; password-breach list; SSO; full SSRF protections for admin-supplied URLs (v1 only allow-lists `api.github.com` and `*.atlassian.net`); formal backup and key-rotation procedures. **Post-v1 live integration phase:** a "reset demo data" command that removes rows with `source = 'seed'`, canonical-record fixtures and contract tests per connector, and computing Delivery and Quality pillars from live sprint data.

## Migration Plan

Greenfield, no data migration. Run migrations then seed; demo seed only with the flag. Rollback by redeploying the previous build; migrations are forward-only within the week.

## Open Questions

- Legal basis and notice for 360° and pulse data (for example India's DPDP Act) before any real data is loaded.
- Which of the deferred hardening items become the first post-v1 work.
