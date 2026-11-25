# Live connectors

This guide covers Phase 2's live, read-only connectors (change `pulse-v2-live-connectors`): the
contract a connector implements, how its fixtures are recorded and served, and how to add one step by
step. Track 1 owns this file. Tracks 2 and 3 write their sections (the per-tool mappings) and send
them to Track 1 to merge.

Everything named here lives in `backend/src/main/java/com/pulse/platform/live/` or in the test kit
`backend/src/test/java/com/pulse/platform/live/testkit/`. The Javadoc of each class has more detail.

Ground rules (design.md decision 0):

- `JsonObject` / `JsonArray` only. No POJOs, DTOs, records or entity beans.
- A connector never writes. It hands normalized batches to the platform's sink, and the platform
  writes them through `IngestService` (`source`, `external_id`, `synced_at`).
- A connector reaches the network only through `LiveContext.http()`: GET only, only to the origins it
  declares, no redirects.
- Nothing blocks the event loop. Compose futures with `.compose(...)` and `Future.all(...)`.
- Allman braces, with blank lines between logical statements and blocks.
- No tool-specific code outside `backend/modules/connectors/<tool>/`.

## 1. The contract

A live connector implements `LiveConnector` (which extends the Phase 1 `ConnectorPort`):

| Method | Returns | Notes |
|---|---|---|
| `tool()` | tile id: `"jira"`, `"gh"` | lower case, `[a-z0-9_-]` |
| `source()` | row source: `"jira"`, `"github"` | never `"seed"` |
| `configSchema()` | `{fields: [{name, label, type, required, pattern, help, options, dependsOn}]}` | non-secret fields; type is text, url, list, date, boolean or choice. The Integrations form is rendered from it. |
| `secretSpec()` | `{name, label, help, authModes, pattern}` | the one secret (password, token) |
| `allowedOrigins(config)` | `[{scheme, host, port, pathPrefix?}]` | Jira: exactly the base URL's origin (`BaseUrls.origin(url)`). GitHub: fixed. |
| `requiredScopes()` | `["..."]` | shown on the tile; empty when the tool reports none |
| `testConnection(ctx)` | `{ok, status, account, scopes, scopeNote, fields, expiresAt, hint}` | `ok` only when every call answered 200. A 401 resolves with `ok: false` and a hint; it does not fail the future. |
| `fetchPages(ctx, since, sink)` | `{cursor}` | hands each page to `sink` as a batch and asks for the next page only after the sink's future succeeds. A failed page fails the whole read. |

`LiveContext` gives the connector, for one call only:

- `config()`: the validated non-secret config
- `secret()`: the decrypted secret. Never log it, never put it in a batch, a result or an exception.
- `http()`: the guarded client (`GuardedHttp`). `get(url, headers)` resolves for any non-3xx status;
  `getOk(url, headers)` fails with `LiveHttpException` outside 2xx. Answers are
  `{status, headers: {lower-case name: value}, body}` with `body` already parsed as JSON.
- `accounts()`: person matching. Call `resolve(tool(), {accountId, login, displayName, email})` for
  every assignee and author you see; unmatched accounts land in the Integrations panel.

A batch is the Phase 1 `ConnectorPort` format:

```json
{
  "source": "jira",
  "entities": [
    { "entity": "sprints", "records": [ { "external_id": "42", "name": "Sprint 7" } ] },
    { "entity": "issues",  "records": [ { "external_id": "PROJ-1",
                                          "sprint_id": { "$ref": "sprints", "external_id": "42" } } ] }
  ]
}
```

Entities are applied in order, so parents come before children. Record fields are column names.

**Failures.** Let `LiveHttpException` propagate: its `code()` (`401`, `429`, `500`, `timeout`,
`redirect`, `bad_payload`...) is what the tile shows after "Sync failed · ". Do not retry: a 429 or
5xx stops the sync, and the pages already handed to the sink stay.

**Registration.** The connectors module descriptor registers each connector from `start(ctx)`:

```java
@Override
public void start(ModuleContext ctx)
{
    ctx.connectors().register(new JiraConnector());
}
```

Platform code never imports a connector.

## 2. Fixtures and the mock server

Automated tests never call a real tool. `LiveMockServer` serves recorded answers on
`http://127.0.0.1:<port>` from the test classpath folder
`backend/src/test/resources/live-fixtures/<tool>/`. Track 3 owns `jira/` and `github/`; `stub/` is
the platform's own example.

### `routes.json`

```json
{
  "routes": [
    { "path": "/rest/api/2/myself", "file": "myself.json" },
    { "path": "/rest/api/2/field",  "file": "field.json" },
    { "path": "/rest/api/2/search", "query": { "startAt": "0" },   "file": "search.json" },
    { "path": "/rest/api/2/search", "query": { "startAt": "100" }, "file": "search-page2.json",
      "status": 200, "headers": { "X-Custom": "value" } }
  ],
  "errors": {
    "401": { "file": "error-401.json", "headers": { "WWW-Authenticate": "Basic realm=\"jira\"" } },
    "429": { "file": "error-429.json", "headers": { "Retry-After": "60" } },
    "500": { "file": "error-500.json" }
  }
}
```

- **Matching.** Method (`GET` by default), exact path, and every listed query parameter. A value of
  `"*"` only requires the parameter to be present. Parameters not listed are ignored. The first
  matching route wins; no match answers 404.
- **Answers.** `file` is relative to the tool folder (an inline `body` also works). `status` defaults to
  200. Content type is `application/json` unless a header overrides it.
- **`{{baseUrl}}`.** Written in a body or header value, it is replaced by the mock server's URL. Use it
  for `self` links and GitHub `Link` headers so paging comes back to the mock.
- **`errors`.** Recorded error answers, used when a test injects that status. Without one the mock
  answers a generic JSON body.
- **Loading.** A missing `routes.json`, a route without a leading `/`, or a missing file fails at load
  with a message naming it. `LiveMockServer.load("<tool>")` checks fixtures without starting a server.

### Recording and sanitizing

1. Record each answer once from the real tool with a **read-only** demo user (for example with
   `curl -s -u <user> '<base>/rest/api/2/myself'`). Never commit the command line with a password.
2. Remove passwords, tokens, real emails and real names. Replace host names with
   `jira.example.local` or `{{baseUrl}}`.
3. Keep the answer's shape: field names, nesting, paging fields (`startAt`, `maxResults`, `total`),
   and both variants where a tool answers in two formats (Jira's sprint as a string or an object).
4. Check with a grep that no password or real email remains.

### Faults

Tests replace a fixture answer with a fault:

```java
mock.faultAt(2, LiveMockServer.status(401));                       // the 2nd request since reset()
mock.faultOn("/rest/api/2/myself", LiveMockServer.status(500));    // every request to a path
mock.faultAt(3, LiveMockServer.stall());                           // never answers: read timeout
mock.faultAt(2, LiveMockServer.redirect("http://10.0.0.5/"));      // 302, never followed
```

`mock.requests()` lists every request since `reset()` as `{n, method, path, query, uri, headers}`.
Recorded headers hold the credentials the connector sent: assert on them, never print them.

### The loopback flag

Base URL validation refuses loopback addresses, so the mock's `http://127.0.0.1:<port>` origin is
accepted only with the test-only flag `PULSE_LIVE_ALLOW_LOOPBACK=true` (config `liveAllowLoopback`,
read by `LoopbackFlag`). Pulse refuses to start with it under `PULSE_PROFILE=production`.

## 3. The connector contract test

`AbstractLiveConnectorContractTest` is the shared test every connector extends. It runs the
connector against its fixtures through a real `GuardedHttp`, an in-memory ingest store that upserts by
(entity, external_id) like `IngestService`, and `InMemoryExternalAccountService`. It checks:

- the mock origin needs the loopback flag, and the connector declares it for the test config
- Test connection passes on the recorded 200s
- paged ingest over at least two pages, as valid batches, ending with a cursor
- a re-sync (full, then incremental) adds no rows
- the second run sends the first run's cursor
- 401, 429, 500, a stall and a 302 to another host on page 2 each fail with that code, keep page 1,
  send no further request and never reach the other host
- only GET requests, only to the declared origin
- the secret appears in no log line, result, batch, row or error
- the expected accounts are recorded as unmatched

A connector's test supplies:

| Hook | What to return |
|---|---|
| `connector()` | a new connector |
| `fixtures()` | the folder under `live-fixtures/` |
| `config(baseUrl)` | the config pointing at the mock (`baseUrl` is `http://127.0.0.1:<port>`) |
| `secret()` | a test secret |
| `assertCursorUsed(cursor, requests)` | assert the second run's requests carry the cursor (Jira: the JQL holds the cursor minus one minute) |
| `expectedUnmatchedAccounts()` | account ids a full sync must leave unmatched |
| `people(accounts)` *(optional)* | people the fixtures may match by email |
| `secretForms()` *(optional)* | extra forms of the secret, e.g. `base64(username:password)` for Basic auth |
| `minimumPages()`, `readTimeoutMs()` *(optional)* | defaults 2 and 500 ms |

The smallest example is `StubLiveConnectorContractTest` with `live-fixtures/stub/`.

## 4. How to add a connector

1. **Package.** Create `backend/modules/connectors/<tool>/` with `<Tool>Connector implements
   LiveConnector`. It is the only place that knows the tool's URLs, auth headers, paging and fields.
2. **Config and secret.** Write `configSchema()` and `secretSpec()`. Validate a base URL with
   `BaseUrls.validate(url, allowLoopback)`, and derive `allowedOrigins(config)` from
   `BaseUrls.origin(url)`, or return a fixed origin.
3. **Test connection.** Call the tool's "who am I" endpoint (and anything else the form needs, such as
   field discovery) with `ctx.http().get(...)`. Return `ok: true` only when every call answered 200,
   and a hint on 401. Never echo the secret.
4. **Fetch pages.** Page with `ctx.http().getOk(...)`. Map each page to a batch, call
   `ctx.accounts().resolve(...)` for each person you see, hand the batch to the sink, and compose the
   next page on the sink's future. Resolve to `{cursor}`: the greatest "updated" time seen.
5. **Register** the connector in the connectors module's `start(ctx)`.
6. **Fixtures.** Record and sanitize them into `live-fixtures/<tool>/` with a `routes.json`, including
   at least two pages and the 401, 429 and 500 error answers.
7. **Contract test.** Add `<Tool>ConnectorContractTest extends AbstractLiveConnectorContractTest`
   under `backend/src/test/java/com/pulse/modules/connectors/<tool>/`, then tool-specific tests (auth
   headers, mapping edge cases).
8. **Mapping record.** Add a "`<Tool>` → Pulse mapping" section below and send it to Track 1. Track 2
   reviews it against the seed semantics.
9. **Check the plug-in rule.** The change touches nothing under `backend/src/` or
   `frontend/src/modules/integrations/`. If it must, fix the platform gap in a separate task first.

## 5. Mappings

<!-- Track 3 sends the "Jira Server → Pulse" (26.3) and "GitHub → Pulse" (26.6) sections; Track 1 merges them here. -->
