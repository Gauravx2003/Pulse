# Oracle harness: checking a formula port against the prototype

The prototype (`reference/2-prototype/src/app.js`) is the only runnable version of the formulas
(design.md decision 5). Every formula you port into `backend/calc/` must be compared with the matching
prototype function on the same fixture, and the two must agree to the precision the screen shows. The
`Oracle` test utility does this for you.

If the port and the prototype disagree, the text of spec section 12 decides. Record the difference in
the test with a comment and assert the spec's value instead.

## How it works

`com.pulse.testsupport.Oracle` (backend tests) starts one Node process running
`seed/lib/oracle-server.mjs`. That process loads `app.js` in a sandboxed `vm` context with the same
loader the seed extractor uses (`seed/lib/load-prototype.mjs`):

- The DOM is a stub.
- The clock is pinned to 28 Sep 2026 15:30 IST, and `TZ` is Asia/Kolkata.
- `Math.random` is seeded.

Every binding inside the prototype can be reached: functions (`index`, `cult`, `valData`,
`discMetrics`, `attStats`, `trRating`, `aprRecommend` and their helpers) and data (`PEOPLE`,
`person('ps')`, `S.rules`, `ATT` and the others).

You need Node 20 or later on the `PATH`, or its path in `PULSE_NODE`. CI installs Node 24 for the backend
job.

## Writing a comparison test

```java
class IndexOracleTest
{
    private static Oracle oracle;

    @BeforeAll
    static void start() throws Exception
    {
        oracle = Oracle.start();                // one Node process per test class
    }

    @AfterAll
    static void stop()
    {
        oracle.close();
    }

    @AfterEach
    void reset()
    {
        oracle.reset();                         // undo any eval(...) between tests
    }

    @Test
    void indexMatchesThePrototypeForEveryPerson()
    {
        JsonArray ids = (JsonArray) oracle.eval("PEOPLE.map(function(p){return p.id})");

        for (Object id : ids)
        {
            oracle.compare("index", Oracle.args(Oracle.js("person('" + id + "')")), 1,
                    in -> IndexFormula.of(in.getJsonObject(0), weights));   // your port
        }
    }
}
```

`compare(name, args, decimals, port)` does four things:

1. It calls the prototype function `name` with `args`.
2. It gives your `port` the same arguments, already turned into plain JSON (a `JsonArray`). Your port
   and the prototype therefore see one fixture.
3. It rounds every number on both sides as JavaScript's `toFixed(decimals)` would show it. It then
   compares the results member by member: objects need the same keys, arrays the same length, and
   strings and booleans must be equal.
4. It fails when either side holds `NaN`, an infinity or `undefined`. The failure message gives the path
   of each difference, for example `$.parts[2].score: prototype 3.4 (3.4166…), port 3.5 (3.45)`.

`compare(name, args, port)` uses one decimal (`Oracle.DEFAULT_DECIMALS`), the precision of scores and
the index. Pass the precision the screen actually shows: `0` for percentages and whole-point counts, `1`
for scores and `2` for currency.

## Arguments

| You write | The prototype receives |
|---|---|
| `Oracle.js("person('ps')")` | the result of that expression, evaluated inside app.js |
| `Oracle.js("ATT['ps']")`, `Oracle.js("perOf('s:S42')")` | any binding or expression |
| `4`, `"Q3"`, `new JsonObject()...` | that value as JSON |

`oracle.resolve(args)` returns the arguments the way your port receives them. Use it to see what a
fixture holds.

## Other calls

| Call | Use |
|---|---|
| `oracle.call("cultureAvg", Oracle.js("person('ps')"))` | the raw result of a prototype function |
| `oracle.eval("S.rules.weights.okr = 20")` | change prototype state, for example altered rule weights |
| `oracle.eval("PEOPLE.map(function(p){return p.id})")` | read data to loop over |
| `oracle.reset()` | reload app.js and undo every `eval` |
| `Oracle.assertFinite("what", value)` | fail on NaN, an infinity or `undefined` anywhere in a value |
| `Oracle.display(3.75, 1)` | `"3.8"`, the same as `(3.75).toFixed(1)` |

A prototype error, such as a misspelled name, throws `IllegalArgumentException` with the JavaScript
error. The process keeps running.

## Rules

- Compare every seeded person, and every period the formula's task names (tasks.md 4.3–4.8 and the
  Track 2 and Track 3 formula tasks). Don't settle for one hand-picked example.
- If the formula depends on rules, also compare under altered rules: set them with `eval`, then call
  `reset()` afterwards.
- Never change `app.js` or the loader to make a test pass. If the prototype is wrong, follow the spec and
  record it.
- Tests that use the oracle need no database and run in about a second.
