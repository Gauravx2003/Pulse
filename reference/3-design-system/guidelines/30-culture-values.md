# Measuring the 11 DO culture values

Values are measured by **observed behaviour** plus data. Nine values combine a behaviour-anchored 360 rubric (60%) with scored data signals (40%); Moral & Ethics is 360° only; **Discipline** is calculated from Jira and GitHub. The **culture pillar** is the weighted average below (weights are editable in Rules & thresholds and must total 100%).

## Method

1. **Rubric (1–5), quarterly 360** — manager, self, 3–5 internal-team and 2–4 cross-department reviewers rate each value against written anchors (see *360° reviews*). Value score = manager 40% · internal team 25% · cross-department 15% · self 20%; groups below 3 responses are merged or hidden.
2. **Data signals** — Jira, TFS / GitHub, support desk, customer visits, training, weekly pulse and Confluence parameters, each scored 1–5 with bands, make up **40%** of nine values (see *How each value score is calculated*). Moral & Ethics stays 360° only; Discipline is 100% data.
3. **Evidence log** — kudos, 1:1 notes, manager examples and confirmed extra-mile work (+0.05 per point, max +0.3 per value per quarter).

## The values

| # | Value | Weight | What to look for | Data |
|---|---|---|---|---|
| 1 | **Outcome Ownership** | 15% | Finishes work end-to-end, follows up without reminders, owns mistakes | On-time delivery %, accepted without rework %, manager follow-ups needed |
| 2 | **Moral & Ethics** | 10% | Honest reporting, data privacy, responsible AI use | Privacy / policy incidents, peer trust rating, AI-assisted PRs disclosed and reviewed — context only, never auto-scored |
| 3 | **Decision Making** | 10% | Timely, data-based decisions with clear reasoning | Decisions logged (ADRs, tickets), time to decide, decisions reversed |
| 4 | **Problem Solving** | 10% | Finds root causes, not quick patches | Recurring issues permanently fixed, RCAs, reopen rate |
| 5 | **Risk Appetite** | 5% | Proposes and owns new ideas, experiments responsibly | Ideas proposed → piloted → delivered |
| 6 | **Adaptability** | 5% | Learns new tools and processes (incl. AI) and applies them | Time to productivity on a new tool, trainings applied |
| 7 | **Agility in Execution** | 10% | Delivers fast **with** quality | Median cycle time combined with rework rate |
| 8 | **Collaboration** | 10% | Helps others, shares knowledge | Peer rating, docs / templates / prompts shared, cross-team issues |
| 9 | **Resilience** | 5% | Steady performance under pressure, recovers from setbacks | Delivery consistency in high-load sprints, manager examples |
| 10 | **Resourcefulness** | 10% | Achieves results with limited resources, uses AI smartly | Hours or cost saved, processes automated or improved |
| 11 | **Discipline** | 10% | Keeps Jira updated daily and accurately, follows agreed processes, raises delays early | Measures a–g below |
| | **Total** | **100%** | | |

## Discipline — calculated from Jira and GitHub

| | Measure | Formula / source | Good |
|---|---|---|---|
| a | **Daily Update Compliance** | Days with a meaningful update (status change, comment, worklog or linked commit by the assignee) on active tickets ÷ working days with active tickets × 100. Leave and holidays excluded. | ≥ 85% |
| b | **Stale tickets** | Assigned open tickets with no update for **more than 2 working days** | 0 |
| c | **Proactive re-planning** | Due-date changes made before the due date ÷ all due-date changes (Jira changelog `duedate`) | ≥ 80% |
| d | **Status accuracy** | Tickets whose status matches reality — e.g. PR merged but still *In Progress*, *Done* but reopened / open PR | ≥ 90% |
| e | **Time logging accuracy** | Worklogs entered the same or next working day; bulk entries (≥ 3 days logged at once at week / sprint end) counted | ≥ 70% same-day, < 3 bulk |
| f | **Early blocker reporting** | Blockers (Flagged, Blocked status or "is blocked by" link) raised within 1 working day of the blocking event | ≥ 70% |
| g | **Process adherence** | Average of definition-of-done checklist %, PRs reviewed before merge %, stand-up attendance % | ≥ 85% |
| h | **Attendance & leave** | Monthly HR upload, Q3: average of leave with notice %, office policy % and punctuality % (see *Attendance and leave*). Sick, emergency and approved leave never count | ≥ 85%, no absence |

**Rating** — base score from Daily Update Compliance, then adjusted by b–h:

| Score | Guide | Base band (a) |
|---|---|---|
| 5 | 95%+ compliance, no stale tickets, always re-plans before due date | ≥ 95% |
| 4 | Rare stale tickets, mostly proactive | 85–94% |
| 3 | Some stale tickets, sometimes updates dates late | 70–84% |
| 2 | Frequent stale tickets, often changes dates after deadline | 50–69% |
| 1 | Manager must chase for updates regularly | < 50% |

Adjustments: stale ≥ 3 → −0.5 (a 5 needs zero stale) · re-planning < 50% → −0.5, < 80% → −0.25 · status accuracy < 90% → −0.25 · time logging < 70% same-day or ≥ 3 bulk → −0.25 · early blockers < 70% → −0.25 · process < 85% → −0.25 · attendance < 85% → −0.25, < 70% → −0.5, each day absent without leave → −0.25. The score never drops more than 1 below the base band. A manager may **override** with a written reason, which the employee sees.

## Rules

- Moral & Ethics is never scored from tool data.
- Scores are shown to the employee with the anchors, the data and all written examples; anonymous peer comments are grouped, not attributed.
- Managers calibrate value scores together once a half-year.

## How each value score is calculated (shown on every value's detail view)

**Value score = 360° rating × 60% + data signals × 40% + extra-mile bonus** (capped at 5). The data share (default 40%) is set by HR in *Rules & thresholds → Culture value weights*. **Moral & Ethics** is 360° only; **Discipline** is data only (rule a–g). The culture pillar is the weighted average of the 11 values.

1. **360° rating** — behaviour-anchored 1–5 from the last published cycle, weighted by rater group: manager 40%, internal team 25%, cross-department 15%, self 20%. *Not observed* answers are left out.
2. **Data signals** — each parameter is turned into 1–5 with its bands, then averaged by its weight. Parameters with no data, or where there was no opportunity (e.g. no incidents this quarter), are left out and the other weights re-normalised, so nobody is penalised for work they were never given.
3. **Extra-mile bonus** — confirmed extra work: +0.05 per point, split across the values the item credits, max +0.3 per value per quarter.

| Value | Data parameters (weight within data) · bands for 5 / 4 / 3 / 2 |
|---|---|
| Outcome Ownership | Commitment met, last 3 sprints 35% (≥ 95 / 85 / 70 / 55%) · Issues carried over 20% (≤ 0 / 1 / 2 / 4) · Open bugs in own code 20% (≤ 0 / 1 / 2 / 3) · Manager follow-ups 10% (≤ 0 / 1 / 2 / 3) · Customer visit CSAT 15% (≥ 4.8 / 4.3 / 3.8 / 3.0) |
| Moral & Ethics | None — 360° with examples only; status accuracy, policy incidents and AI-use disclosure shown as context |
| Decision Making | Decisions documented (ADRs) 40% (≥ 5 / 4 / 2 / 1) · Median time to decide 35% (≤ 1 / 2 / 3 / 4 d) · Decisions reversed 25% (≤ 0 / 1 / 1 / 2) |
| Problem Solving | Bugs fixed without reopen 35% (≥ 98 / 92 / 85 / 75%) · Others' bugs fixed proactively 30% (≥ 3 / 2 / 1) · RCAs written 20% (≥ 3 / 2 / 1) · Support tickets resolved 15% (≥ 4 / 3 / 2 / 1) |
| Risk Appetite | Spikes / experiments 40% (≥ 3 / 2 / 1) · Ideas proposed → piloted 35% (≥ 3 / 2 / 1) · Risks raised in planning 25% (≥ 4 / 3 / 2 / 1) |
| Adaptability | Mid-sprint work absorbed 35% (≥ 4 / 3 / 2 / 1) · Areas worked in 25% (≥ 4 / 3 / 2 / 1) · Training rating 20% (≥ 4.5 / 3.75 / 3 / 2.25) · Time to productivity on a new tool 20% (≤ 4 / 7 / 10 / 13 d) |
| Agility in Execution | Median cycle time 35% (≤ 2 / 3 / 4.5 / 6 d) · Stale share of open work 25% (≤ 0 / 15 / 30 / 50%) · Median PR size 20% (≤ 200 / 300 / 400 / 600 lines) · Logged ÷ estimate 20% (≤ 110 / 125 / 140 / 160%) |
| Collaboration | PR reviews given 25% (≥ 15 / 10 / 6 / 3) · Review comments to standard 20% (≥ 85 / 70 / 55 / 40%) · Kudos received 20% (≥ 3 / 2 / 1) · Helped others (takeovers, help, mentees) 20% (≥ 3 / 2 / 1) · Cross-team issues 15% (≥ 4 / 3 / 2 / 1) · Office and on-site presence 15% (≥ 95 / 85 / 70 / 55%, attendance upload) |
| Resilience | Incidents and escalations handled 35% (≥ 3 / 2 / 1) · Delivery in overloaded sprints 40% (≥ 90 / 80 / 65 / 50%) · Mood in heavy weeks 25% (≥ 4 / 3.5 / 3 / 2.5) |
| Resourcefulness | Useful tools and scripts 35% (≥ 3 / 2 / 1) · Docs, templates, prompts shared 30% (≥ 6 / 4 / 2 / 1) · Hours saved by automation 35% (≥ 30 / 20 / 10 / 3 h) |
| Discipline | a Daily update compliance (base band) adjusted by b stale tickets, c proactive re-planning, d status accuracy, e time logging, f early blockers, g process adherence (includes the PR standard), h attendance & leave (monthly upload) |

The detail view shows the formula with the person's real numbers, the rater table, every parameter with its source, measured value, bands, 1–5 score and weight, and each extra-mile item with the bonus it adds. A *Calculation rules for all 11 values* table sits at the bottom of the Culture values screen.
