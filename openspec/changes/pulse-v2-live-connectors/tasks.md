# Tasks: Phase 2: live read-only connectors (Jira Server first, then GitHub)

Three interns work in parallel, one track each, exactly as in Phase 1 (`pulse-v1-baseline/tasks.md`). Apply **only the groups of your own track**. Rules that keep the tracks conflict-free (see design.md decision 9):

- **Start condition.** Start only after Phase 1 acceptance (baseline 23.1 to 23.4) has passed on seed data.
- **Directory ownership.** It is unchanged from `CONTRIBUTING.md`. Never edit another track's directory.
  - **Track 1:** platform code in `backend/src/` (secrets, guarded HTTP, live registry, sync orchestrator, external accounts, mock server and contract test kit under `backend/src/test/`), `modules/integrations` (backend and frontend), `modules/users`, `docs/`, CODEOWNERS.
  - **Track 2:** the work tables and screens. Migrations V290/V291, `calc/work` loaders, `modules/work`, `modules/pull_requests`, `modules/utilization`, `modules/effort`.
  - **Track 3:** the new module `backend/modules/connectors/` (`jira/`, then `github/`), the fixtures under `backend/src/test/resources/live-fixtures/`, and the Phase 2 Playwright flows. Track 1 hands the fixtures folder to Track 3 in CODEOWNERS.
- **Migrations.**
  - Track 1 uses `V190–V199`. Track 2 uses `V290–V299`: V290 for Jira, V291 for GitHub. Track 3 needs none; `V390–V399` is reserved.
  - All migrations are additive only. No Phase 1 table, column, endpoint, response shape, formula or threshold changes.
- **Jira first.**
  - Every track does its Jira tasks before its GitHub tasks.
  - GitHub work may be built in parallel, but it merges to `main` only after Jira acceptance (27.1 and 27.2) passes.
  - The GitHub connector must not need any change in `backend/src/` or the Integrations frontend (the plug-in proof, design.md decision 7).
- **Day 1 contract freeze (Phase 2).** Everyone else starts with stubs and rebases onto these:
  - **Track 1** lands:
    - 24.1: the `ExternalAccountService` interface and a stub
    - 24.3: `LiveConnector`, `LiveContext`, `ModuleContext.connectors().register(...)`, the empty `modules/connectors/` module and its CODEOWNERS line
    - 24.4: the mock server and `AbstractLiveConnectorContractTest` skeleton
  - **Track 2** lands the V290 column names that Track 3 writes into: `issues.html_url` and `issues.assignee_account_id`.
- **Cross-track calls.** Cross-track data is read only through the owning service. Track 3 connectors call only `LiveContext` (Track 1) and write only through `IngestService`. They never touch Track 2 code.
- **Coding standards (design.md decision 0).** These apply to every task:
  - `JsonObject` / `JsonArray` only: no POJOs, DTOs, records or entity beans.
  - SQL only in repositories that extend `BaseRepository` over `SqlExecutor` with `Tuple` parameters.
  - Ingest tables are written only through `IngestService` (`source`, `external_id`, `synced_at`).
  - Nothing blocks the event loop: crypto and heavy mapping run in `executeBlocking`, and futures are composed with `.compose` / `Future.all`.
  - Route failures go through `ctx.fail` to the central `FailureHandler`.
  - Allman braces, with blank lines between logical statements and blocks.
- **Network.** Every outbound request goes through `LiveContext.http()`: GET only, only to the connector's declared origin, no redirects. Automated tests use the mock server only. Live smoke tests (27.2, 28.2) are manual.
- **PR checklist** (from Phase 1), plus these items:
  - "no secret in any log, response or audit entry"
  - "no outbound call outside `LiveContext.http()`"
  - "no Phase 1 contract changed"
  - "no tool-specific code outside `backend/modules/connectors/<tool>/`"
  - "no POJO/DTO; Allman braces"

---

# TRACK 1: Generic live connector platform, Integrations UI, privacy, docs

Suggested order: 24.3, 24.1, 24.4 (Day 1 freeze), then 24.2, 24.5, 24.6, 24.7, 24.8.

## 24. Generic live connector platform

- [x] 24.1 **Migration V190 and external accounts.** *(Day 1: interface and stub)*
  - Create three tables:
    - `external_accounts`: id, person_id nullable, tool, tool_account_id, login, display_name, email nullable, is_bot, first_seen_at, timestamps; unique on (tool, tool_account_id)
    - `sync_state`: tool primary key, cursor, last_run_at, status, last_error_code
    - `integration_connections`: tool primary key, config jsonb, secret_ciphertext, secret_iv, secret_last4, expires_at, state, rotated_at, version
  - Implement `ExternalAccountService` and `ExternalAccountRepository extends BaseRepository` in `backend/src/`, with these operations:
    - `resolve(tool, JsonObject {accountId, login, displayName, email})`: an existing link, else a case-insensitive match against `people.email`, else an unmatched row
    - `link` and `unlink`
    - `listUnmatched(tool?)`
    - `setBot`
  - Add Admin endpoints under `/api/v1/integrations/accounts` for those operations, with audit entries.
  - Verify:
    - the migration applies after V300
    - every Phase 1 test passes unchanged
    - `Priya@Corp.com` matches `priya@corp.com`
    - a second link of the same account returns 409
    - forbidden and nonexistent records get the same response
    - Admin-only role matrix
- [x] 24.2 **Secret storage.**
  - Implement `SecretBox`:
    - AES-256-GCM with a random 96-bit IV
    - key from `PULSE_SECRET_KEY` (32 bytes, base64)
    - encrypt and decrypt run in `executeBlocking`
  - Store live-tool secrets (the Jira password or PAT, the GitHub PAT) in `integration_connections`. Seeded tools keep the Phase 1 masked placeholder.
  - Redact the `Authorization` header and every `password`, `token` and `secret` field in logs, errors and audit entries.
  - Verify:
    - after a save, the database dump and the captured logs hold no plain password
    - save is refused without the key, with a 422 message
    - a tampered ciphertext fails to decrypt and blocks the sync with "key"
    - API responses carry only `last4`
- [x] 24.3 **Live connector contract and guarded HTTP.** *(Day 1: the interfaces, the empty connectors module and CODEOWNERS)*
  - Add `LiveConnector` and `LiveContext` (design.md decision 1), with `ModuleContext.connectors().register(...)`. All inputs and outputs are `JsonObject` / `JsonArray`.
  - `LiveContext.http()` wraps the Vert.x `WebClient` and enforces:
    - GET only
    - the exact origin (scheme, host, port) from `allowedOrigins(config)`, where `http` and `https` are both allowed when declared
    - a context-path prefix with no `..` escape
    - no redirects
    - a per-request DNS check that refuses loopback, link-local and metadata addresses unless literally configured
    - 5 s connect and 15 s read timeouts
    - a 10 MB response cap
    - log lines with tool, path and status only
  - Add shared base-URL validation: `http`/`https` only; no user info, query or fragment; and a warning flag for `http`.
  - Create the empty `backend/modules/connectors/` module. Add its CODEOWNERS line (Track 3) and the fixtures folder line (Track 3).
  - Verify each of these:
    - a different origin is rejected before any request leaves the process
    - a different port on the same host is rejected the same way
    - a 302 to `http://10.0.0.5/` is refused and not followed
    - a POST is refused
    - a host name resolving to `169.254.169.254` is refused
    - `http://172.16.10.6:8080` is allowed when configured
    - a stall hits the read timeout
    - a stub connector registered from a test module is listed by the registry
- [x] 24.4 **Mock server and connector contract test.** *(Day 1: skeleton)*
  - Add a test utility that serves recorded fixtures from `backend/src/test/resources/live-fixtures/<tool>/` on `http://127.0.0.1:<port>`. Its test-only loopback flag is refused in the production profile.
  - Add `AbstractLiveConnectorContractTest`, which covers:
    - paged ingest
    - no duplicates on a re-sync
    - cursor use on the second run
    - 401, 429, 500, a stall and a 302 to another host, each keeping earlier pages
    - only GET requests to the declared origin
    - no secret in logs or responses
    - unmatched accounts recorded
  - Start `docs/live-connectors.md`, covering the contract, the fixture format and a step-by-step "how to add a connector". Tracks 2 and 3 send their sections to Track 1 for the merge.
  - Verify: a stub connector extending the contract test passes every case.
- [x] 24.5 **Generic Test connection, Save and Disconnect routes.** They work for any registered live connector.
  - `POST /api/v1/integrations/{tool}/test`:
    - validates the submitted values against `configSchema` and `secretSpec`, including the base URL rules
    - calls `testConnection`
    - returns `{ok, status, account, scopes, scopeNote, fields, expiresAt, hint, warnings}`
    - passes only on HTTP 200
  - `PUT /api/v1/integrations/{tool}/connection` saves only with a passing test proof: single use, valid for 10 minutes, bound to the tool and a hash of the values.
  - Disconnect deletes the secret, keeps every ingested row and writes the credential activity log.
  - Verify with a stub connector and the mock server:
    - 200 passes
    - 401 fails with the status and the connector's hint, Save stays disabled and stored values are unchanged
    - values changed after the test cannot be saved
    - the secret appears in no response
- [x] 24.6 **Generic Sync now orchestrator** (`LiveSyncService`, with `SyncStateRepository extends BaseRepository`). `POST /api/v1/integrations/{tool}/sync` runs these steps:
  1. Takes the per-tool lock (a conditional update on `sync_state.status`); a concurrent call gets 409.
  2. Decrypts the secret into `LiveContext` only.
  3. Calls `fetchPages(ctx, cursor, sink)`, where the sink ingests each page through `IngestService` in its own transaction.
  4. Writes `sync_log` with counts per entity.
  5. Advances the cursor only on success.
  6. Publishes the `CalcService` invalidation event.

  On 401, 403, 429, 5xx, timeout or a bad payload the run stops:
  - pages already ingested stay, and the cursor does not move
  - the status becomes "Sync failed · code"
  - the credential activity log records it
  - the integrations action provider raises a Fix item

  Seeded tools keep the Phase 1 simulation path.

  Verify with a stub connector:
  - two syncs give no duplicates
  - a 500 on page 2 keeps page 1 and leaves the cursor unchanged
  - seed rows are identical before and after a failed and a successful sync
  - a concurrent call gets 409
  - the calculation cache is invalidated
- [x] 24.7 **Integrations frontend** (`frontend/src/modules/integrations/`), generic for any live connector:
  - `CredentialsModal.vue` renders its fields from `configSchema` and `secretSpec`, with no tool-specific code. Supported types:
    - text, secret, URL, list of strings, date, boolean
    - a choice field, for example an auth mode, that shows or hides dependent fields
  - It runs the real Test connection and shows:
    - the account
    - scopes or a scope note
    - proposed fields, for the Admin to confirm
    - the expiry
    - warnings, for example "`http` sends credentials unencrypted"
  - Save is enabled only after a pass. Secrets are never pre-filled.
  - Tiles show:
    - a Live badge and "synced just now / N min ago"
    - "Sync failed · code" with Fix
    - Sync now disabled while a sync runs
    - "Token expires in N days" from `expires_at`
  - An Unmatched accounts panel, filterable by tool, offers link-to-person (search), unlink and a bot toggle.
  - Verify:
    - with a stub connector's schema, the form renders and submits with no frontend change
    - the shared UI audit and axe are clean at 390, 1024 and 1440 px
    - keyboard-only runs of the modal and the panel pass
    - a Manager sees the "You don't have access" card
- [x] 24.8 **Privacy on deletion and runbook.**
  - User deletion (`modules/users`):
    - unlinks and pseudonymizes that person's `external_accounts` (login, display name and email cleared)
    - leaves live rows with a null person, so team aggregates stay
    - lists this in the deletion dialog
  - Update `docs/demo.md` and `CONTRIBUTING.md` for Phase 2:
    - the track split and directory ownership
    - `PULSE_SECRET_KEY` generation
    - the Jira user and rights needed (Browse projects)
    - Basic versus Bearer PAT, and the `http` risk
    - how to link demo users to real accounts
    - that seed and live rows are both counted
  - Verify:
    - no username or email remains for the deleted person
    - team totals are unchanged
    - a fresh clone starts with one command

---

# TRACK 2: Work tables, calc loaders and work screens for live rows

Depends on the Track 1 Day 1 freeze (`ExternalAccountService` stub). Suggested order: 25.1 (Day 1 column names), 25.2, 25.3, 25.4, then GitHub: 25.5, 25.6, then 25.7.

## 25. Live rows in work data and screens

- [x] 25.1 **Migration V290 for Jira (additive).** *(Day 1: column names)*
  - Add `html_url` and `assignee_account_id` to `issues`.
  - Add an index on (source, synced_at).
  - Add a read view that resolves the person of live issues through `external_accounts`; seed rows keep their stored person.
  - Point the `calc/work` issue loaders at the view, with no formula change.
  - Verify:
    - the migration applies after V190
    - all Phase 1 Work, Utilization and Discipline tests pass
    - `FullOracleTest` is unchanged
- [x] 25.2 **Work item screens.**
  - Add a source badge (Seed / Jira), an "Open in Jira" link from `html_url` and a source filter (an allow-listed list filter) on Work items and the issue drawer.
  - Rebalance suggestions link to the Jira issue.
  - Add `source` and `html_url` to responses as new fields only.
  - Links open in a new tab with `rel="noopener noreferrer"`.
  - Verify:
    - UI audit and axe are clean, using rows ingested by a stub batch
    - no write request reaches Jira from any action
- [x] 25.3 **Unmatched Jira assignees in per-person figures.**
  - Work items, Utilization and Discipline count a live issue for a person only when the Jira account is linked.
  - Team totals include unmatched issues.
  - Verify:
    - a link-then-read test shows the person's figures change with no re-sync
    - the baseline 23.2 cross-screen consistency test passes with Jira rows present
- [ ] 25.4 **Jira mapping review and shape test.**
  - Review Track 3's Jira → Pulse mapping (26.3) against the Phase 1 seed semantics: statuses, time units, story points, sprint.
  - Add a test that a live issue and a live sprint, ingested from Track 3's fixtures, give the same `CalcService` input shape as their seed equivalents.
  - Verify: the review is approved in Track 3's PR, and the shape test passes.
- [ ] 25.5 **Migration V291 for GitHub (additive).**
  - Add `html_url` and `author_account_id` to `pull_requests`, and `author_account_id` to `commit_days`.
  - Add read views that resolve the person of live rows.
  - Point the `calc/work` PR and commit loaders at the views, with no formula change.
  - Verify: the Phase 1 PR, Effort and Utilization tests and `FullOracleTest` are unchanged.
- [ ] 25.6 **PR screens and unmatched GitHub authors.**
  - Add a source badge (Seed / GitHub) and an "Open in GitHub" link on the PR list and PR detail.
  - Unmatched authors stay out of per-person PR, Effort and Utilization figures until they are linked.
  - Review Track 3's GitHub mapping, and add the PR and commit-day shape test.
  - Verify:
    - UI audit and axe are clean
    - the link-then-read test passes
    - PR standard checks give the same results for live and seed PRs
- [ ] 25.7 **Track 2 regression with live rows.**
  - Re-run the `assertRoleMatrix` contract tests for every Track 2 endpoint with seed, Jira and (after 25.6) GitHub rows present.
  - Re-run the oracle comparison on the seed rows.
  - Verify:
    - zero cross-role leaks
    - the same response for forbidden and nonexistent records
    - oracle results identical to Phase 1

---

# TRACK 3: Connectors (Jira Server, then GitHub), fixtures and end-to-end flows

Depends on the Track 1 Day 1 freeze (`LiveConnector`, `LiveContext`, mock server, contract test skeleton) and Track 2's V290 column names. Until then, code against the interfaces with a fake `LiveContext`. Suggested order: 26.1, 26.2, 26.3, then GitHub: 26.4, 26.5, 26.6. GitHub merges to `main` only after 27.2.

## 26. Live connectors

- [x] 26.1 **Jira fixtures** from the internal instance (`http://172.16.10.6:8080`), using a read-only demo user, sanitized (passwords, emails and names removed). `jira/field.json` and `jira/search.json` already exist. Add:
  - `/rest/api/2/myself`
  - a second `/rest/api/2/search` page (`startAt` 100) with `expand=changelog`
  - an issue with sprint, story points and time tracking
  - a changelog with a bot edit
  - an assignee without `emailAddress`
  - 401, 429 and 500 responses

  Verify:
  - the fixtures load in the mock server
  - a grep finds no password or real email
  - both sprint field formats (string and object) are represented

  **Status (2026-11-26), done:** recorded with a personal access token, GET only: `/myself`, `/field` and two `/search` pages (`startAt` 0 and 100, `maxResults=100`, `expand=changelog`, the connector's own JQL and `fields=`) from a 10,656-issue project. Sanitized (users renamed `dev.userN` at `@example.com`, host `{{baseUrl}}`, project key `PROJ`, sprint names, summaries, descriptions, comments and free-text changelog values replaced) into `live-fixtures/jira-recorded/`; `jira/myself.json` is the recorded answer too. Those oldest issues have string sprints only, no story points, time tracking, hidden emails or bot edits, so the case coverage stays in `jira/search-page1.json` and `search-page2.json` (5-issue pages, `startAt` 5). 401 is recorded; 429 and 500 stay in Jira's `errorMessages` format, because forcing them on the shared server is unsafe. `JiraFixturesTest` (8) covers the Verify points and the 100-issue pagination.
- [x] 26.2 **`JiraConnector`** in `backend/modules/connectors/jira/`, registered by the connectors module descriptor. It is `JsonObject` in and out, with no model classes.
  - **Config:** Base URL, auth mode (`basic` / `bearer`), Username, Project Keys, story points and sprint field ids, optional Expiry Date, and "ignore bot and non-assignee edits".
  - **Secret:** "Password / Token".
  - **Auth:** Basic sends `Authorization: Basic base64(username:password)`; Bearer sends `Authorization: Bearer <token>`.
  - **Allowed origin:** exactly the base URL's scheme, host and port.
  - **`testConnection`:**
    - `GET /rest/api/2/myself`: username, display name, email
    - `GET /rest/api/2/field`: proposes the story points and sprint field ids
  - **`fetchPages`:**
    - `GET /rest/api/2/search` with `jql=project in (<keys>) AND updated >= "<cursor - 1 min>" ORDER BY updated ASC`, `startAt`, `maxResults=100`, `expand=changelog` and a limited `fields=`
    - pages until `total` is reached
    - status category mapped onto the seed statuses
    - estimates converted from seconds to hours
    - story points read from the configured field
    - the sprint parsed from the string or object form and upserted into `sprints` with `source = 'jira'` via `$ref`
    - `html_url` built as `<baseUrl>/browse/<key>`
    - description and comments stored as plain text or wiki markup strings, with no ADF parsing
    - assignees and changelog authors resolved through `accounts().resolve` with `name` and `emailAddress`
    - with the bot switch on, last activity taken from the changelog
    - `source = 'jira'`, with the issue key as `external_id`
  - Verify:
    - `JiraConnectorContractTest extends AbstractLiveConnectorContractTest` passes
    - Basic and Bearer each send the right header
    - an incremental run sends the cursor minus one minute, and repeated issues do not duplicate
    - an assignee without email lands in the unmatched list under their username
    - a bot-only edit leaves last activity unchanged
    - an unknown story points field is stored as null
    - a base URL with user info, or `http://169.254.169.254`, is rejected on save

  **Status (2026-11-26), done:** `JiraConnector` and `JiraMapper` are built and registered; `JiraConnectorContractTest` (13) and `JiraConnectorTest` (15) pass, and the full backend suite (1185) is green. The two items left open on 2026-11-25 are closed: (1) migration V293 (Track 2's range, additive) adds `issues.description text`, appended to the `issues_with_assignee` view, and the mapper stores `fields.description` as the v2 plain text / wiki markup string (non-strings become null; seed rows stay null); comment bodies go to `issue_events.to_value` with kind `comment`. (2) "Rejected on save" is checked with Jira's own schema through `LiveConfig.validate`, the check the 24.5 Test connection and Save routes run (400 on `config.baseUrl` for user info and `http://169.254.169.254`). Mapping choices for the 25.4 review: sprint key `S<jira sprint id>` and number = the Jira id; sprints without dates (future) are not stored and the issue's `sprint_id` is null; `ends_on` is the Sunday on or after `endDate`; an in-progress status whose name contains "review" maps to In Review; more than one sprint sets `carried_over`; `started_on` is the first status change; with the bot switch off, Jira's `updated` is written as a `transition` event of the assignee so it counts as last activity.
- [ ] 26.3 **Jira mapping record and end-to-end flow.**
  - Write the Jira Server → Pulse mapping section of `docs/live-connectors.md` (sent to Track 1 for the merge, reviewed by Track 2 in 25.4).
  - Add a Playwright flow against the mock server:
    1. Admin enters the base URL, username and password; the `http` warning shows.
    2. Test passes and proposes the field ids; Admin confirms them and saves.
    3. Sync now runs, and the issue appears with a Jira badge.
    4. Workload, Discipline, index and the appraisal draft score move consistently.
    5. An unmatched username is linked, and the person's figures update.
    6. A 401 gives "Sync failed · 401", and Fix opens Change credentials.
    7. A concurrent Sync now gets 409.
    8. Disconnect keeps the rows.

  Verify: the flow passes on a fresh database with `PULSE_DEMO_SEED=true`.
- [ ] 26.4 **GitHub fixtures** from a sandbox repo, sanitized. Include:
  - `/user`
  - `/repos/{o}/{r}` with classic-token and fine-grained-token headers
  - two pages each of pulls and commits
  - 401, 403, 429 and 500

  Verify: the fixtures load in the mock server and contain no token or real email.
- [ ] 26.5 **`GitHubConnector`** in `backend/modules/connectors/github/`. It is `JsonObject` in and out.
  - **Config:** owner and repo; `secretSpec` describes the fine-grained PAT (Metadata, Pull requests and Contents read).
  - **Allowed origin:** `https://api.github.com:443`.
  - **`testConnection`:**
    - `GET /user` and `GET /repos/{owner}/{repo}`
    - scopes from `X-OAuth-Scopes`, or the fine-grained note when there is no header
    - expiry from `github-authentication-token-expiration`
  - **`fetchPages`:**
    - pull requests from `pulls?state=all&sort=updated&direction=desc&per_page=100`, paged by `Link` until `updated_at` < cursor, capped at 1,000 per run
    - commits from `commits?since=cursor&per_page=100`, aggregated into `commit_days` per author per day
    - authors resolved through `accounts().resolve`
    - `source = 'github'`
  - Verify:
    - `GitHubConnectorContractTest extends AbstractLiveConnectorContractTest` passes
    - three commits on one day give one row with a count of 3
    - a classic token lists scopes, and a fine-grained token shows the note
    - an unknown author is stored with a null person
    - **the PR touches no file under `backend/src/` or `frontend/src/modules/integrations/`**
- [ ] 26.6 **GitHub mapping record and end-to-end flow.**
  - Write the GitHub → Pulse mapping section of `docs/live-connectors.md` (reviewed by Track 2 in 25.6).
  - Add a Playwright flow against the mock server: connect with a fine-grained token, sync, PR with a GitHub badge, link an unmatched login, 401, Disconnect.
  - Verify: the flow passes with seed, Jira and GitHub rows together.

---

# ALL TRACKS: Acceptance (after the tracks merge)

## 27. Jira acceptance (before any GitHub merge)

- [ ] 27.1 After a mock-server Jira sync, re-run:
  - the baseline 23.1 contract matrix
  - the 23.2 oracle on seed rows and the cross-screen test
  - the 23.4 UI audit
  - the Jira Playwright flow (26.3)

  Verify:
  - nothing regresses
  - disconnecting Jira leaves ingested and seed rows intact
- [ ] 27.2 **Jira security pass and live smoke test** (led by Track 1).
  - Grep the logs, database dump and API responses of the e2e run for the test password: zero hits.
  - Confirm every outbound request was a GET to the configured origin with no redirect followed.
  - Run `/security-review` on the Jira diff and fix every finding.
  - Run the manual smoke test once against `http://172.16.10.6:8080` with a read-only demo user, and record it in `docs/demo.md`.

## 28. GitHub acceptance and close

- [ ] 28.1 After GitHub merges, repeat 27.1 with seed, Jira and GitHub rows together, plus the GitHub Playwright flow (26.6).
- [ ] 28.2 Repeat the 27.2 security pass for `api.github.com`. Run the manual smoke test with a real fine-grained PAT, and record it in `docs/demo.md`.
- [ ] 28.3 Review the "Deferred hardening" list (baseline design.md) and this change's Open Questions. Then archive `pulse-v1-baseline` and `pulse-v2-live-connectors`, in that order, and verify the archived specs with `openspec validate`.
