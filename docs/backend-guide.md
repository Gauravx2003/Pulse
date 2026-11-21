# Backend guide: adding an endpoint to a module

This guide is for Tracks 2 and 3. Follow it top to bottom and you will have a module endpoint that
declares its permission, reads through the policy service, writes audit entries, validates its input,
handles concurrent edits and has a per-role contract test. Everything named here exists in
`backend/src/main/java/com/pulse/platform/`; the Javadoc of each class has more detail.

Ground rules (design.md decision 1, `CONTRIBUTING.md`):

- Modules are plain classes. No verticles, no blocking calls on the event loop (BCrypt, CSV parsing and
  file reads go in `vertx.executeBlocking`).
- No DTO or entity classes: pass `JsonObject` / `JsonArray`.
- SQL lives only in repository classes, with `$1, $2` placeholders and a `Tuple`.
- Handlers never write error responses: they call `rc.fail(...)` (or throw an `ApiException`), and the
  central `FailureHandler` renders it.
- Never write a visibility filter yourself. The policy service is the only code that decides who sees whom.

## 1. The module and its files

```
backend/modules/<id>/
  <Name>Module.java          com.pulse.modules.<id>.<Name>Module implements PulseModule
  <Name>Routes.java          handlers (optional: can live in the module class)
  <Name>Service.java         business rules, transactions, audit
  <Name>Repository.java      SQL only
  migrations/V<n>__<what>.sql
  seed/S<n>__<what>.sql      reference data, always loaded
  seed/S<n>__demo_<what>.sql demo data, loaded only with PULSE_DEMO_SEED=true
```

There is nothing to register. At start-up the platform scans the classpath for
`com.pulse.modules.<id>.<Name>Module` classes and creates each one with its public no-arg constructor. Its
`migrations/` and `seed/` folders are picked up on their own.

`<n>` must be in your track's range: Track 2 `V200–V299` / `S200–S299`, Track 3 `V300–V399` /
`S300–S399`. Leave gaps between versions. Never edit a merged migration: add a new version.
`scripts/check-migration-versions.sh` runs in CI. It checks the file names, rejects duplicate versions
and rejects versions outside 100–399. It does not check the per-track ranges, so review checks those.

Imports: `PulseModule` and `ModuleContext` are in `com.pulse.platform.module`. `PolicyService`, `Scope`,
`ScopedRepository` and `VisibilityRelation` are in `com.pulse.platform.policy`. `BaseRepository`, `ListQuery`
and `SqlExecutor` are in `com.pulse.platform.db`. `BodySchema`, `Ids`, `ExpectedVersion`, `ApiException` and
`RateLimiter` are in `com.pulse.platform.http`. `AuditService` and `AuditEvents` are in
`com.pulse.platform.audit`.

Editable tables follow design.md decision 4:

```sql
-- backend/modules/kudos/migrations/V330__kudos.sql
CREATE TABLE kudos (
    id          uuid        PRIMARY KEY DEFAULT gen_random_uuid(),   -- always a random UUID
    person_id   uuid        NOT NULL REFERENCES people (id),          -- whose record this is
    author_id   uuid        NOT NULL REFERENCES people (id),
    message     text        NOT NULL CHECK (length(message) <= 500),
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),                  -- needed by updateVersioned
    version     integer     NOT NULL DEFAULT 1                       -- optimistic lock
);
```

Every table that holds a person's data has a column naming that person (here `person_id`); the policy
scope filters on it.

## 2. The module descriptor

```java
package com.pulse.modules.kudos;

public class KudosModule implements PulseModule
{
    @Override
    public String id()
    {
        return "kudos";                       // routes mount under /api/v1/kudos
    }

    @Override
    public List<String> permissions()
    {
        return List.of("kudos.moderate");     // only permissions PermissionCatalogue does not already have
    }

    @Override
    public List<String> auditEvents()
    {
        return List.of("kudos.created");      // only events not already in AuditEvents
    }

    @Override
    public void routes(Router router, ModuleContext ctx)
    {
        new KudosRoutes(ctx).mount(router);
    }
}
```

Other hooks on `PulseModule`: `menu()` (only screens beyond the screen map), `jobs()`,
`actionProviders()` and `visibilityRelations()` (section 4).

`ModuleContext` gives you `vertx()`, `config()`, `sql()` (the shared `SqlExecutor`) and `policy()`
(the `PolicyService`). Never use `pool()` directly.

## 3. Routes: permission first, then schema, then the handler

Every route starts with the permission it needs. `policy.requires(...)` needs all of the listed
permissions and `policy.requiresAny(...)` needs at least one. A misspelled permission fails at start-up.
A refused check answers 403 `no_access` and writes a `permission.refused` audit entry for you.

```java
public final class KudosRoutes
{
    static final BodySchema CREATE = BodySchema.object()
            .uuid("personId")
            .string("message", 500)
            .required("personId", "message");

    static final BodySchema EDIT = BodySchema.object()
            .string("message", 500)
            .integer("version", 1, Integer.MAX_VALUE)
            .required("message");

    private final ModuleContext ctx;

    private final PolicyService policy;

    KudosRoutes(ModuleContext ctx)
    {
        this.ctx = ctx;
        this.policy = ctx.policy();
    }

    void mount(Router router)
    {
        // GET /api/v1/kudos/people/:personId
        router.get("/people/:personId").handler(policy.requiresAny("self.view", "team.view", "all.view"))
                .handler(this::forPerson);

        // GET /api/v1/kudos/items?page=1&pageSize=50&sort=-created
        router.get("/items").handler(policy.requires("oneonone")).handler(this::list);

        // POST /api/v1/kudos/items
        router.post("/items").handler(policy.requires("oneonone")).handler(CREATE.handler()).handler(this::create);

        // PUT /api/v1/kudos/items/:id
        router.put("/items/:id").handler(policy.requires("oneonone")).handler(EDIT.handler()).handler(this::edit);
    }
}
```

**Input validation.** A `BodySchema` lists every allowed field. An unknown field, a wrong type, a string
over its limit or a list over its size answers 400 `invalid_field`, with the field's name in
`error.field`. Read the checked body with `BodySchema.body(ctx)`. Field types: `string`, `integer`,
`number`, `bool`, `uuid`, `date`, `oneOf`, `stringList`, `uuidList`, `object`, `objectList`, plus
`required(...)`.

**Query parameters.** List endpoints accept only the filters and sorts the repository declares on its
`ListQuery`. Anything else answers 400 `invalid_parameter` naming the parameter.

**Path ids.** Use `Ids.path(ctx, "id")` for record ids. A malformed id answers 404 like a missing record.
Person ids go to `policy.requireVisible` instead (next section), which answers 403.

## 4. Visibility: who may see a person's data

Ask the policy service; never filter by hand.

- `policy.requireVisible(user, personId, domain)` checks one person. It resolves to a `Scope` or fails
  with 403 `no_access`. A person the caller may not see and a person who does not exist get the same
  403, so existence is never revealed. Each refusal writes an `access.refused` audit entry. Only a caller
  with `all.view` gets a 404 for a missing person.
- `policy.scope(user, domain)` returns the `Scope` for list endpoints.

The base rules apply in every domain: a person sees themself (`self.view`), a holder of `team.view` sees
their current direct reports (dated reporting lines), and `all.view` sees everyone. Use the domain
constants on `PolicyService`: `DOMAIN_PERSON`, `DOMAIN_OKRS`, `DOMAIN_TRAINING`, `DOMAIN_VISITS`,
`DOMAIN_ATTENDANCE`, `DOMAIN_CHECKIN`, `DOMAIN_SALARY`, `DOMAIN_REVIEWS` and `DOMAIN_PULSE`.

Exceptions:

- **Check-in times** (`DOMAIN_CHECKIN`): the person and their direct manager only. `all.view` does not
  apply, and no relation can widen this domain.
- **Mentor sees mentees' training.** The training module declares a relation; it applies only to its domain:

  ```java
  @Override
  public List<VisibilityRelation> visibilityRelations()
  {
      return List.of(new VisibilityRelation(PolicyService.DOMAIN_TRAINING, "mentor",
              "SELECT mentee_id AS person_id FROM training_mentors WHERE mentor_id = $1"));   // $1 = viewer's person id
  }
  ```

- **Account owner sees their visits.** The visits repository passes an owner column (next section) and
  the scope adds "rows the viewer owns".

### Repositories for personal data take the scope

Repositories of personal, salary, attendance, review and pulse data extend `ScopedRepository`
(`ScopedRepository` itself extends `BaseRepository`). Its read helpers require a `Scope`, which only the
policy service can create, so personal data cannot be read without one. Mark where the filter goes with
`{scope}`:

```java
final class KudosRepository extends ScopedRepository
{
    KudosRepository(SqlExecutor sql)
    {
        super(sql);
    }

    Future<JsonArray> forPerson(Scope scope, UUID personId)
    {
        return scopedList(scope, "k.person_id",
                "SELECT k.id, k.message, k.created_at AS \"createdAt\", k.version FROM kudos k "
                        + "WHERE k.person_id = $1 AND {scope} ORDER BY k.created_at DESC",
                Tuple.of(personId));
    }

    Future<JsonObject> page(Scope scope, MultiMap params)
    {
        return scopedPage(scope, "k.person_id", ListQuery.from("SELECT k.id, k.person_id AS \"personId\", "
                        + "k.message, k.created_at AS \"createdAt\" FROM kudos k")
                .filter("personId", "k.person_id")
                .sort("created", "k.created_at")
                .defaultSort("-created")
                .tieBreaker("k.id"), params);
    }

    Future<JsonObject> insert(UUID personId, UUID authorId, String message)
    {
        return queryOne("INSERT INTO kudos (person_id, author_id, message) VALUES ($1, $2, $3) RETURNING *",
                Tuple.of(personId, authorId, message));
    }

    Future<JsonObject> edit(UUID id, String message, int expectedVersion)
    {
        return updateVersioned("kudos", "message = $1", Tuple.of(message), id, expectedVersion);
    }
}
```

For "visible people or rows I own", use `scopedList(scope, "v.person_id", "v.owner_id", sql, params)`.

The handlers:

```java
private void forPerson(RoutingContext rc)
{
    JsonObject user = PolicyService.user(rc);
    String personId = rc.pathParam("personId");

    policy.requireVisible(user, personId, PolicyService.DOMAIN_PERSON)
            .compose(scope -> new KudosRepository(ctx.sql()).forPerson(scope, UUID.fromString(personId)))
            .onSuccess(rows -> rc.json(new JsonObject().put("items", rows)))
            .onFailure(rc::fail);
}

private void list(RoutingContext rc)
{
    policy.scope(PolicyService.user(rc), PolicyService.DOMAIN_PERSON)
            .compose(scope -> new KudosRepository(ctx.sql()).page(scope, rc.queryParams()))
            .onSuccess(rc::json)                     // {items, page, pageSize, total, pageSizeCapped}
            .onFailure(rc::fail);
}
```

## 5. Writes: transaction, audit, separation of duties

Write the audit entry **in the same transaction** as the change, so it rolls back with it:

```java
private void create(RoutingContext rc)
{
    createKudos(PolicyService.user(rc), BodySchema.body(rc))
            .onSuccess(row ->
            {
                rc.response().setStatusCode(201);
                rc.json(row);
            })
            .onFailure(rc::fail);
}

Future<JsonObject> createKudos(JsonObject user, JsonObject body)
{
    String personId = body.getString("personId");

    return policy.requireVisible(user, personId, PolicyService.DOMAIN_PERSON)
            .compose(scope -> ctx.sql().inTransaction(tx -> new KudosRepository(tx)
                    .insert(UUID.fromString(personId), UUID.fromString(user.getString("personId")),
                            body.getString("message"))
                    .compose(row -> AuditService.record(tx, AuditService.entry(user, "kudos.created", "kudos",
                                    row.getString("id"), new JsonObject().put("personId", personId)))
                            .map(row))));
}
```

`AuditService.record` accepts only names from `AuditEvents` (the spec's catalogue) or events your
module declares in `auditEvents()` (the example module declares `kudos.created`); an unknown name
fails the call. The database adds the id and the UTC time; request id, client IP and
the actor's role are filled in for you. Never put secret values or free text in the summary. Name the
fields that changed instead. Values under keys such as `password`, `token`, `secret`, `apiKey`,
`comment`, `notes`, `text` or `message` are replaced with `[redacted]` anyway, and long strings are cut.

**Data reads that must be audited** (salary reads and exports, 360° result reads, data exports) record
`AuditEvents.SALARY_READ`, `SALARY_EXPORT`, `REVIEW360_RESULTS_READ` or `DATA_EXPORT` before returning
the rows.

**Separation of duties.** Before approving, proposing, overriding, confirming, verifying, calibrating,
resolving or assigning on behalf of someone, call:

```java
policy.refuseSelf(user, "appraisal.increment.approve", subjectPersonId, null, policy.request(rc))
```

On the caller's own record this writes a refused audit entry and fails with 422
`self_action_refused`. The action names are the keys of `PolicyService.SELF_REFUSED`.

**Concurrent edits.** Editable records carry `version`. Read the version the client loaded with
`ExpectedVersion.of(rc, body)` (the body's `version`, or the `If-Match` header) and update through
`updateVersioned(...)`. A stale version answers 409 `version_conflict` with `error.currentVersion` and
changes nothing; a missing row answers 404.

```java
private void edit(RoutingContext rc)
{
    JsonObject body = BodySchema.body(rc);

    new KudosRepository(ctx.sql()).edit(Ids.path(rc, "id"), body.getString("message"), ExpectedVersion.of(rc, body))
            .onSuccess(rc::json)
            .onFailure(rc::fail);
}
```

(A real edit also checks visibility and audits, as in `create`.)

**Exports** use `export(listQuery, params)` on the repository. It applies the same filters, returns at
most `BaseRepository.MAX_EXPORT_ROWS` (10,000) rows, and returns `{items, truncated}`.

## 6. Errors

Fail with `rc.fail(apiException)`, or return a failed future, or throw an `ApiException`
inside a handler. The body is always
`{"error": {"code", "message", "requestId"[, "field"][, "retryAfter"][, "currentVersion"]}}`.

| Status | When | Code |
|---|---|---|
| 400 | bad body, field or parameter | `invalid_field` (with `field`), `invalid_parameter`, `invalid_body` |
| 401 | no session | `unauthenticated` |
| 403 | permission or visibility | `no_access` (also `csrf_invalid`, `origin_rejected`) |
| 404 | no such record or route | `not_found` |
| 409 | stale version | `version_conflict` (with `currentVersion`) |
| 413 | body too large | `payload_too_large` |
| 422 | a business rule | a code naming the rule, e.g. `self_action_refused`, `assessment_failed_cannot_complete` |
| 429 | rate limit | `rate_limited` (with `retryAfter` and a Retry-After header) |
| 500 | anything unexpected | `internal_error`: a generic message quoting the request id; the detail goes to the (redacted) log |

For 422 use `ApiException.unprocessable("<rule_code>", "<sentence the user sees>")`.

**Rate limits.** Every route already has a per-IP and per-user limit. Add a stricter one to expensive
routes such as imports and exports:
`router.post("/upload").handler(RateLimiter.perUser(ctx.vertx(), "imports", 10, 60))`. CSV and multipart
bodies may be up to 5 MB, everything else 1 MB.

## 7. Tests

Every endpoint needs a per-role contract test. Use `TestApi` (a real server on a throw-away schema with
the demo seed) and `RoleMatrix`:

```java
class KudosRoutesTest
{
    private static TestApi api;

    @BeforeAll
    static void start() throws Exception
    {
        api = TestApi.start(18_330, null);    // pick a port no other test class uses
    }

    @AfterAll
    static void stop() throws Exception
    {
        api.close();
    }

    @Test
    void personKudosFollowVisibility() throws Exception
    {
        RoleMatrix.assertRoleMatrix(api, "GET /api/v1/kudos/people/{person:priya@pulse.demo}", Map.of(
                RoleMatrix.ADMIN, 200,       // hr@: all.view
                RoleMatrix.MANAGER, 200,     // rahul@: Priya's manager
                RoleMatrix.EMPLOYEE, 200,    // priya@: herself
                RoleMatrix.PEER, 403));      // arjun@: same team, a peer
    }
}
```

`assertRoleMatrix` fails unless Admin, Manager, Employee and peer are all asserted. It also checks that
refusals carry no data and look the same. `{person:<email>}` and `{user:<email>}` resolve to ids. For
writes pass a body: `assertRoleMatrix(api, "POST /api/v1/...", body, expectations)`. `TestApi` also has
`get`, `post`, `put`, `signIn`, `exec`, `count` and `queryOne` for arranging data and checking rows.

Run the tests with `DB_PASSWORD=pulse_secret_dev mvn test` from `backend/` while the compose PostgreSQL
is up.

## Checklist for a new endpoint

- [ ] Route declares its permission with `policy.requires` / `requiresAny`.
- [ ] Body has a `BodySchema`; path ids go through `Ids.path` or `policy.requireVisible`.
- [ ] Personal data is read through a `ScopedRepository` with a `Scope` from the policy service.
- [ ] Writes run in `inTransaction` and record an audit entry in the same transaction.
- [ ] Acting on someone else's record calls `policy.refuseSelf` first.
- [ ] Editable records update through `updateVersioned` with `ExpectedVersion.of`.
- [ ] Failures go through `rc.fail` / `ApiException` with a stable code.
- [ ] Migrations and seeds are in your range; demo data is in `S<n>__demo_*.sql`.
- [ ] A `RoleMatrix` contract test covers Admin, Manager, Employee and peer.
