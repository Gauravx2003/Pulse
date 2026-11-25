# Demo runbook

This page covers how to start Pulse for a demo, which flags it needs, who to sign in as, why "today"
is fixed, and what is simulated in this version (task 23.5). The demo data is the prototype's
(`reference/2-prototype/src/app.js`), so the numbers on screen match the prototype.

## 1. Start it

### One command (local tools)

```bash
scripts/demo.sh            # first run: builds, creates the database, loads the demo seed
scripts/demo.sh --reset    # back to a clean demo: drops and recreates the demo database
scripts/demo.sh --build    # after a pull: rebuild the backend jar too
```

Then open <http://localhost:5173> and sign in. Ctrl+C stops the web app and the backend.

The script:

- creates the database `pulse_demo` if it is missing;
- builds `backend/target/pulse-backend-1.0.0-SNAPSHOT-fat.jar` if it is missing;
- runs `npm ci` in `frontend/` if `node_modules` is missing;
- starts the backend with the demo flags (§2) and waits for `/health`;
- starts the web app (Vite), which forwards `/api` to the backend.

The backend log is `backend/target/demo-backend.log`. The first start applies every migration and
loads the demo seed, which takes about a minute. Later starts take a few seconds.

**Requirements:** Java 21, Maven 3.9, Node 20 or later, and PostgreSQL 16. The quickest way to get the
database is `docker compose up -d postgres`. A local PostgreSQL works too: the script connects as
`pulse` / `pulse_secret_dev` on `localhost:5432` unless you set other values.

| Setting | Default | What it changes |
|---|---|---|
| `DB_HOST`, `DB_PORT`, `DB_USER`, `DB_PASSWORD` | `localhost`, `5432`, `pulse`, `pulse_secret_dev` | The PostgreSQL to use. The user needs to be able to create a database. |
| `PULSE_DEMO_DB` | `pulse_demo` | The database name. Keep it apart from your development database. |
| `API_PORT`, `WEB_PORT` | `8088`, `5173` | The backend and web app ports. |
| `PULSE_TODAY` | `2026-09-28` | The pinned date (§4). |

### Everything in Docker

```bash
docker compose up --build
```

This starts PostgreSQL, the backend on `:8088` and the built web app behind nginx on
<http://localhost>, with the same demo flags. It needs only Docker. Port 5432 must be free; if a local
PostgreSQL is running, stop it first. `docker compose down -v` deletes the demo database, so the next
`up` starts clean.

## 2. Demo flags

The backend reads these from the environment. `scripts/demo.sh` and `docker-compose.yml` set them for
you.

| Variable | Demo value | Meaning |
|---|---|---|
| `PULSE_DEMO_SEED` | `true` | Loads the demo accounts and data at start-up: seed files named `S<n>__demo_*` and the seed connector's Jira, GitHub, support and attendance data from `seed/extracted/`. Without it the database has only roles and default rules. |
| `PULSE_TODAY` | `2026-09-28` | Pins "today" (§4). |
| `PULSE_PROFILE` | `development` | Must be `development`. Under `production` the backend refuses to start with the demo seed on, because every demo account has the same password, and it ignores `PULSE_TODAY`. |
| `PULSE_APP_URL` | the web app origin | Used for CORS, the Origin check on writes and the links in invites. It must match the address in the browser. |
| `PULSE_TRUST_PROXY` | `true` behind nginx | Takes the client IP from `X-Real-IP`, which the sign-in throttle uses. Leave it off when the browser reaches the backend directly. |
| `PORT` | `8088` | The backend port. |
| `DB_*`, `PULSE_DB_POOL_SIZE`, `PULSE_DB_APP_ROLE` | `pulse` defaults, `10`, `pulse_app` | The database connection. Migrations run as `DB_USER`; requests run as the DML-only role `pulse_app`. |

Seeds run once, in number order. If a database was started once without `PULSE_DEMO_SEED`, or with a
different `PULSE_TODAY`, its numbers will not match the demo. Reset it (§5).

## 3. Accounts

Every account's password is **`demo123`**.

| Account | Name and role | Shows |
|---|---|---|
| `hr@pulse.demo` | Anita Desai, **Admin** (HR) | Everything: all people, Rules & thresholds, Users & roles, Integrations, data imports, appraisal approval, 360° publishing |
| `rahul@pulse.demo` | Rahul Nair, **Manager**, Platform | The manager's day: Action center, Team utilization, nudges, extra-mile confirmations, 1:1s, calibration, appraisal proposals, training assignment |
| `priya@pulse.demo` | Priya Shah, **Employee**, Software Engineer II | The employee view: Overview of the live sprint, Progress and weekly pulse, own culture values, PRs, effort, visits |
| `arjun@pulse.demo` | Arjun Mehta, Senior Engineer | A peer of Priya: the peer cannot see her private data |
| `sara@pulse.demo` | Sara Khan, Senior Engineer | Mentor of the trainee Rohit: assessment and mentor review |
| `neha@pulse.demo` | Neha Joshi, Software Engineer I | 360° reviewer |
| `kiran@pulse.demo`, `dev@pulse.demo` | Kiran Rao, Dev Patel | Platform engineers |
| `rohit@pulse.demo` | Rohit Kulkarni, Trainee | A new joiner on the onboarding plan: check-ins, sign-off |
| `anjali@pulse.demo` | Anjali Nair, Trainee | A second trainee |
| `sana@pulse.demo` | Sana Mirza, Solutions Engineer | Customer visits: account owner and customer feedback |
| `kavya@pulse.demo` | Kavya Rao, Support Lead | Support tickets she assigns |
| `meera@pulse.demo`, `anil@pulse.demo`, `rohan@pulse.demo` | Product, QA and DevOps | People outside the Platform team |

Rahul's team is Priya, Arjun, Neha, Kiran, Dev, Sara, Rohit and Anjali. Everyone else reports to
Anita.

After sign-in, Anita and Rahul land on the Action center and the engineers on Overview. Meera, Anil,
Kavya, Rohan and Sana have no performance profile, so their menu has no person pages and they land on
Customer visits (as in the prototype).

## 4. The pinned date

"Today" is **Monday 28 September 2026**, the prototype's date. On that day:

- Sprint 43 (21 Sep to 9 Oct) is live and Sprint 42 (7 to 18 Sep) has just closed;
- Q3 2026 is the live quarter, and the index history of Q4 2025 to Q2 2026 is seeded;
- release 8.2 is out (18 Sep) and 8.3 is in progress (13 Nov);
- September's attendance is not uploaded yet, so the upload reminder shows.

Without the pin, the backend uses the real date. Once that date is past the last seeded sprint, there
is no live sprint, and the live screens are empty or show zeros. At start-up and every hour, the
backend freezes the sprints and quarters that have ended (`calc_snapshots`). On an unpinned
database, that freezes periods the demo expects to be live. Reset the database if that happened.

## 5. Keeping the demo the same everywhere

Two machines show the same numbers only when both run:

- the same commit;
- the same flags (§2);
- a database built from scratch.

Clicking through the demo changes the data. Confirming extra-mile items, submitting a pulse, sending a
nudge, OKR check-ins and appraisal proposals all change it. Database IDs differ between machines.
The numbers must match.

Before a demo, and whenever two machines disagree:

```bash
git pull
scripts/demo.sh --build --reset
```

To check that two machines match, compare these counts. On a fresh demo database they are identical:

```sql
SELECT count(*) FILTER (WHERE kind = 'MIGRATION') AS migrations, count(*) FILTER (WHERE kind = 'SEED') AS seeds
FROM pulse_schema_history;
SELECT (SELECT count(*) FROM issues) AS issues, (SELECT count(*) FROM worklogs) AS worklogs,
       (SELECT count(*) FROM weekly_pulse) AS pulse, (SELECT count(*) FROM extra_items WHERE state = 'pending') AS pending;
```

## 6. What is simulated

| Area | In this version |
|---|---|
| Jira, GitHub, TFS, support desk, PagerDuty data | Loaded by the seed connector from the prototype's data (`seed/extracted/`). **Sync now** re-reads the same data and logs "Simulated sync from seed". Live GitHub and Jira connectors are Phase 2 (task group 24). |
| Integrations | **Test connection** always passes and says it is simulated; no request is sent. Saved credentials are stored only as masked placeholders, and the reported scopes are the catalogued ones. |
| 12 culture parameters | The parameters without a tool to read from come from `culture_signals`, generated by the prototype. They are marked `is_simulated` and labelled "Reported input". Every other parameter is computed from the seeded data. |
| Email and chat | Invites, password reset links, Slack or Teams nudges and kudos shares go to the `outbox` table. There is no delivery job yet, so nothing leaves the machine. In-app notifications are real. |
| Weekly digest | The Monday digest job is scheduled but only records "pending". The digest is shown as a preview in the Action center. |
| Attendance | The months up to August are seeded as if HR had uploaded its export. Upload September's CSV from the sample file to show the import. |
| Rules, scores and reviews | Real. Every number comes from `CalcService` on the seeded data, and Rules & thresholds edits apply at once (docs/calc.md). |

## 7. Troubleshooting

| Symptom | Fix |
|---|---|
| `Cannot reach PostgreSQL` | Start it (`docker compose up -d postgres`) or set `DB_*`. |
| `Port 5173 is in use` or the backend stops at once | Something else uses the port. Set `WEB_PORT` or `API_PORT`, or stop the other process. |
| Every number is 0, or there is no live sprint | The backend ran without `PULSE_TODAY` or `PULSE_DEMO_SEED`. Run `scripts/demo.sh --reset`. |
| Start-up fails with a duplicate key in a demo seed | The database froze its periods before the demo seed loaded. Run `scripts/demo.sh --reset`. |
| Sign-in says "Too many attempts" | The sign-in throttle tripped. Wait 15 minutes or restart with `--reset`. |
| Changes after a pull do not show | Run `scripts/demo.sh --build`, which rebuilds the backend jar. |
