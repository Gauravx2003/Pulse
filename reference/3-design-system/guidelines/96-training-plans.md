# Training plans

Week-by-week plans for **new joiners (trainees)** and for **targeted development** of existing people. Checkpoint reviews use the **same review form, notifications and results view as 360° feedback**.

## Plan library (manager / HR)

A plan has: name, who it is for, duration in weeks, description, the culture values it builds, checkpoint days (e.g. 14, 30, 60, 90) and **modules**. Each module has a title, type (*Course, Reading, Shadowing, Hands-on task, Assessment, Meeting*), week, hours and an optional resource/link.

| Action | Notes |
|---|---|
| **New plan** | Name, duration, at least one module required; module weeks within the duration; checkpoints within the plan length |
| **Edit** | Add / remove / reorder modules; people already on the plan keep the version they were assigned |
| **Duplicate** | Copy as a starting point for a variant |
| **Delete** | Confirmation; current assignments keep their copy and continue |

Seed plans: *Software Engineer onboarding* (12 weeks), *QA engineer onboarding* (8 weeks), *Jira discipline refresher* (2 weeks), *Ownership & decision making* (4 weeks), *Code review & PR standard* (2 weeks). Each plan also has an **assessment type** and a **pass mark**.

## Assigning

**Assign training plan** → choose a plan, a **mentor**, a start date and **one or many people** (new joiners are listed first and tagged). People already on that plan are skipped. The trainee and the mentor each get an **in-app notification**. Checkpoints due within 7 days are requested immediately; later ones show *Scheduled* and are requested with **Request review**.

Manager actions on an assignment: open it, tick modules, **Edit assignment** (mentor, start date), **Pause / Resume**, **Remove** (confirmation; feedback already given stays).

## Following a plan (trainee)

**Training plans → My training**: progress %, overdue modules, mentor and end date; modules grouped by week with due dates. The trainee (and mentor or manager) ticks modules as done. The Overview shows a small training card.

## Checkpoint reviews — same feedback system

- Each checkpoint creates review requests for **self, manager and mentor**, using the 360° form (10 values 1–5 or *Not observed*, examples for 1/2/5, *keep doing* / *start doing*).
- Reviewers get the request as an **in-app notification** and in *Reviews → 360° feedback → To give* (tagged *Training checkpoint · Day N*).
- When all are submitted the checkpoint is *Completed*; the manager and trainee open **Feedback** to see the grouped results.

## Suggestions from reviews and feedback

- **New joiners without a plan** (joined < 3 months) are suggested the onboarding plan.
- For everyone else, Pulse looks at their **three lowest culture values** (from 360° feedback, Discipline and reviews) and suggests plans that build those values — e.g. Discipline 2.0 → *Jira discipline refresher*. One click assigns it.
- Employees see *Suggested from your reviews* on their own training page.

## Access

| Permission | Manager | Admin | Employee |
|---|:-:|:-:|:-:|
| `training.manage` — library CRUD, assign, edit, pause, complete, remove, request checkpoints | ✓ | ✓ | |
| Own plan: view, tick modules, see checkpoint feedback | ✓ | ✓ | ✓ |
| Mentor: tick the trainee's modules, answer checkpoint reviews | via assignment | | |

Trainees are **Employees** on a training plan — there is no separate trainee login. Demo (password `demo123`): `rohit@` (two plans at once), `anjali@` (one completed, one in progress), `neha@` (failed first assessment, extended), `kiran@` (manager review pending), `dev@` (final feedback to sign off); mentor `sara@`; manager `rahul@`.

## Lifecycle: period → assessment → review → sign-off → rating

Every assignment follows the same steps, shown as a stepper on the plan:

| Step | Who | What happens |
|---|---|---|
| Assigned | Manager | One or more plans for one or more people; mentor, start date, **end of the training period** (default = plan length), assessment window (default 3 working days after the period), check-in cadence |
| Training period | Trainee, mentor | Modules ticked; daily / weekly / monthly check-ins verified by the mentor or manager; checkpoint reviews inside the period |
| Assessment | Mentor or manager | After the period: date, type (written test, practical task, code review, presentation, panel), score 0–100 and evidence. **Pass** at the plan's pass mark (default 70%) |
| Mentor & manager review | Mentor, then manager | Each gives a 1–5 rating, strengths and what to improve. The manager decides **Completed**, **Extend** (new end date, new assessment — the attempt is kept in history) or **Not completed**. A failed assessment cannot be marked Completed |
| Trainee sign-off | Trainee | Reads the assessment and both reviews, **agrees or disagrees** (comment required), rates the training (1–5) and mentor support (1–5). A disagreement is flagged for the manager to discuss |
| Completed | — | The final training rating is locked in |

- **Trainees acknowledge every feedback item** — check-in comments, checkpoint reviews, the assessment and final reviews — with *Acknowledge* or *I disagree* + comment. Unacknowledged items are counted on the plan and in the menu badge.
- **Several plans at once.** A person can be on multiple plans; *My training* shows a plan picker with each plan's stage, and the manager's table groups plans by person. Assigning warns when a person's training load goes above ~16 h/week.
- **Training rating (1–5)** = Assessment 40% (score ÷ 20) + Mentor review 20% + Manager review 20% + Check-ins verified 10% (of closed periods) + Checkpoint reviews 10%. Missing parts are left out and the rest re-weighted; it is *Provisional* until the assessment and both reviews exist. Bands: Outstanding ≥ 4.5 · Strong ≥ 3.75 · Meets ≥ 3 · Needs support ≥ 2.25 · Not yet.
- **Overall training rating** per person = plan-length-weighted average of completed plans (provisional ones if none are complete yet). It feeds the Growth pillar (averaged with the growth score) and the appraisal evidence.
- **Plan library** shows each plan's pass rate, average outcome rating and the trainees' own rating of the plan.
- Weights, default pass mark, assessment window and sign-off window are in **Rules & thresholds → Training rating & sign-off**.

## Mentoring (mentor = an Employee chosen by the manager)

The manager assigns a **mentor** with every plan (the mentor list shows how many active trainees each person already mentors) and how often they meet: **Daily**, **Twice a week** or **Weekly** (default from the plan's check-in cadence). The mentor does the day-to-day work, the same way reviewers work in the 360° system:

| Mentor does | Where |
|---|---|
| Day-to-day **sessions** — date, duration, call / video / in person / chat, what was discussed, next steps, optional blocker to the manager | *Log session*; the trainee is notified and sees the notes under **Mentor sessions** |
| Verifies check-ins | *Verify* or *Needs work* with feedback |
| Checkpoint ratings | Same form and notifications as 360° feedback |
| Takes the **assessment** after the period | *Record assessment* |
| Gives the **mentor rating** | *Write mentor review* |
| Replies when a trainee disagrees with feedback | *Reply* |

**Training plans → Mentoring** (shown to anyone who mentors):

- KPIs: trainees, check-ins to verify, assessments to take, ratings to give (mentor + checkpoint), sessions in the last 7 days against the plan (e.g. 5 of 10).
- **Pending for you** — every open item, oldest first, with one action button: check-in to verify, assessment to take, mentor rating, checkpoint rating, session requested by the trainee, trainee disagreed, sessions behind plan.
- **My trainees** — a card per plan: stage, progress, check-ins, sessions, rating, last session and its next steps.
- **Mentoring activity** — a timeline of everything on those plans (check-ins, verifications, sessions, modules, assessments, ratings, sign-offs, acknowledgements, replies) with filters.

Trainees can **Ask for a session**; it appears in the mentor's pending list until a session is logged.

**Manager view (Trainees & assignments → Mentors):** per mentor — active trainees, check-ins to verify, assessments, ratings pending, open requests and disagreements, sessions in the last 7 days vs plan, last session, and how trainees rated the mentor at sign-off. Click a mentor to see their pending items and **Send reminder**. Demo mentors: `sara@` (5 plans), `priya@`, `arjun@`.
