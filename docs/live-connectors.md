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
| `configSchema()` | `{fields: [{name, label, type, required, pattern, help, options, dependsOn, default}]}` | non-secret fields; type is text, url, list, date, boolean or choice. The Integrations form is rendered from it (`default` pre-fills a new connection; `dependsOn` shows the field only while another field holds one of the listed values). |
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

### Secrets at rest (task 24.2)

Connectors never store or decrypt anything themselves; the platform does it.

- **Key.** `PULSE_SECRET_KEY` is 32 random bytes in base64. Generate one with
  `openssl rand -base64 32`. Without it, saving a live credential fails with 422
  `secret_key_missing` and nothing is stored. Seeded tools don't need it.
- **Storage.** `ConnectionStore` (`ctx.connections(sql)`) encrypts the secret with `SecretBox`
  (AES-256-GCM, a random 96-bit IV per save, bound to the tool id) and writes one row per tool in
  `integration_connections`. Only registered live connectors are stored there.
- **What goes out.** `ConnectionStore.view(tool)` is the only shape a response may carry:
  `{tool, config, last4, hasSecret, expiresAt, state, rotatedAt, updatedAt, version}`. `last4` is
  null for secrets shorter than 12 characters. Ciphertext and IV never leave the database.
- **Unreadable secret.** If the key is missing or changed, or the row was altered,
  `ConnectionStore.secret(tool)` fails with `SecretKeyException` (`secret_unreadable`). The test or
  sync sends no request and records `SecretKeyException.SYNC_ERROR` (`key`), so the tile shows
  "Sync failed · key" and the Admin enters the credentials again. Key rotation is not supported yet.
- **Redaction.** Logs mask `Authorization` header values (any scheme), `Basic` credentials and
  every `password`, `token` and `secret` member. Audit summaries replace the same names, plus
  `authorization` and `ciphertext`, with `[redacted]`.

### Test connection, Save and Disconnect (task 24.5)

The same routes work for every registered live connector. All of them are Admin only
(`integrations.manage`). A tool without a live connector answers 404 on these routes; seeded tools
keep the Phase 1 `/tools/{tool}/...` routes.

| Route | Body | Answer |
|---|---|---|
| `GET /api/v1/integrations/{tool}/connection` | | `{tool, source, configSchema, secretSpec, requiredScopes, connection}`, where `connection` is the view above or null |
| `POST /api/v1/integrations/{tool}/test` | `{config, secret, expiresAt?}` | `{ok, status, code, account, scopes, scopeNote, fields, expiresAt, hint, warnings, proof, proofExpiresAt}` |
| `PUT /api/v1/integrations/{tool}/connection` | `{config, secret, expiresAt?, proof}` | as `GET` |
| `DELETE /api/v1/integrations/{tool}/connection` | | as `GET`; 409 `not_connected` when no secret is stored |

- **Validation.** `LiveConfig` checks `config` against `configSchema()` and the secret against
  `secretSpec()` before any request leaves the process. Errors are 400 `validation_failed`, naming
  `config.<field>` or `secret`.
  - Field types: `text`, `url`, `list`, `date`, `boolean`, `choice`.
  - `url` follows the base URL rules: it is stored normalized, and `http` adds an `insecure_http`
    warning.
  - `list` takes an array or a comma-separated string.
  - `choice` takes one of `options`, given as strings or `{value, label}`.
  - `pattern` applies to text values and to each list item.
  - `dependsOn: {field, values: [...]}` makes a field count only while that other field holds one
    of the values, for example a username needed only for `basic` auth. Otherwise its value is
    dropped.
  - Unknown fields are refused.
- **Expiry.** `expiresAt` is an optional ISO date, entered by the Admin (for example for a Jira
  PAT). When it is empty, Save stores the `expiresAt` that the test reported (for example a GitHub
  header).
- **Pass.** A test passes only when the connector says `ok` **and** the status is 200. Errors from
  the guarded client are reported with `ok: false`, a `code` (`401`, `redirect`, `timeout`, ...) and
  a `hint`; they are not HTTP errors. A 401 without the connector's own hint gets "401 Unauthorized,
  check the username and password or token". Nothing is stored by a test.
- **Proof.** Only a passing test carries a `proof`. Save accepts it once, within 10 minutes, for the
  same tool and the same normalized config, secret and expiry. Otherwise Save answers 422
  `test_required` and the stored values are unchanged. Proofs are kept in memory in
  `vertx.sharedData()` as an HMAC, never the secret, so a restart forgets them.
- **Save** encrypts the secret, then writes the credential activity log ("Credentials added" or
  "Credentials changed") and a `credential.changed` audit entry with the config field names, all in
  one transaction. Without `PULSE_SECRET_KEY` it answers 422 `secret_key_missing` before the proof is
  used.
- **Disconnect** deletes the secret and keeps the config and every ingested row. It writes the
  credential activity log and the audit entry.
- **No secret out.** Responses carry only the fields listed above. Any string that contains the
  submitted secret is masked as a last guard.

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

### Jira Server → Pulse (task 26.3)

Code: `backend/modules/connectors/jira/JiraConnector.java` (requests, paging, batches) and
`JiraMapper.java` (pure mapping). Track 2 reviews this record against the Phase 1 seed semantics in
task 25.4.

**Requests** (REST API v2, GET only, to the base URL's origin and context path)

| When | Request | Used for |
|---|---|---|
| Test connection | `/rest/api/2/myself` | account shown on the form: `name`, `displayName`, `emailAddress` |
| Test connection | `/rest/api/2/field` | proposals: custom fields named "Story Points" / "Story point estimate" (story points), schema `com.pyxis.greenhopper.jira:gh-sprint` (sprint), datetime custom fields named "Start Time (Dev)", "End Time (Dev)", "Start Time (QA)", "End Time (QA)" (ignoring case) for the four Start/End ids |
| Sync now | `/rest/api/2/search?jql=…&maxResults=100&expand=changelog&fields=…&startAt=N` | issues, sprints, events, worklogs, work intervals; pages until `startAt + issues >= total` |

- **JQL.** `project in ("PUL", "OPS") ORDER BY updated ASC` on the first run, then
  `project in (…) AND updated >= "<cursor − 1 min, yyyy-MM-dd HH:mm>" ORDER BY updated ASC`. Keys are
  upper-cased and must match `[A-Z][A-Z0-9_]{0,49}`, so nothing else reaches the JQL.
- **`fields=`** summary, issuetype, status, assignee, creator, reporter, created, updated, duedate,
  resolutiondate, timeoriginalestimate, timeestimate, timespent, description, comment, worklog, plus
  the confirmed story points, sprint and Dev/QA Start/End field ids.
- **Cursor.** The greatest `fields.updated` seen, stored as ISO-8601 with its offset. JQL has minute
  precision, so the next run overlaps by one minute, and the upsert by (source, external_id) absorbs
  the repeats.
- **Auth.** Basic `base64(username:password)` or Bearer `<personal access token>`. Jira Server
  reports no scopes; the form says to use a read-only user.

**`issues`** (`source = 'jira'`, `external_id` = issue key)

| Pulse column | Jira | Rule |
|---|---|---|
| `key`, `external_id` | `key` | e.g. `PUL-201` |
| `summary` | `fields.summary` | the key when empty |
| `issue_type` | `fields.issuetype.name` | `Story` when missing |
| `status` | `fields.status.statusCategory.key` | `new` → To Do, `indeterminate` → In Progress, `done` → Done; an in-progress status whose name contains "review" → In Review; an unknown category → In Progress |
| `assignee_id` | | always null for live rows; the person is resolved at read time (V290 view `issues_with_assignee`) |
| `assignee_account_id` | `fields.assignee.name` | the Jira username; null when unassigned |
| `story_points` | `fields.<story points field>` | a number, else null (unknown or unset field: null, never guessed) |
| `estimate_hours` | `fields.timeoriginalestimate` | seconds ÷ 3600, two decimals; null stays null |
| `remaining_hours` | `fields.timeestimate` | seconds ÷ 3600; null stays null |
| `spent_hours` | `fields.timespent` | seconds ÷ 3600; null → 0 |
| `due_on` | `fields.duedate` | date |
| `started_on` | changelog | date of the first `status` change; null while To Do; never after `resolved_on` |
| `resolved_on` | `fields.resolutiondate` | the date in Jira's own offset |
| `sprint_id` | sprint field | `$ref` to the issue's last dated sprint; null when it has none (see sprints) |
| `carried_over` | sprint field | true when the issue lists more than one sprint |
| `reopened`, `was_stale` | | false (not derived from Jira yet) |
| `html_url` | | `<baseUrl>/browse/<key>` |
| `description` | `fields.description` | the v2 plain text / wiki markup string as is (V293); not a string → null; no ADF parsing |

**`sprints`** (`source = 'jira'`, `external_id` = Jira sprint id)

- The sprint field is read in both forms Jira Server returns: the string
  `com.atlassian.greenhopper.service.sprint.Sprint@…[id=…,name=…,state=…,startDate=…,endDate=…,…]`
  (`<null>` read as null) and the object `{id, name, state, startDate, endDate, …}`.
- `key` = `J<id>` (apart from the seed's `S<n>`, 25.4 review), `number` = the Jira id, `starts_on` = `startDate`'s date, `work_ends_on` =
  `endDate`'s date, `ends_on` = the Sunday on or after `work_ends_on` (as seeded sprints end).
- `name` = the sprint's Jira name, trimmed; null when Jira gives none (change jira-sprint-names). A
  rename in Jira updates the same row on the next sync of an issue in that sprint.
- **Labels.** Every screen names a sprint by `name`, else "Sprint <number>" (seed sprints have no name,
  so they stay "Sprint 43"). When two sprints share a name, as on two boards, each label adds its key:
  "Sprint 12 (J140)". Keys never change with the name, so saved period choices and snapshots keep
  working.
- A sprint without both dates (a future sprint) is not stored, and an issue in it gets no `sprint_id`.

**`issue_events`** (`source = 'jira'`, `external_id` = `<key>|<event id>`)

| Event | From | `kind` |
|---|---|---|
| creation | `fields.created`, author `creator` (else `reporter`) | `created` |
| each changelog item | `changelog.histories[].items[]`, author `histories[].author` | `status` → `transition`, `assignee` → `assignee_change`, `Sprint` → `sprint_change`, `timespent` / `WorklogId` → `worklog`, any other field → `field_change` |
| each comment | `fields.comment.comments[]`; the body goes to `to_value` | `comment` |
| Jira's `updated` | only with "Ignore edits by bots and non-assignees" **off**: a `transition` by the assignee | `transition` |

- `from_value` / `to_value` are stored as the seed stores them (25.4 review): a `sprint_change` holds the
  key of the issue's last sprint (`J147`; null when it left every sprint), an `assignee_change` the
  Jira usernames, read as people through `from_person` / `to_person` of `issue_events_with_actor`
  (V294). Any other field keeps Jira's display text. Display names stay in `summary`.
- `actor_account_id` is the author's Jira username, and `actor_id` stays null: the person is resolved
  at read time (V292 view `issue_events_with_actor`). An account marked as a bot makes its events
  bot events.
- **Last activity** (`work` loaders) counts only the assignee's own non-bot `transition`, `worklog`
  and `comment` events, so bot edits and other people's edits never refresh it. With the switch on (the
  default), a bot-only edit leaves last activity unchanged. With it off, Jira's `updated` counts as
  the assignee's activity whoever made the change.

**Hours: worklogs and work intervals** (change jira-derived-hours)

- **Precedence, per issue.** An issue has Jira time tracking when the search embeds a worklog for it or
  `fields.timespent` > 0. Such an issue's hours come from its worklogs only; its work interval rows are
  written with `starts_at` null, so intervals synced earlier stop counting. Any other issue's hours come
  from its Dev and QA intervals.
- **Truncated worklogs.** The search embeds at most 20 worklogs per issue (`worklog.total` gives the real
  count). An issue with more still takes the time-tracking branch with the embedded ones, and the read
  result reports the number of such issues as `truncatedWorklogs`.

`worklogs` (`source = 'jira'`, `external_id` = worklog id)

| Pulse column | Jira | Rule |
|---|---|---|
| `issue_id` | issue key | `$ref` to the issue |
| `account_id` | `worklog.worklogs[].author.name` | the Jira username; `person_id` stays null and is resolved at read time (`worklogs_with_person`) |
| `work_date` | `started` | the start date in Asia/Kolkata |
| `hours` | `timeSpentSeconds` | seconds ÷ 3600, two decimals |
| `logged_at` | `started` | the full start time |

`issue_work_intervals` (`source = 'jira'`, `external_id` = `<key>|dev` or `<key>|qa`)

| Pulse column | Jira | Rule |
|---|---|---|
| `kind` | | `dev` for the Dev pair, `qa` for the QA pair; a pair with either field id empty is off and writes nothing |
| `starts_at` | the Start field | null when the field is empty or the issue has time tracking: the row then counts nothing |
| `ends_at`, `end_source` | the End field, else `fields.resolutiondate` | `field` when the End field is set; `resolution` when it is empty and the issue is resolved; else null and `open` (counted up to now) |
| `account_id` | assignee changelog | the issue's assignee at `starts_at` (from the `assignee` items' `from` / `to` usernames); if nobody was assigned then, the author of the change that set the start value; else the current assignee |

- One row per issue and kind, upserted on every sync, so a moved or cleared Start/End value never leaves
  stale hours. The connector stores raw intervals only; the `work` loaders split them into hours per day
  (working-day window, holidays, leave and visit days, `dayHours`).
- Start/End values are read from the field values in the search answer (ISO with offset), never from the
  changelog's display strings.

**People** (`external_accounts`, tool `jira`)

- Every assignee, changelog author, comment author, worklog author and assignee named in the assignee
  changelog on a page is resolved once with
  `{accountId: name, login: name, displayName, email: emailAddress}`.
- Matching: an existing link first, then `emailAddress` against users' work emails, ignoring case.
  A user whose email the instance hides stays unmatched under their username until an Admin links them.
- Rows keep the username. Linking or unlinking changes per-person figures from the next read, with no
  re-sync. Unmatched and bot accounts count for no person; team totals include unmatched issues.

**Points for the 25.4 review**

1. **One sprint calendar.** Jira sprints go into the same `sprints` table as the seed sprints, and the
   active sprint is the first row by `starts_on` whose dates contain today. A Jira sprint that
   overlaps a seed sprint makes the active sprint depend on start dates. Workload counts only issues
   of the active sprint's `key`.
2. **Sprint key collisions.** `sprints.key` is not unique, so Jira sprint 43 gets key `S43`, the same as
   seed Sprint 43, and both would count as one sprint wherever `s.key` is compared. A distinct
   namespace (for example `J43`) avoids it. That needs Track 2's agreement on how keys are shown.
3. **Future sprints** are not stored, so their issues have no sprint until the sprint starts and
   the next sync picks up the dates.
4. **Seeded index inputs.** The performance index needs the seeded pillars and culture ratings of the
   quarter, which the demo seed holds up to 2026-Q3. In a quarter without them there is no index,
   whatever Jira holds. Jira moves the index only through Discipline (the live sprint's stale
   count).

**25.4 review outcome (Track 2, 2026-11-26).** The record matches the Phase 1 seed semantics for
statuses, hours, story points, due and resolved dates, accounts and description, and
`JiraShapeTest` shows a live issue, its sprint and its metrics reach `CalcService` in the seed's
shape. The four points above, and what the review added:

- **Points 1, 3 and 4: accepted.** A production instance runs without the demo seed, so the two
  calendars only meet in a demo. Do not connect a live Jira to a database with `PULSE_DEMO_SEED=true`
  whose seeded sprints overlap the Jira sprints.
- **Point 2: fixed.** Jira sprints are keyed `J<id>`; the number, which the screens show, stays
  the Jira id.
- **Changelog values: fixed.** `sprint_change` held the sprint name and `assignee_change` the display
  names, so the Effort detection (mid-sprint additions, take-overs) never matched a live row. They now
  hold the sprint key and the usernames (above), and V294 resolves the usernames.
- **Follow-ups** (tasks 25.8 and 26.7):
  - Jira worklogs are not read yet, so hours logged, closed-period utilization and Effort's logged
    hours are 0 for Jira work.
  - `carried_over` is set in the issue's newest sprint, where the seed sets it in the sprint the
    issue left: the old sprint loses the issue's committed points and the new one counts it as
    carried.
  - `reopened` and `was_stale` are always false, so the reopen rate and the closed sprint's stale
    count are 0 for Jira rows.
  - `started_on` is the first status change of any kind; the seed's is the first move to In
    Progress. Close for most workflows; noted, no task.

**End-to-end flow.** `npm run test:live-flows` (in `frontend/`) runs `e2e/live-flows/jira.spec.js` with
`playwright.live.config.js`, on its own backend and database:

- `pulse_e2e_live` is dropped and recreated with the demo seed.
- "Today" is 26 Nov 2026, inside Jira sprint 147 of `live-fixtures/jira-e2e/`.
- `PULSE_LIVE_ALLOW_LOOPBACK=true` is set, with a fresh `PULSE_SECRET_KEY` for each run.

The mock Jira (`e2e/live-flows/mockTool.js`) serves those fixtures with the `routes.json` rules of
section 2, and scripts the 401 and the held search for the 409. Its fixtures match two demo people:
`priya.shah` by email (in another letter case), and `arjun.m` (no email), whom the flow links by
hand. The flow copies each person's 2026-Q3 pillars and culture ratings to 2026-Q4 so the index
exists (review point 4). Build the backend jar first.
