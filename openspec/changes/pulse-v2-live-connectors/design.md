# Design

## Context

Phase 1 delivered the connector seam this change plugs into (baseline design.md decision 6, task 5.4). It lives in `backend/src/main/java/com/pulse/platform/ingest/`:
- `ConnectorPort`: `tool()`, `source()`, `live()`, `fetchChanges(since)`.
- `ConnectorRegistry`.
- `IngestService`: the single, idempotent write path. It upserts by (source, external_id) and never deletes.
- `SeedConnector`.

Ingest tables:
- GitHub feeds `pull_requests` and `commit_days`.
- Jira feeds `issues` and the base `sprints` table.
- `pull_requests`, `commit_days` and `issues` are defined in `backend/modules/work/migrations/V200__work_data_layer.sql`; `sprints` is in `V100`.

Every baseline rule still applies:
- the Vert.x & Java Coding Standards (baseline decision 1, `openspec/config.yaml`)
- module self-registration (decision 10)
- formulas only in `calc/`, read through `CalcService` (decision 5)
- the oracle rule

## Goals / Non-Goals

**Goals:**
- **GitHub first.** Real GitHub pull requests and commit days appear beside seed data on every screen, with the same numbers on every screen.
- **Jira as a plug-in.** Adding Jira afterwards means adding one connector package, its fixtures and its mapping, with **no change to platform code, the Integrations UI or the sync orchestrator**. The same holds for any later tool.
- **Safe credentials.** Credentials are encrypted at rest and never returned or logged. Only GET requests go out, only to the hosts the connector declares.
- **Phase 1 unchanged.** No Phase 1 contract or formula changes, and the Phase 1 acceptance still passes.

**Non-Goals:**
- Webhooks, scheduled polling, OAuth, other live tools, write calls, key rotation, KMS.
- A three-track split. One owner builds this in order.

## Decisions

### 1. Live connector contract (what a tool must provide)

```java
public interface LiveConnector extends ConnectorPort
{
    // live() returns true; tool() is the tile id ("gh", "jira"); source() is the row source ("github", "jira")
    JsonObject configSchema();       // non-secret fields: name, label, type, required, pattern, help
    JsonObject secretSpec();         // label, help text (token type, where to create it), format check
    List<HostRule> allowedHosts();   // exact host, or a host suffix that is checked against the config (e.g. site URL)
    JsonArray requiredScopes();      // shown on the tile; used for the write-scope warning when the tool reports scopes
    Future<JsonObject> testConnection(LiveContext ctx);                       // {ok, status, account, scopes, scopeNote, expiresAt, hint}
    Future<JsonObject> fetchPages(LiveContext ctx, String since,
                                  Function<JsonObject, Future<Void>> sink);  // resolves to {cursor}
}
```

- `LiveContext` gives the connector four things:
  - its validated config
  - its decrypted secret, for the duration of the call only
  - `http()`, the guarded client of decision 3, already bound to the connector's allowed hosts
  - `accounts()`, the person resolver of decision 5
- **Batches.** Each batch handed to the sink uses the existing `ConnectorPort` batch format: entities, records and `$ref`.
- **`fetchChanges(since)`.** It is still implemented. It collects every page into one batch, so the Phase 1 contract is unchanged.
- **No tool-specific code outside a connector.** The connector is the only place that knows URLs, auth headers, paging or field names.
- **Registration.** Connectors live in a new module, `backend/modules/connectors/`, one package per tool (`github/`, `jira/`). The module's descriptor registers them through `ModuleContext.connectors().register(...)`. Platform code never imports a connector.
- *Why:* adding Jira, or any tool later, is then a new package plus fixtures. The tile, settings form, test, save, sync, logs, failure states, unmatched-accounts panel and secrets all work unchanged.

### 2. Secrets (generic per tool)

- `SecretBox` encrypts with AES-256-GCM: a random 96-bit IV per secret, and `PULSE_SECRET_KEY` (32 bytes, base64) taken from the environment.
- `integration_connections` has one row per live tool:
  - tool
  - config jsonb (validated against `configSchema`)
  - secret_ciphertext
  - secret_iv
  - secret_last4
  - expires_at
  - state
  - rotated_at
  - version
- A save is refused when the key is missing.
- A secret that cannot be decrypted blocks the sync with "Sync failed · key", and no request is sent.
- Secrets never appear in responses, logs, the sync log, the audit log or error messages. Redaction covers the `Authorization` header and every field named `token`, `secret` or `password`.
- **Seeded tools** keep the Phase 1 masked-placeholder path.

### 3. Guarded outbound HTTP

`LiveContext.http()` wraps the Vert.x `WebClient` and is the only way connector code reaches the network. Its rules:
- **Methods:** GET only.
- **Hosts:** `https` only, and only the hosts in `allowedHosts()`. A host suffix rule (Jira's `.atlassian.net`) is matched against the configured site URL on save and re-checked on every request.
- **Redirects:** never followed; a 3xx is a failure.
- **Timeouts:** 5 s to connect, 15 s to read.
- **Response size:** capped at 5 MB.
- **Logging:** tool, path and status only.

It does not retry. A 429 stops the sync.

### 4. Sync orchestration (generic)

`POST /api/v1/integrations/{tool}/sync` works for any registered live connector. It runs these steps:
1. Takes a per-tool lock: a conditional update of `sync_state.status` to `running`. A second request gets 409.
2. Reads the cursor from `sync_state`.
3. Calls `fetchPages(ctx, cursor, sink)`. The sink ingests each page through `IngestService` in its own transaction.
4. Writes `sync_log` with counts per entity and the outcome.
5. Advances the cursor only on success.
6. Publishes the existing `CalcService` cache-invalidation event.

**Failure.** A 401, 403, 429, 5xx, timeout or bad payload stops the run:
- pages already ingested stay, and the cursor does not move
- the tile shows "Sync failed · code"
- the credential activity log records it

Test connection and Save go through the same generic routes:
- `POST /api/v1/integrations/{tool}/test`
- `PUT /api/v1/integrations/{tool}/connection`

**Seeded tools** keep the Phase 1 simulation path.

### 5. Person matching (generic per tool)

- **Table.** `external_accounts` holds person_id (nullable), tool, tool_account_id, login, email (nullable), is_bot and first_seen_at. It is unique on (tool, tool_account_id).
- **Resolving.** Connectors call `accounts().resolve(accountId, login, email)`. The order is:
  1. an existing link
  2. a case-insensitive match on work email
  3. otherwise, an unmatched row
- **Raw account id.** Live rows keep the raw account id. Their person is resolved at read time through `external_accounts`, so linking applies from the next read with no re-sync.
- **What counts.** Per-person calculations count only rows with a person. Bots are excluded from activity.
- **UI.** The Integrations panel lists unmatched accounts for every tool, with link, unlink and bot actions.

### 6. GitHub connector (first)

**Config**
- owner and repo
- the secret is a PAT
- allowed host `api.github.com`

**Token types**
- A fine-grained PAT is recommended: Metadata, Pull requests and Contents read, on the one repo.
- A classic PAT also works and reports scopes in `X-OAuth-Scopes`.
- A fine-grained token sends no scopes header. The test then shows "Fine-grained token: permissions set on GitHub", and the 200 from `GET /repos/{owner}/{repo}` is the proof of access.
- Expiry is read from `github-authentication-token-expiration` when present.

**Test connection**
- `GET /user` and `GET /repos/{owner}/{repo}`.

**Pull requests**
- Read with `GET /repos/{owner}/{repo}/pulls?state=all&sort=updated&direction=desc&per_page=100`, paged by the `Link` header until `updated_at < cursor`.
- Capped at 1,000 per run.
- Mapped to state, opened_at, merged_at, author and `html_url`.

**Commits**
- Read with `GET /repos/{owner}/{repo}/commits?since=cursor&per_page=100`.
- Aggregated per author per day into `commit_days`, with external_id `{login}:{date}`.

**Rows written**
- `source = 'github'`, with the GitHub id as `external_id`.

**Migration V290 (additive)**
- `html_url` and `author_account_id` on `pull_requests`.
- `author_account_id` on `commit_days`.
- An index on (source, synced_at).
- Read views that resolve the person of live rows. The `calc/work` loaders read through these views; no formula changes.

### 7. Jira connector (after GitHub acceptance; no platform change)

**Config**
- site URL, email and project keys
- the story points field id
- the token expiry date, entered by hand because the API does not report it
- "ignore bot and non-assignee edits"
- allowed host suffix `.atlassian.net`, checked against the site URL

**Secret**
- Only a classic (unscoped) Atlassian API token, sent with basic auth.
- A scoped token needs `api.atlassian.com/ex/jira/{cloudId}`, which is outside the allow-list. The secret help text says so.

**Test connection**
- `GET /rest/api/3/myself`.

**Issues**
- Read with `GET /rest/api/3/search/jql`, using `project in (keys) AND updated >= cursor ORDER BY updated ASC` and following `nextPageToken`.
- The status category maps onto the seed statuses.
- Estimate and remaining time are converted from seconds to hours.
- Story points come from the configured field.
- The sprint field is upserted as a `sprints` row with `source = 'jira'`.
- With "ignore bot edits" on, last activity comes from the changelog, skipping bots and non-assignees.

**Rows written**
- `source = 'jira'`, with the issue key as `external_id`.

**Migration V291 (additive)**
- `html_url` and `assignee_account_id` on `issues`.
- A read view for `issues`.

**Proof of the plug-in claim**
- The Jira task must not edit `backend/src/` or the Integrations frontend.
- If it needs to, the platform gap is fixed in a separate task first.

### 8. Fixtures, mock server and the connector contract test

- **Mock server.** A test utility starts a local HTTP server that serves recorded JSON from `backend/src/test/resources/live-fixtures/<tool>/`.
- **Override.** Tests point a connector's allowed hosts at the mock host through a test-only override, which the production profile refuses.
- **Contract test.** `AbstractLiveConnectorContractTest` is the shared test that every connector extends with its fixtures. It checks:
  - paged 200 responses ingest
  - a re-sync gives no duplicates
  - the second run uses the cursor
  - 401, 429, 500, a stall and a 302 each fail cleanly, keeping earlier pages
  - only GET requests go to allowed hosts
  - the secret never appears in logs or responses
  - unmatched accounts land in `external_accounts`
- **Fixture source.** Fixtures are recorded once from a sandbox and sanitized: tokens, emails and names removed.

### 9. Ownership and migrations

- **Owner.** One person owns this change, and the groups run in order.
- **Directory rules.** The Phase 1 rules still hold for review. Shared files and platform code are reviewed by the Track 1 owner. The work-table migrations (V290, V291) and the `calc/work` loader changes are reviewed by the Track 2 owner.
- **Migration numbers.**
  - V190–V199 for platform tables.
  - V290–V299 for the work-table columns and views.

## Risks / Trade-offs

- **Live data changes demo numbers.** Seed and live rows are both counted. The runbook says so, and the oracle comparison runs on seed rows only.
- **Generic form limits.** A schema-driven settings form cannot express every tool-specific widget. The schema supports text, secret, URL, list of strings, date and boolean, which is enough for GitHub and Jira. A richer widget would be a platform change.
- **GitHub pull requests have no `since` filter.** The first sync of a large repository is capped at 1,000 pull requests, with a note on the tile.
- **Rate limits.** A 429 stops the sync. There is no automatic retry, so the Admin retries later.
- **Lost or changed secret key.** Stored secrets become unreadable, and the tile asks for the credentials again. Rotation is deferred.
- **Jira field variance** (custom story points field id). The field id is Admin-configured. An unknown field is stored as null, never guessed.

## Migration Plan

All migrations are forward-only and additive. Disconnecting a tool keeps every ingested row. Rollback means redeploying the previous build; the new tables, columns and views are unused by Phase 1 code.

## Open Questions

- Should a later change add bulk account linking by CSV, live evidence in appraisal and 1:1s, or a "remove live data for this tool" action?
- Do we want scheduled polling after the demo? It stays in Deferred hardening until decided.
