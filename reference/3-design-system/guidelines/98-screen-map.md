# Screen map and end-to-end flows

How every screen connects, who sees it, and where each number comes from. Verified in the prototype for Admin, Manager, Employee (including new joiners on a training plan — trainees are Employees, not a separate login) and cross-department employee, at desktop, laptop and phone widths.

## Screens by role

| Screen | Tabs / detail views | Employee | Manager | Admin |
|---|---|:-:|:-:|:-:|
| Action center (managers, Admin — landing) | do today · people signals · sprint forecast · next-sprint capacity · skills coverage · team pulse · team wins · weekly digest | – | ✓ | ✓ |
| Progress | next steps · achievements · index trend · utilization by sprint · culture Q2→Q3 · OKRs, PRs, training · weekly pulse · team wins | own | reports | all |
| Overview | sprint, month or release · utilization by sprint (overall) · alert · needs-attention issues → issue drawer · bugs & support · training · culture radar · road map | own | reports | all |
| Work items | sprint, month or release · sprint summary · issue history with filters and timeline · live: filters, search, sort · issue drawer (date & time verification, activity, nudge) · daily load | own | reports | all |
| Pull requests | Team / Person · sprint, month or all · sprint-by-sprint / month-by-month table · standard checks · review comments · PR list → PR detail (checks, reviewers, threads) · PR standard | own | reports | all |
| Effort | Team / Person · sprint or month · sprint-by-sprint / month-by-month · daily log · unplanned & extra work → evidence · confirm / reject | own | + confirm | + confirm |
| Bugs & support | Team / Person · sprint or month · history table · open bugs in own code (take, dispute) · fixed for others · pick up · support queue | own | + review dispute | all |
| Customer visits | Team / Person · Q3 / year · compared with the team · visits → visit detail · plan / log / edit / delete · report · customer feedback (account owner) · verify | own + feedback to record | reports + verify | all |
| Attendance | Team / Person · month, sprint or quarter · KPIs · people table (Good / Watch / Talk) · day-by-day grid · calendar · leave list · late check-ins · month by month · how it feeds culture values · monthly uploads | own | reports | all + upload |
| OKRs | Person / Team · quarter dropdown · create, edit, check-in, delete · import / export CSV | own | + team | all |
| Training plans | My training (plan picker, stepper, check-ins, mentor sessions, feedback acknowledgement, sign-off, rating) · Mentoring (pending for you, my trainees, sessions, activity) · Trainees & assignments (needs your action, assessment, final review, ratings by person) → plan detail · Plan library (pass rate, ratings) | own / mentor | + library, assign, verify | all |
| Road map | milestones, promotion readiness, skills | own | reports | all |
| Culture values | 11 values · Discipline (a–g, override) · anchors · ratings, data, evidence | own | reports + override | all |
| Reviews | Performance review · 1:1s & feedback · 360° feedback (To give, My feedback, Team, Cycles) → review form, results | own | + assign 360°, calibration | + cycles |
| Team utilization | sprint, month or release · per-person commitment and utilization · live: heatmap (onboarding shown as *Training*) · people table · rebalance | – | ✓ | ✓ |
| Appraisal & increments | bands, budget, evidence panel, submit / approve / release | – | propose | approve |
| Users · Roles & permissions | directory CRUD, import / export CSV · permission matrix, custom roles | – | own team | ✓ |
| Data imports | upload cards (OKRs, attendance, users, customer visits, holidays) → choose file → check → import → undo · exports (incl. appraisal for payroll) · where each module gets its data · upload history | – | OKRs, users, visits | ✓ |
| Integrations · Rules & thresholds | credentials, config, sync · all thresholds and weights | – | – | ✓ |

## End-to-end flows

1. **Jira / GitHub → workload**: issues, worklogs and commits → Work items, stale signals, days assigned, utilization → Overview, Team utilization, Discipline (a–g) → Culture values → Performance index → Appraisal.
2. **Extra work → culture credit**: mid-sprint, incidents, takeovers, support tickets, proactive fixes → Effort → manager confirms → +points → Culture values (bonus) → Performance index.
3. **360°**: manager assigns reviewers (A assigns C to review B) → C's bell notification → review form → manager sees *Submitted* → Admin publishes → B's results and culture values update → Appraisal (360 part).
4. **Training**: manager assigns plan + mentor → trainee notified → modules ticked → daily / weekly / monthly check-in → mentor verifies or sends back → trainee sees feedback → checkpoint reviews (same 360 form) → Performance review. New joiners show *Onboarding* in Overview and Team utilization and are not counted as under-utilized.
5. **Appraisal**: performance index (4 quarters) + 360° + manager review + Discipline → band → recommended % (max 20%) → manager proposes → HR approves → letters released.
6. **Attendance**: HR uploads the monthly CSV → check (errors, warnings, replace / merge) → Attendance screen → leave out of capacity · Discipline rule h · Collaboration office presence → Performance index. Missing months and attendance patterns appear in the Action center.
7. **Pull requests**: TFS / GitHub PRs and review threads → PR standard % and review comment quality → Discipline (g), Collaboration signals, appraisal evidence.
8. **Customer visits**: plan (capacity drops) → visit report → account owner records CSAT → manager verifies → points (own monthly cap) → culture values → performance index → appraisal.
6. **Integrations**: Admin adds credentials → Test connection → Save & connect → sync log → data appears on the screens above; failures (e.g. HRMS 401) show *Fix* → change credentials.

## Consistency rules checked

- A person's utilization, stale count and Discipline are the same number on Overview, Work items, Team utilization and Culture values.
- Days before a person joined are shown as “—” and never counted as working days.
- Trainees on an active onboarding plan are excluded from rebalance suggestions and under-utilization counts.
- Onboarding plans are suggested only to new joiners; development plans come from each person's three lowest culture values.
- Every list has an empty state; no screen shows NaN, undefined or blank cards for people without data.


## Period: sprint or month

Every screen with period data — **Effort**, **Bugs & support**, **Pull requests** and **Customer visits** — has the same period control: **Sprint | Month** (Pull requests also **All**) and a dropdown of sprints (Sprint 31 – Sprint 43, two weeks each, Sprint 43 is the active three-week sprint) or months (April – September 2026). The choice is shared: pick *Sprint 42* on Effort and Bugs & support opens on Sprint 42 too. A sprint covers Monday of its first week to the Sunday before the next sprint, so weekend work is never lost between sprints. Each screen also has a **Sprint by sprint / Month by month** table for the person or the team; click a row to open that period.
