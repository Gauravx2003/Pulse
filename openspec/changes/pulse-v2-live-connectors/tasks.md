# Tasks: Phase 2: live read-only connectors (GitHub first, then Jira)

## Ground rules

- **Owner and order.** One owner works through the groups in order. Start only after Phase 1 acceptance (baseline 23.1 to 23.4) has passed on seed data.
- **Generic platform first.** Group 24 builds a platform with no GitHub-specific code. Group 25 adds GitHub.
- **Jira gate.** Group 27 (Jira) starts only after GitHub acceptance (26.3) passes. It must not edit `backend/src/` or `frontend/src/modules/integrations/`. If it needs to, fix the platform gap in a separate task first (design.md decision 7).
- **Phase 1 unchanged.** Migrations are additive only:
  - V190–V199 for platform tables
  - V290–V299 for work-table columns and views

  No Phase 1 table, column, endpoint, response shape, formula or threshold changes.
- **Write path.** Every write into an ingest table goes through `IngestService`. Connectors never write SQL.
- **Network.** Every outbound request goes through `LiveContext.http()`: GET only, and only to the connector's allowed hosts. Automated tests use the mock server only. Live smoke tests (26.4, 27.5) are manual.
- **Coding standards.** Every task that adds a handler, service or repository must follow the Vert.x & Java Coding Standards in baseline `design.md` (decision 1).
- **Reviews.** Platform and shared files are reviewed by the Track 1 owner. Work-table migrations and `calc/work` loader changes are reviewed by the Track 2 owner.
- **PR checklist** (from Phase 1), plus these items:
  - "no secret in any log, response or audit entry"
  - "no outbound call outside `LiveContext.http()`"
  - "no Phase 1 contract changed"
  - "no tool-specific code outside `backend/modules/connectors/<tool>/`"

---

## 24. Generic live connector platform

- [ ] 24.1 **Migration V190 and external accounts.**
  - Create three tables:
    - `external_accounts`: id, person_id nullable, tool, tool_account_id, login, email nullable, is_bot, first_seen_at, timestamps; unique on (tool, tool_account_id)
    - `sync_state`: tool primary key, cursor, last_run_at, status, last_error_code
    - `integration_connections`: tool primary key, config jsonb, secret_ciphertext, secret_iv, secret_last4, expires_at, state, rotated_at, version
  - Implement `ExternalAccountService` in `backend/src/`, with these operations:
    - `resolve(tool, accountId, login, email)`: an existing link, else a match on work email (case-insensitive), else an unmatched row
    - `link` and `unlink`
    - `listUnmatched(tool?)`
    - `setBot`
  - Add Admin endpoints under `/api/v1/integrations/accounts` for those operations, with audit entries.
  - Verify:
    - the migration applies after V300
    - every Phase 1 test passes unchanged
    - a second link of the same account returns 409
    - forbidden and nonexistent records get the same response
    - Admin-only role matrix
- [ ] 24.2 **Secret storage.**
  - Implement `SecretBox`:
    - AES-256-GCM with a random 96-bit IV
    - key from `PULSE_SECRET_KEY` (32 bytes, base64), decoded off the event loop
  - Store live-tool secrets in `integration_connections`. Seeded tools keep the Phase 1 masked placeholder.
  - Redact the `Authorization` header and every `token`, `secret` and `password` field in logs, errors and audit entries.
  - Verify:
    - after a save, the database dump and the captured logs hold no plain token
    - save is refused without the key, with a 422 message
    - a tampered ciphertext fails to decrypt and blocks the sync with "key"
    - API responses carry only `last4`
- [ ] 24.3 **Live connector contract and guarded HTTP.**
  - Add `LiveConnector` and `LiveContext` (design.md decision 1), with `ModuleContext.connectors().register(...)`.
  - `LiveContext.http()` wraps the Vert.x `WebClient` and enforces:
    - GET only
    - `https` only, to the connector's `allowedHosts()`; suffix rules are checked against the config and re-checked on every request
    - no redirects
    - 5 s connect and 15 s read timeouts
    - a 5 MB response cap
    - log lines with tool, path and status only
  - Create the empty `backend/modules/connectors/` module that will hold one package per tool.
  - Verify:
    - a host that is not allowed, an `http://` URL, a 302 and a POST are each rejected before any request leaves the process
    - a stall hits the read timeout
    - a stub connector registered from a test module is listed by the registry
- [ ] 24.4 **Mock server and connector contract test.**
  - Add a test utility that serves recorded fixtures from `backend/src/test/resources/live-fixtures/<tool>/`. Its test-only host override is refused in the production profile.
  - Add `AbstractLiveConnectorContractTest`, which covers:
    - paged ingest
    - no duplicates on a re-sync
    - cursor use on the second run
    - 401, 429, 500, a stall and a 302, each keeping earlier pages
    - only GET requests to allowed hosts
    - no secret in logs or responses
    - unmatched accounts recorded
  - Start `docs/live-connectors.md`, covering the contract, the fixture format and a step-by-step "how to add a connector".
  - Verify: a stub connector extending the contract test passes every case.
- [ ] 24.5 **Generic Test connection, Save and Disconnect routes.** They work for any registered live connector.
  - `POST /api/v1/integrations/{tool}/test`:
    - validates the submitted values against `configSchema` and `secretSpec`
    - calls `testConnection`
    - returns `{ok, status, account, scopes, scopeNote, expiresAt, hint}`
    - passes only on HTTP 200
    - warns when reported scopes exceed `requiredScopes()`
  - `PUT /api/v1/integrations/{tool}/connection` saves only with a passing test proof: single use, valid for 10 minutes, bound to the tool and a hash of the values.
  - Disconnect deletes the secret, keeps every ingested row and writes the credential activity log.
  - Verify with a stub connector and the mock server:
    - 200 passes
    - 401 fails with the status and the connector's hint, Save stays disabled and stored values are unchanged
    - values changed after the test cannot be saved
    - the token appears in no response
- [ ] 24.6 **Generic Sync now orchestrator.** `POST /api/v1/integrations/{tool}/sync` runs these steps:
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
  - a 429 on page 2 keeps page 1 and leaves the cursor unchanged
  - seed rows are identical before and after a failed and a successful sync
  - a concurrent call gets 409
  - the calculation cache is invalidated
- [ ] 24.7 **Integrations frontend** (`frontend/src/modules/integrations/`), generic for any live connector:
  - `CredentialsModal.vue` renders its fields from `configSchema` and `secretSpec`: text, secret, URL, list of strings, date, boolean. No tool-specific code.
  - It runs the real Test connection, shows the account, the scopes or scope note, the expiry and any warning, and enables Save only after a pass. Secrets are never pre-filled.
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
  - unlinks and pseudonymizes that person's `external_accounts` (login and email cleared)
  - leaves live rows with a null person, so team aggregates stay
  - lists this in the deletion dialog

  Verify:
  - no login or email remains for the deleted person
  - team totals are unchanged

## 25. GitHub connector

- [ ] 25.1 **Migration V290 (additive).**
  - Add `html_url` and `author_account_id` to `pull_requests`, and `author_account_id` to `commit_days`.
  - Add indexes on (source, synced_at).
  - Add read views that resolve the person of live rows through `external_accounts`; seed rows keep their stored person.
  - Point the `calc/work` loaders for PRs and commit days at the views, with no formula change.
  - Verify:
    - all Phase 1 PR, Effort, Utilization and Discipline tests pass
    - `FullOracleTest` is unchanged
- [ ] 25.2 **Record GitHub fixtures.** Record them once from a sandbox repo, then sanitize: tokens, emails and names removed. Include:
  - `/user`
  - `/repos/{o}/{r}` with classic-token and fine-grained-token headers
  - two pages each of pulls and commits
  - 401, 403, 429 and 500

  Verify: the fixtures load in the mock server, and a grep finds no token or real email.
- [ ] 25.3 **`GitHubConnector`** in `backend/modules/connectors/github/`, registered by the connectors module descriptor.
  - **Config:** owner and repo; `secretSpec` describes the fine-grained PAT (Metadata, Pull requests and Contents read).
  - **Allowed host:** `api.github.com`.
  - **`testConnection`:**
    - `GET /user` and `GET /repos/{owner}/{repo}`
    - scopes from `X-OAuth-Scopes` when present, otherwise the scope note "Fine-grained token: permissions set on GitHub"
    - expiry from `github-authentication-token-expiration`
  - **`fetchPages`:**
    - pull requests from `pulls?state=all&sort=updated&direction=desc&per_page=100`, paged by `Link` until `updated_at` < cursor, capped at 1,000 per run
    - commits from `commits?since=cursor&per_page=100`, aggregated into one `commit_days` record per author per day with external_id `{login}:{date}`
    - authors resolved through `accounts().resolve`
    - `source = 'github'`
  - Verify:
    - `GitHubConnectorContractTest extends AbstractLiveConnectorContractTest` passes
    - three commits on one day give one row with a count of 3
    - a classic token lists scopes, and a fine-grained token shows the scope note
    - an unknown author is stored with a null person and appears in the unmatched list
- [ ] 25.4 **Mapping record.** Document the GitHub → Pulse mapping (PR state, times, author, commit days) in `docs/live-connectors.md`, and add a test that a live PR and a live commit day give the same `CalcService` input shape as their seed equivalents. Verify: the Track 2 owner approves the mapping in review, and the shape test passes.

## 26. GitHub on the screens and GitHub acceptance

- [ ] 26.1 **PR screens.**
  - Add a source badge (Seed / GitHub) and an "Open in GitHub" link from `html_url` on the PR list and PR detail.
  - Add `source` and `html_url` to responses as new fields only.
  - Links open in a new tab with `rel="noopener noreferrer"`.
  - Verify:
    - UI audit and axe are clean
    - PR standard checks give the same results for live and seed PRs of the same shape
- [ ] 26.2 **Unmatched authors in per-person figures.**
  - PR, Effort and Utilization figures count a live row for a person only when the account is linked.
  - Team totals include unmatched rows.
  - Verify:
    - a link-then-read test shows the person's figures change with no re-sync
    - the baseline 23.2 cross-screen consistency test passes with GitHub rows present
- [ ] 26.3 **GitHub acceptance.** After a mock-server GitHub sync, re-run:
  - the baseline 23.1 contract matrix
  - the 23.2 oracle on seed rows and the cross-screen test
  - the 23.4 UI audit

  Add a Playwright flow:
  1. Admin connects GitHub: Test passes, Save, Sync now.
  2. The PR appears with a GitHub badge.
  3. An unmatched login is linked, and the person's figures update.
  4. A 401 gives "Sync failed · 401", and Fix opens Change credentials.
  5. A concurrent Sync now gets 409.
  6. Disconnect keeps the rows.

  Verify that nothing regresses.
- [ ] 26.4 **GitHub security pass and live smoke test.**
  - Grep the logs, database dump and API responses of the e2e run for the test token: zero hits.
  - Confirm every outbound request was a GET to `api.github.com` with no redirect followed.
  - Run `/security-review` on the diff and fix every finding.
  - Run the manual smoke test once with a real fine-grained PAT on the demo repo.
  - Update `docs/demo.md`:
    - how to create the PAT
    - `PULSE_SECRET_KEY` generation
    - how to link demo users to real accounts
    - that seed and live rows are both counted
    - the smoke test record

## 27. Jira connector (start only after 26.3 passes; no edits to `backend/src/` or the Integrations frontend)

- [ ] 27.1 **Migration V291 (additive).**
  - Add `html_url` and `assignee_account_id` to `issues`, and a read view that resolves the person of live issues.
  - Point the `calc/work` issue loaders at the view, with no formula change.
  - Verify: the Phase 1 Work, Utilization and Discipline tests and `FullOracleTest` are unchanged.
- [ ] 27.2 **Record Jira fixtures** from a sandbox site and sanitize them. Include:
  - `/myself`
  - two `search/jql` pages with `nextPageToken`
  - an issue with a sprint, story points and time tracking
  - a changelog with a bot edit
  - 401, 429 and 500

  Verify: the fixtures load in the mock server and contain no token or real email.
- [ ] 27.3 **`JiraConnector`** in `backend/modules/connectors/jira/`.
  - **Config:** site URL, email, project keys, story points field id, token expiry date, "ignore bot and non-assignee edits".
  - **Secret:** `secretSpec` explains that only a classic (unscoped) API token works.
  - **Allowed host:** the suffix `.atlassian.net`, checked against the site URL.
  - **`testConnection`:** `GET /rest/api/3/myself`, using basic auth.
  - **`fetchPages`:**
    - `search/jql` with `project in (keys) AND updated >= "cursor" ORDER BY updated ASC`, following `nextPageToken`
    - status category mapped onto the seed statuses
    - estimates converted from seconds to hours
    - story points from the configured field
    - the sprint field upserted into `sprints` with `source = 'jira'` and linked by `$ref`
    - assignees resolved through `accounts().resolve`
    - with "ignore bot edits" on, last activity taken from the changelog
    - `source = 'jira'`, with the issue key as `external_id`
  - Verify:
    - `JiraConnectorContractTest extends AbstractLiveConnectorContractTest` passes
    - an incremental run fetches only issues updated since the cursor
    - a hidden-email assignee lands in the unmatched list
    - a bot-only edit leaves last activity unchanged
    - an unknown story points field is stored as null
    - a site URL outside `.atlassian.net` is rejected on save
    - **`git diff` of this task touches no file under `backend/src/` or `frontend/src/modules/integrations/`**
- [ ] 27.4 **Work item screens.**
  - Add a source badge (Seed / Jira), an "Open in Jira" link and a source filter on Work items and the issue drawer.
  - Rebalance suggestions link to the Jira issue.
  - Unmatched assignees stay out of per-person figures until they are linked.
  - Add the Jira mapping to `docs/live-connectors.md`.
  - Verify:
    - UI audit and axe are clean
    - the link-then-read test passes
    - no write request reaches Jira
- [ ] 27.5 **Jira acceptance.**
  - Re-run 26.3 with seed, GitHub and Jira rows together, plus a Jira Playwright flow: sync, workload, Discipline, index and the appraisal draft score all move consistently.
  - Repeat the 26.4 security pass for `*.atlassian.net`.
  - Run the manual smoke test with a real classic API token.
  - Update `docs/demo.md` for Jira: the API token, field ids and project keys.

## 28. Close

- [ ] 28.1 Review the "Deferred hardening" list (baseline design.md) and this change's Open Questions. Then archive `pulse-v1-baseline` and `pulse-v2-live-connectors`, in that order, and verify the archived specs with `openspec validate`.
