# Design

## Context

Phase 1 delivered the connector seam this change plugs into (baseline design.md decision 6, task 5.4). It lives in `backend/src/main/java/com/pulse/platform/ingest/`:
- `ConnectorPort`: `tool()`, `source()`, `live()`, `fetchChanges(since)`.
- `ConnectorRegistry`.
- `IngestService`: the single, idempotent write path. It upserts by (source, external_id), stamps `synced_at` and never deletes.
- `SeedConnector`.

Ingest tables:
- Jira feeds `issues` and the base `sprints` table.
- GitHub feeds `pull_requests` and `commit_days`.
- `issues`, `pull_requests` and `commit_days` are defined in `backend/modules/work/migrations/V200__work_data_layer.sql`; `sprints` is in `V100`.

**Target Jira.** The target is the internal self-hosted **Jira Server / Data Center** at `http://172.16.10.6:8080`, not Jira Cloud. It was verified to answer `200 OK` on REST API v2 with Basic auth (`username:password`). Jira is therefore built first; GitHub follows on the same platform.

## Goals / Non-Goals

**Goals:**
- **Jira first.** Real Jira Server issues and sprints appear beside seed data on every screen, with the same numbers on every screen.
- **GitHub as a plug-in.** Adding GitHub afterwards means adding one connector package, its fixtures and its mapping, with **no change to platform code, the Integrations UI or the sync orchestrator**. The same holds for any later tool.
- **Safe credentials.** Credentials are encrypted at rest and never returned or logged. Only GET requests go out, only to the origins the connector declares. A LAN `http://` Jira is allowed explicitly, without opening the rest of the internal network.
- **Phase 1 unchanged.** No Phase 1 contract or formula changes, and the Phase 1 acceptance still passes.

**Non-Goals:**
- Jira Cloud, webhooks, scheduled polling, OAuth, other live tools, write calls, key rotation, KMS.

## Decisions

### 0. Coding standards for this change (binding on every task)

The rules from `openspec/config.yaml` and baseline design.md decision 1 apply without exception:
- **Data shapes.** No POJOs, DTOs, records or entity beans. Config, secrets in transit, API payloads, normalized batches, host rules, test results and sync results are all `io.vertx.core.json.JsonObject` / `JsonArray`. The only new types are services and interfaces: `LiveConnector`, `LiveContext`, `SecretBox`, `GuardedHttp`, `ExternalAccountService`, `LiveSyncService`, and repositories.
- **Data access.** All SQL lives in repository classes that extend `BaseRepository` and use `SqlExecutor` (`queryList`, `queryOne`, `execute`, `inTransaction`) with parameterized `Tuple`s. There is no SQL in handlers, services or connectors.
- **Ingest tables.** They are written only through `IngestService`, with `source = 'jira'` or `'github'`, the tool's id as `external_id`, and `synced_at` stamped by the service.
- **Event loop.** Nothing blocks it:
  - HTTP goes through the Vert.x `WebClient` (async).
  - AES-GCM, base64 key decoding and large JSON mapping run in `vertx.executeBlocking(() -> ...)` when they can take time.
  - Futures are composed with `.compose(...)` and `Future.all(...)`, never nested callbacks.
- **Errors.** Route failures go through `ctx.fail(status, exception)` to the central `FailureHandler`.
- **Verticles.** There are still only two verticle classes; connectors are plain classes registered by a module.
- **Style.** Allman braces (opening `{` on its own line for classes, methods, if/else, loops and try/catch), with blank lines between logical statements and blocks.

### 1. Live connector contract (what a tool must provide)

```java
public interface LiveConnector extends ConnectorPort
{
    // live() returns true; tool() is the tile id ("jira", "gh"); source() is the row source ("jira", "github")

    JsonObject configSchema();       // non-secret fields: name, label, type, required, pattern, help

    JsonObject secretSpec();         // secret label/help, auth modes (e.g. basic | bearer), format check

    JsonArray allowedOrigins(JsonObject config);   // [{"scheme","host","port"}] derived from config or fixed

    JsonArray requiredScopes();      // shown on the tile; used for the write-scope warning when the tool reports scopes

    Future<JsonObject> testConnection(LiveContext ctx);       // {ok, status, account, scopes, scopeNote, fields, expiresAt, hint}

    Future<JsonObject> fetchPages(LiveContext ctx, String since,
                                  Function<JsonObject, Future<Void>> sink);   // resolves to {"cursor": ...}
}
```

- **`LiveContext`** is a service, not a data bean. It gives the connector:
  - `config()`: a `JsonObject`
  - `secret()`: decrypted only for the duration of the call
  - `http()`: the guarded client of decision 3, bound to `allowedOrigins(config)`
  - `accounts()`: the person resolver of decision 5
- **Batches.** Each batch handed to the sink uses the existing `ConnectorPort` batch format (entities, records, `$ref`).
- **`fetchChanges(since)`.** It is still implemented. It collects every page into one batch, so the Phase 1 contract is unchanged.
- **No tool-specific code outside a connector.** The connector is the only place that knows URLs, auth headers, paging or field names.
- **Registration.** Connectors live in a new module, `backend/modules/connectors/`, one package per tool: `jira/` first, then `github/`. The module's descriptor registers them through `ModuleContext.connectors().register(...)`. Platform code never imports a connector.

### 2. Secrets (generic per tool)

- **`SecretBox`.** It encrypts with AES-256-GCM: a random 96-bit IV per secret, and `PULSE_SECRET_KEY` (32 bytes, base64) taken from the environment.
- **What it encrypts:**
  - for Jira, the password, or the PAT when the Bearer mode is chosen
  - for GitHub, the PAT
- **`integration_connections`** has one row per live tool. Columns:
  - tool
  - config jsonb (non-secret, validated against `configSchema`). For Jira this holds base URL, auth mode, username, project keys, field ids and the bot switch.
  - secret_ciphertext
  - secret_iv
  - secret_last4
  - expires_at (optional, entered by the Admin for Jira)
  - state
  - rotated_at
  - version
- **Refusals.**
  - A save is refused when the key is missing.
  - A secret that cannot be decrypted blocks the sync with "Sync failed · key", and no request is sent.
- **Redaction.** Secrets never appear in responses, logs, the sync log, the audit log or error messages. Redaction covers the `Authorization` header and every field named `password`, `token` or `secret`.
- **Seeded tools.** They keep the Phase 1 masked-placeholder path.

### 3. Guarded outbound HTTP and URL validation (SSRF)

`LiveContext.http()` wraps the Vert.x `WebClient` and is the only way connector code reaches the network.

**Rules on every request**
- **Methods:** GET only.
- **Origin:** the request's scheme, host and port must exactly equal one of `allowedOrigins(config)`.
  - Jira declares exactly the origin of its configured base URL, which may be `http://` or `https://` and may be a LAN IP or host.
  - GitHub declares `https://api.github.com:443`.
  - Every other origin is refused, including other private, LAN and loopback addresses.
- **Path prefix:** a configured context path (e.g. `/jira`) is kept as a prefix, and requests may not climb out of it with `..`.
- **Redirects:** never followed (`setFollowRedirects(false)`). A 3xx is a failure, so a redirect to another internal host is never reached.
- **DNS:** the host is re-resolved per request. The connection is refused if the resolved address falls in loopback (`127.0.0.0/8`, `::1`), link-local or cloud metadata ranges (`169.254.0.0/16`, `fe80::/10`) or `0.0.0.0`, unless the configured base URL literally names it. This guards against DNS rebinding to metadata endpoints. RFC 1918 LAN ranges are allowed only for the exact configured origin.
- **Timeouts:** 5 s to connect, 15 s to read.
- **Response size:** capped at 10 MB, enough for `expand=changelog` pages.
- **Logging:** tool, path and status only.
- **Retries:** none. A 429 or 5xx stops the sync.

**Base URL validation on save (Jira)**
- The scheme is `http` or `https`.
- No user info, query or fragment.
- A trailing slash is normalized.
- An `http://` URL is accepted with a visible warning that credentials and data travel unencrypted on the network.

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

- **Table.** `external_accounts` holds:
  - person_id (nullable)
  - tool
  - tool_account_id
  - login
  - display_name
  - email (nullable)
  - is_bot
  - first_seen_at

  It is unique on (tool, tool_account_id).
- **Resolving.** Connectors call `accounts().resolve(JsonObject {accountId, login, displayName, email})`. The order is:
  1. an existing link
  2. a case-insensitive match of `email` against `people.email`
  3. otherwise, an unmatched row
- **Jira identifier.** For Jira Server the account id and login are the Jira username (`name`), and the email is `emailAddress` when the instance exposes it.
- **Raw account id.** Live rows keep the raw account id. Their person is resolved at read time through `external_accounts`, so linking applies from the next read with no re-sync.
- **What counts.** Per-person calculations count only rows with a person. Bots are excluded from activity.
- **UI.** The Integrations panel lists unmatched accounts for every tool, with link, unlink and bot actions.
- **Later.** A bulk CSV mapping is a later follow-up; the username stored here is what it would match on.

### 6. Jira Server / Data Center connector (first)

**Config (`configSchema`)**
- Base URL, for example `http://172.16.10.6:8080`.
- Auth mode: `basic` (default) or `bearer`.
- Username: required for `basic`.
- Project Keys.
- Story points field id and sprint field id: proposed by field discovery and confirmed by the Admin.
- Optional Expiry Date.
- The "ignore bot and non-assignee edits" switch.

**Secret (`secretSpec`)**
- "Password / Token".
- Basic sends `Authorization: Basic base64(username:password)`; the base64 encoding happens in the connector, from the decrypted secret.
- Bearer sends `Authorization: Bearer <token>` (a Jira Data Center personal access token).

**Allowed origins**
- Exactly the scheme, host and port of the base URL.

**Test connection**
- `GET /rest/api/2/myself`: username, display name and email.
- Then `GET /rest/api/2/field`: custom fields whose name or schema match story points (`Story Points`, `Story point estimate`) and sprint (`com.pyxis.greenhopper.jira:gh-sprint`) are returned as proposals.
- The test passes only when both calls answer 200.

**Fetch pages**
- `GET /rest/api/2/search` with these parameters:
  - `jql=project in (<keys>) AND updated >= "<cursor yyyy-MM-dd HH:mm>" ORDER BY updated ASC`
  - `startAt` and `maxResults=100`
  - `expand=changelog`
  - `fields=` limited to what is mapped
- It pages until `startAt + issues.length >= total`.
- **Cursor.** The cursor is the greatest `fields.updated` seen. Jira JQL has minute precision, so the next run uses a one-minute overlap, and the idempotent upsert absorbs the repeats.

**Mapping (`JsonObject` → normalized batch)**
- Issue key becomes `external_id`.
- `fields.status.statusCategory.key` (`new`, `indeterminate`, `done`) maps onto the seed statuses.
- `timeoriginalestimate` and `timeestimate` are converted from seconds to hours.
- Story points come from the configured field.
- The sprint field is upserted as a `sprints` row with `source = 'jira'` and referenced by `$ref`. Jira Server returns the sprint either as an array of strings (`com.atlassian.greenhopper.service.sprint.Sprint@...[id=..,name=..,state=..,startDate=..,endDate=..]`, parsed) or as objects, depending on version; both are handled.
- `html_url` is `<baseUrl>/browse/<key>`.
- `fields.description` and comment bodies are **plain text / Jira wiki markup strings** (v2 format). They are stored as text, with no ADF parsing.
- Assignee: `assignee.name` and `assignee.emailAddress` go through `accounts().resolve`.
- Last activity: with the bot switch on, it is the latest changelog `created` time whose `author` is neither a bot nor a non-assignee. Changelog authors are resolved by `author.name` and `author.emailAddress`.

**Rows written**
- `source = 'jira'`.

**Migration V290 (additive)**
- `html_url` and `assignee_account_id` on `issues`.
- An index on (source, synced_at).
- A read view that resolves the person of live issues.
- The `calc/work` issue loaders read through this view; no formula changes.

### 7. GitHub connector (after Jira acceptance; no platform change)

**Config**
- owner and repo
- the secret is a PAT; `secretSpec` recommends a fine-grained, read-only PAT on one repo
- allowed origin `https://api.github.com:443`

**Token types**
- A classic token reports scopes in `X-OAuth-Scopes`.
- A fine-grained token sends no scopes header, so the test shows "Fine-grained token: permissions set on GitHub".
- Expiry is read from `github-authentication-token-expiration`.

**Test connection**
- `GET /user` and `GET /repos/{owner}/{repo}`.

**Pull requests**
- Read with `pulls?state=all&sort=updated&direction=desc&per_page=100`, paged by `Link` until `updated_at < cursor`.
- Capped at 1,000 per run.

**Commits**
- Read with `commits?since=cursor&per_page=100`.
- Aggregated per author per day into `commit_days`, with external_id `{login}:{date}`.

**Rows written**
- `source = 'github'`.

**Migration V291 (additive)**
- `html_url` and `author_account_id` on `pull_requests`.
- `author_account_id` on `commit_days`.
- Read views that resolve the person of live rows.

**Proof of the plug-in claim**
- The GitHub task must not edit `backend/src/` or the Integrations frontend.
- If it needs to, the platform gap is fixed in a separate task first.

### 8. Fixtures, mock server and the connector contract test

- **Mock server.** A test utility starts a local HTTP server that serves recorded JSON from `backend/src/test/resources/live-fixtures/<tool>/`.
- **Origin.** Because Jira allows an `http://` LAN origin, the mock server's `http://127.0.0.1:<port>` origin is configured like any Jira base URL. A test-only flag allows a loopback origin; the production profile refuses that flag.
- **Contract test.** `AbstractLiveConnectorContractTest` is the shared test that every connector extends with its fixtures. It checks:
  - paged 200 responses ingest
  - a re-sync gives no duplicates
  - the second run uses the cursor
  - 401, 429, 500, a stall and a 302 to another host each fail cleanly, keeping earlier pages
  - only GET requests go to the declared origin
  - the secret never appears in logs or responses
  - unmatched accounts land in `external_accounts`
- **Fixture source.** Jira fixtures are recorded once from the internal instance (`/myself`, `/field`, two `/search` pages with changelog) and sanitized: passwords, emails and names removed.

### 9. Parallel build: three tracks, disjoint files

The split follows Phase 1 directory ownership, so the tracks never edit the same files:

| Track | Group | Migrations | Owns |
|---|---|---|---|
| 1 | 24 | `V190–V199` | `backend/src/` platform (secrets, guarded HTTP, live registry, sync orchestrator, external accounts, mock server and contract test kit), `modules/integrations` (backend and frontend), `modules/users`, `docs/`, CODEOWNERS |
| 2 | 25 | `V290` (Jira), `V291` (GitHub) | work-table columns and views, `calc/work` loaders, `modules/work`, `pull_requests`, `utilization`, `effort` screens and endpoints |
| 3 | 26 | none (`V390–V399` reserved) | new module `backend/modules/connectors/` (`jira/`, `github/`), `backend/src/test/resources/live-fixtures/`, Phase 2 Playwright flows |

**Day 1 contract freeze**
- Track 1 lands the following; the other tracks start against stubs and rebase onto it:
  - the `ExternalAccountService` interface and a stub
  - `LiveConnector`, `LiveContext` and the registry hook
  - the empty connectors module with its CODEOWNERS lines
  - the mock server and the contract test skeleton
- Track 2 lands the V290 column names Track 3 writes into.

**Jira first**
- Each track finishes its Jira tasks before its GitHub tasks.
- GitHub work merges only after Jira acceptance (27.1, 27.2).

**Cross-track boundaries**
- Connectors (Track 3) call only `LiveContext` and `IngestService`. They never import Track 2 code.
- Track 2 reviews Track 3's mappings.
- Track 1 merges the docs sections the other tracks write.

## Risks / Trade-offs

- **Plain `http` to Jira.** The Basic auth password and issue data cross the LAN unencrypted.
  - Accepted for the internal demo instance.
  - The form warns about it, the runbook recommends `https` or a Bearer PAT with limited rights, and a read-only Jira user is required.
- **Allowing a private origin.** The guard allows only the one configured origin, follows no redirects and blocks metadata and loopback addresses. An Admin can still point Pulse at another LAN service, which is why the setting is Admin-only and audited.
- **Jira Server version differences.** The sprint field format differs (string versus object), and so do field names. Both formats are parsed; unknown fields are stored as null, never guessed.
- **JQL minute precision.** A one-minute overlap on the cursor, plus idempotent upserts, avoids missing updates.
- **Live data changes demo numbers.** Seed and live rows are both counted. The runbook says so, and the oracle comparison runs on seed rows only.
- **Lost or changed secret key.** Stored secrets become unreadable, and the tile asks for the credentials again. Rotation is deferred.

## Migration Plan

All migrations are forward-only and additive. Disconnecting a tool keeps every ingested row. Rollback means redeploying the previous build; the new tables, columns and views are unused by Phase 1 code.

## Open Questions

- Can the internal Jira be put behind `https` before any real (non-demo) use?
- Should a later change add bulk account linking by CSV (matching on Jira username or GitHub login), live evidence in appraisal and 1:1s, or a "remove live data for this tool" action?
- Do we want scheduled polling after the demo? It stays in Deferred hardening until decided.
