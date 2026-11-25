# Tasks: Phase 2: live read-only connectors (Jira Server first, then GitHub)

## Ground rules

- **Owner and order.** One owner works through the groups in order. Start only after Phase 1 acceptance (baseline 23.1 to 23.4) has passed on seed data.
- **Generic platform first.** Group 24 builds a platform with no Jira-specific code.
- **Jira.** Groups 25 and 26 add the self-hosted **Jira Server / Data Center** connector (REST API v2, Basic auth or Bearer PAT), verified against `http://172.16.10.6:8080`.
- **GitHub gate.** Group 27 (GitHub) starts only after Jira acceptance (26.3) passes. It must not edit `backend/src/` or `frontend/src/modules/integrations/`. If it needs to, fix the platform gap in a separate task first (design.md decision 7).
- **Phase 1 unchanged.** Migrations are additive only:
  - V190–V199 for platform tables
  - V290 (Jira) and V291 (GitHub) for work-table columns and views

  No Phase 1 table, column, endpoint, response shape, formula or threshold changes.
- **Coding standards (design.md decision 0).** These apply to every task:
  - `JsonObject` / `JsonArray` only: no POJOs, DTOs, records or entity beans.
  - SQL only in repositories that extend `BaseRepository` over `SqlExecutor` with `Tuple` parameters.
  - Ingest tables are written only through `IngestService` (`source`, `external_id`, `synced_at`).
  - Nothing blocks the event loop: crypto and heavy mapping run in `executeBlocking`, and futures are composed with `.compose` / `Future.all`.
  - Route failures go through `ctx.fail` to the central `FailureHandler`.
  - Allman braces, with blank lines between logical statements and blocks.
- **Network.** Every outbound request goes through `LiveContext.http()`: GET only, only to the connector's declared origin, no redirects. Automated tests use the mock server only. Live smoke tests (26.4, 27.5) are manual.
- **Reviews.** Platform and shared files are reviewed by the Track 1 owner. Work-table migrations and `calc/work` loader changes are reviewed by the Track 2 owner.
- **PR checklist** (from Phase 1), plus these items:
  - "no secret in any log, response or audit entry"
  - "no outbound call outside `LiveContext.http()`"
  - "no Phase 1 contract changed"
  - "no tool-specific code outside `backend/modules/connectors/<tool>/`"
  - "no POJO/DTO; Allman braces"

---

## 24. Generic live connector platform

- [ ] 24.1 **Migration V190 and external accounts.**
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
- [ ] 24.2 **Secret storage.**
  - Implement `SecretBox`:
    - AES-256-GCM with a random 96-bit IV
    - key from `PULSE_SECRET_KEY` (32 bytes, base64)
    - encrypt and decrypt run in `executeBlocking`
  - Store live-tool secrets (the Jira password or PAT, later the GitHub PAT) in `integration_connections`. Seeded tools keep the Phase 1 masked placeholder.
  - Redact the `Authorization` header and every `password`, `token` and `secret` field in logs, errors and audit entries.
  - Verify:
    - after a save, the database dump and the captured logs hold no plain password
    - save is refused without the key, with a 422 message
    - a tampered ciphertext fails to decrypt and blocks the sync with "key"
    - API responses carry only `last4`
- [ ] 24.3 **Live connector contract and guarded HTTP.**
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
  - Create the empty `backend/modules/connectors/` module that will hold one package per tool.
  - Verify each of these:
    - a different origin is rejected before any request leaves the process
    - a different port on the same host is rejected the same way
    - a 302 to `http://10.0.0.5/` is refused and not followed
    - a POST is refused
    - a host name resolving to `169.254.169.254` is refused
    - `http://172.16.10.6:8080` is allowed when configured
    - a stall hits the read timeout
    - a stub connector registered from a test module is listed by the registry
- [ ] 24.4 **Mock server and connector contract test.**
  - Add a test utility that serves recorded fixtures from `backend/src/test/resources/live-fixtures/<tool>/` on `http://127.0.0.1:<port>`. Its test-only loopback flag is refused in the production profile.
  - Add `AbstractLiveConnectorContractTest`, which covers:
    - paged ingest
    - no duplicates on a re-sync
    - cursor use on the second run
    - 401, 429, 500, a stall and a 302 to another host, each keeping earlier pages
    - only GET requests to the declared origin
    - no secret in logs or responses
    - unmatched accounts recorded
  - Start `docs/live-connectors.md`, covering the contract, the fixture format and a step-by-step "how to add a connector".
  - Verify: a stub connector extending the contract test passes every case.
- [ ] 24.5 **Generic Test connection, Save and Disconnect routes.** They work for any registered live connector.
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
- [ ] 24.6 **Generic Sync now orchestrator** (`LiveSyncService`, with `SyncStateRepository extends BaseRepository`). `POST /api/v1/integrations/{tool}/sync` runs these steps:
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
- [ ] 24.7 **Integrations frontend** (`frontend/src/modules/integrations/`), generic for any live connector:
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
- [ ] 24.8 **Privacy on deletion.** User deletion (`modules/users`):
  - unlinks and pseudonymizes that person's `external_accounts` (login, display name and email cleared)
  - leaves live rows with a null person, so team aggregates stay
  - lists this in the deletion dialog

  Verify:
  - no username or email remains for the deleted person
  - team totals are unchanged

## 25. Jira Server / Data Center connector

- [ ] 25.1 **Migration V290 (additive).**
  - Add `html_url` and `assignee_account_id` to `issues`.
  - Add an index on (source, synced_at).
  - Add a read view that resolves the person of live issues through `external_accounts`; seed rows keep their stored person.
  - Point the `calc/work` issue loaders at the view, with no formula change.
  - Verify:
    - the migration applies after V190
    - all Phase 1 Work, Utilization and Discipline tests pass
    - `FullOracleTest` is unchanged
- [ ] 25.2 **Record Jira fixtures** from the internal instance (`http://172.16.10.6:8080`), using a read-only demo user, then sanitize them: passwords, emails and names removed. Include:
  - `/rest/api/2/myself`
  - `/rest/api/2/field`, with the story points and sprint custom fields
  - two `/rest/api/2/search` pages (`startAt` 0 and 100) with `expand=changelog`. They must contain:
    - an issue with sprint, story points and time tracking
    - a wiki-markup description
    - a changelog with a bot edit
    - an assignee without `emailAddress`
  - 401, 429 and 500 responses

  Verify:
  - the fixtures load in the mock server
  - a grep finds no password or real email
  - both sprint field formats (string and object) are represented
- [ ] 25.3 **`JiraConnector`** in `backend/modules/connectors/jira/`, registered by the connectors module descriptor. It is `JsonObject` in and out, with no model classes.
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
- [ ] 25.4 **Mapping record.**
  - Document the Jira Server → Pulse mapping in `docs/live-connectors.md`: statuses, time units, story points, sprint formats, username and email matching, wiki-markup text.
  - Add a test that a live issue and a live sprint give the same `CalcService` input shape as their seed equivalents.
  - Verify: the Track 2 owner approves the mapping in review, and the shape test passes.

## 26. Jira on the screens and Jira acceptance

- [ ] 26.1 **Work item screens.**
  - Add a source badge (Seed / Jira), an "Open in Jira" link from `html_url` and a source filter (an allow-listed list filter) on Work items and the issue drawer.
  - Rebalance suggestions link to the Jira issue.
  - Add `source` and `html_url` to responses as new fields only.
  - Links open in a new tab with `rel="noopener noreferrer"`.
  - Verify:
    - UI audit and axe are clean
    - no write request reaches Jira from any action
- [ ] 26.2 **Unmatched assignees in per-person figures.**
  - Work items, Utilization and Discipline count a live issue for a person only when the Jira account is linked.
  - Team totals include unmatched issues.
  - Verify:
    - a link-then-read test shows the person's figures change with no re-sync
    - the baseline 23.2 cross-screen consistency test passes with Jira rows present
- [ ] 26.3 **Jira acceptance.** After a mock-server Jira sync, re-run:
  - the baseline 23.1 contract matrix
  - the 23.2 oracle on seed rows and the cross-screen test
  - the 23.4 UI audit

  Add a Playwright flow:
  1. Admin enters the base URL, username and password; the `http` warning shows.
  2. Test passes and proposes the field ids; Admin confirms them and saves.
  3. Sync now runs, and the issue appears with a Jira badge.
  4. Workload, Discipline, index and the appraisal draft score move consistently.
  5. An unmatched username is linked, and the person's figures update.
  6. A 401 gives "Sync failed · 401", and Fix opens Change credentials.
  7. A concurrent Sync now gets 409.
  8. Disconnect keeps the rows.

  Verify that nothing regresses.
- [ ] 26.4 **Jira security pass and live smoke test.**
  - Grep the logs, database dump and API responses of the e2e run for the test password: zero hits.
  - Confirm every outbound request was a GET to the configured origin with no redirect followed.
  - Run `/security-review` on the diff and fix every finding.
  - Run the manual smoke test once against `http://172.16.10.6:8080` with a read-only demo user.
  - Update `docs/demo.md`:
    - the Jira user and rights needed (Browse projects)
    - Basic versus Bearer PAT, and the `http` risk
    - how to find field ids
    - `PULSE_SECRET_KEY` generation
    - how to link demo users to real Jira usernames
    - that seed and live rows are both counted
    - the smoke test record

## 27. GitHub connector (start only after 26.3 passes; no edits to `backend/src/` or the Integrations frontend)

- [ ] 27.1 **Migration V291 (additive).**
  - Add `html_url` and `author_account_id` to `pull_requests`, and `author_account_id` to `commit_days`.
  - Add read views that resolve the person of live rows.
  - Point the `calc/work` PR and commit loaders at the views, with no formula change.
  - Verify: the Phase 1 PR, Effort and Utilization tests and `FullOracleTest` are unchanged.
- [ ] 27.2 **Record GitHub fixtures** from a sandbox repo and sanitize them. Include:
  - `/user`
  - `/repos/{o}/{r}` with classic-token and fine-grained-token headers
  - two pages each of pulls and commits
  - 401, 403, 429 and 500

  Verify: the fixtures load in the mock server and contain no token or real email.
- [ ] 27.3 **`GitHubConnector`** in `backend/modules/connectors/github/`. It is `JsonObject` in and out.
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
    - **`git diff` of this task touches no file under `backend/src/` or `frontend/src/modules/integrations/`**
- [ ] 27.4 **PR screens.**
  - Add a source badge (Seed / GitHub) and an "Open in GitHub" link on the PR list and PR detail.
  - Unmatched authors stay out of per-person PR, Effort and Utilization figures until they are linked.
  - Add the GitHub mapping to `docs/live-connectors.md`.
  - Verify:
    - UI audit and axe are clean
    - the link-then-read test passes
    - PR standard checks give the same results for live and seed PRs
- [ ] 27.5 **GitHub acceptance.**
  - Re-run 26.3 with seed, Jira and GitHub rows together, plus a GitHub Playwright flow: connect, sync, PR with badge, 401, Disconnect.
  - Repeat the 26.4 security pass for `api.github.com`.
  - Run the manual smoke test with a real fine-grained PAT.
  - Update `docs/demo.md` for GitHub.

## 28. Close

- [ ] 28.1 Review the "Deferred hardening" list (baseline design.md) and this change's Open Questions. Then archive `pulse-v1-baseline` and `pulse-v2-live-connectors`, in that order, and verify the archived specs with `openspec validate`.
