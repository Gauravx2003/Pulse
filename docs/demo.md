# Demo runbook

This page covers how to start Pulse for a demo, which flags it needs, who to sign in as, why "today"
is fixed, and what is simulated in this version (task 23.5). §8 adds the live Jira and GitHub
connectors of Phase 2 (task 24.8). The demo data is the prototype's
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
| `PULSE_SECRET_KEY` | generated once into `.pulse/demo-secret-key` | Encrypts live Jira and GitHub credentials (§8). The file is git-ignored and kept across runs, including `--reset`. |

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
| `PULSE_SECRET_KEY` | 32 random bytes, base64 | Encrypts live credentials (§8). `scripts/demo.sh` makes one; for Docker, export it before `docker compose up`. Without it Pulse starts and seeded tools work, but saving live credentials fails with "secret key missing". |
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
| Jira, GitHub, TFS, support desk, PagerDuty data | Loaded by the seed connector from the prototype's data (`seed/extracted/`). **Sync now** on a seeded tile re-reads the same data and logs "Simulated sync from seed". A Jira tile connected live (§8) reads the real server instead; GitHub follows. |
| Integrations | On seeded tiles, **Test connection** always passes and says it is simulated; no request is sent. On a live tile it is a real, read-only request (§8). |
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
| Saving live credentials says the secret key is missing | `PULSE_SECRET_KEY` is not set. `scripts/demo.sh` sets it; with Docker, export one (§8). |
| A live tile shows "Sync failed · key" | The key changed since the credentials were saved (another machine, or `.pulse/` deleted). Enter the credentials again. |

## 8. Live Jira and GitHub (Phase 2)

Phase 2 adds read-only live connectors next to the seeded tools. Jira Server is first; GitHub follows
with the same screens. How connectors are built is in [`live-connectors.md`](live-connectors.md).

### The secret key

Live credentials are stored encrypted (AES-256-GCM) under `PULSE_SECRET_KEY`: 32 random bytes in
base64.

```bash
openssl rand -base64 32        # or: head -c 32 /dev/urandom | base64
```

`scripts/demo.sh` generates one on the first run, stores it in `.pulse/demo-secret-key` (git-ignored,
readable only by you) and reuses it, so saved credentials stay readable. For Docker, export
`PULSE_SECRET_KEY` before `docker compose up`. If the key changes, stored secrets can't be read: the
tile shows "Sync failed · key" and the Admin enters the credentials again. Keep production keys out of
the repository and out of shell history.

### The Jira user and rights

Create a dedicated, **read-only** Jira user for Pulse. It needs only the **Browse projects**
permission on each project listed under *Project keys*, and no other permission. Pulse sends only GET
requests: `/rest/api/2/myself` and `/rest/api/2/field` to test, and `/rest/api/2/search` with the
changelog to sync.

### Basic or Bearer, and `http`

| Authentication | Secret | Use when |
|---|---|---|
| Username and password (Basic, the default) | The Jira user's password | Jira Server without personal access tokens |
| Personal access token (Bearer) | A token from *Profile → Personal Access Tokens* | Jira Data Center 8.14 or later. Preferred: it can be revoked alone, and the password never leaves Jira. |

Both send the secret on every request. Over `http` (for example the internal
`http://172.16.10.6:8080`) it travels **unencrypted** on the network, and the form warns about it. Use
`https` where the server offers it. Otherwise use a Bearer token of the read-only user, so a captured
secret can read only what that user can browse and can be revoked.

### Connect and sync

1. Sign in as `hr@pulse.demo` and open **Integrations**.
2. On the Jira tile choose **Connect**. Enter the base URL, authentication, username, secret and project
   keys.
3. **Test connection.** It shows the account, warnings and the proposed story points and sprint fields.
   Confirm the fields. **Save & connect** is enabled only after a passing test with the current values.
4. **Sync now.** The tile shows "synced just now", or "Sync failed · code" with **Fix**. A failed
   sync keeps the pages it already read and resumes from the last good point next time.

**Disconnect** deletes the stored secret and keeps every row already read.

### Linking demo users to real accounts

A Jira account is matched to a person automatically when its email equals the person's work email.
Demo accounts use `@pulse.demo`, so in the demo nothing matches automatically:

1. After a sync, open **Unmatched accounts** on the Integrations screen (filter by tool if needed).
2. **Link to person** and pick the demo user, for example the Jira account of the engineer you show as
   Priya.
3. Mark automation accounts as **Bot** so they stay out of activity figures.

A link applies from the next read, with no re-sync: the person's Work items, Overview and Team
utilization then count those issues. **Unlink** reverses it. Live issues of unmatched accounts count
for the team ("Unassigned"), not for a person.

### The real team on live Jira (`scripts/live.sh`)

To see a real team's Jira work rather than the demo's, run `scripts/live.sh`. It runs beside the demo:

- its own database `pulse_live`, API on :8090, web on :5175, and key file `.pulse/live-secret-key`
- the real date as "today", so Jira's running sprint is the live sprint
- **no demo company**: the demo seed is off, so the database holds only the people you import

**First Admin.** On the first start (no Admin in the database yet) the script asks for your work email,
name and password, the password hidden and asked twice. It hands them to the backend as
`PULSE_ADMIN_EMAIL`, `PULSE_ADMIN_NAME` and `PULSE_ADMIN_PASSWORD`, and the backend creates that Active
Admin once:

- The password follows the password rules. It is stored hashed and never logged or audited.
- Once an Admin exists, the settings are ignored: they never reset a password or promote an account.
- Under the production profile a `@pulse.demo` address is refused.

The same variables work for any deployment started without the demo seed. A `pulse_live` created by an
earlier version of the script still holds the demo accounts: run `scripts/live.sh --reset` once. It asks
for the database name before it drops anything.

Then:

1. **Imports → Users.** Import the team (`name, email, role, manager_email, title, department, level`).
   - Each work email must be the one in the person's Jira profile (letter case does not matter), so the
     first sync matches them.
   - Give the team lead your Admin email as `manager_email`, and everyone else the lead's, so Team
     utilization shows the team.
2. **Connect and sync** as above, with **Personal access token** and a small project first. The first
   sync reads the project's whole history. At **Test connection**, also confirm the four Start/End
   field ids (see "Hours from the Start/End fields" below) before you save.
3. **Unmatched accounts.** Link anyone whose Jira email is hidden or different.

#### Hours from the Start/End fields

This team does not log work in Jira: worklogs and time spent are empty. It records time in the
datetime fields **Start Time (Dev)**, **End Time (Dev)**, **Start Time (QA)** and **End Time (QA)**, and
Pulse turns those into hours logged (change jira-derived-hours).

**Confirm the four field ids.** Test connection proposes them by name and type, next to story points
and sprint. On the internal Jira they are:

| Field | Id |
|---|---|
| Start Time (Dev) | `customfield_10200` |
| End Time (Dev) | `customfield_10201` |
| Start Time (QA) | `customfield_11000` |
| End Time (QA) | `customfield_10701` |

Choose **Use …** for each, run **Test connection** again (the values changed), then **Save**. A pair
with either id empty is off: its issues give no hours. The sync log shows the worklogs and intervals read,
and `truncatedWorklogs` (issues with more than the 20 worklogs the search returns).

**What "hours logged" means here.** For an issue with no Jira time tracking, each Dev or QA pair is the
time the issue spent between its Start and End, **in office hours**:

- only weekdays that are not company holidays, the person's leave or customer-visit days
- on each day, only the part inside the working-day window (default 10:00–18:00, Asia/Kolkata)
- a person's overlapping intervals that day (Dev and QA, or two issues at once) count once
- at most **Hours per day** (default 8) a day
- no End yet: up to the issue's resolution time if it is resolved, else up to now, never after today

The hours go to whoever was the issue's assignee when the interval started, so a Dev → QA hand-over
credits each side to the right person. An issue with any worklog or time spent uses its worklogs
instead, never both. It is elapsed time on the issue, not measured effort; read it that way.

These hours feed Hours logged and Days worked on **Effort**, and closed periods on **Team utilization**.
The live sprint's utilization still reads remaining hours, which this team does not set, so it stays at
0 %.

**Change the working-day window.** Rules & thresholds → **Staleness & capacity** → **Working day
starts** and **Working day ends** (the end must be after the start). The daily cap is **Hours per day**
in the **Monthly effort & extra-mile points** card. A change applies to open periods from the next read;
a closed period that is already frozen keeps its numbers until an Admin recalculates it (docs/calc.md
§7).

Performance index, appraisal score and most of Discipline stay empty or at their default for these
people, because those come from seeded inputs that real people don't have. The database holds real
employee data: keep it on this machine.

### Seed and live rows are both counted

Live rows are added next to the seed rows; neither replaces the other. After a live sync, team
totals, Work items and the issue counts of §5 include the live issues, so the demo numbers no longer
match the prototype. Work items show each row's source (Seed or Jira) and can filter by it. The
oracle comparisons run on seed rows only. To show the prototype's numbers again, run
`scripts/demo.sh --reset` (the secret key is kept; the connection and live rows are not).

### Deleting a user with linked accounts

Deleting a user (Users & roles) also unlinks their tool accounts and clears the stored username,
display name and email, here and in the live rows that copied them. Their live issues stay in team
totals with no name; the deletion dialog lists this. The person still exists in Jira, so the next sync
reads their account again as a new **unmatched** account. Leave it unlinked.
