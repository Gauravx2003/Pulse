# Sprint metrics: tasks, staleness, days assigned, utilization

How Pulse turns the active Jira sprint into the four headline numbers. All thresholds are editable under **Rules & thresholds**; defaults below.

## 1. Data pulled from Jira

| What | Jira source |
|---|---|
| Active sprint(s) per board | `GET /rest/agile/1.0/board/{boardId}/sprint?state=active` |
| Issues in the sprint | `GET /rest/agile/1.0/sprint/{sprintId}/issue?fields=assignee,status,statuscategorychangedate,updated,duedate,timeoriginalestimate,timeestimate,timespent,customfield_<storyPoints>,issuetype,priority` |
| Status history | `GET /rest/api/3/issue/{key}/changelog` |
| Worklogs | `GET /rest/api/3/issue/{key}/worklog` |
| Comments | `GET /rest/api/3/issue/{key}/comment` |
| Real-time updates | Webhooks: `jira:issue_updated`, `worklog_created`, `comment_created`, `sprint_started`, `sprint_closed` |

Capacity inputs come from HRMS (holidays, leave) and calendar (full-day OOO); standard working day = 8 h, **focus hours = 6 h/day** (default, per team).

## 2. Tasks assigned

`assigned = count(issues in active sprint where assignee = person)` — split into To Do / In Progress / In Review / Done, plus story points. Sub-tasks count toward their own assignee, not the parent's.

## 3. Tasks not updated (staleness)

An issue's **last meaningful activity** = the latest timestamp among:
- a status transition (changelog),
- a worklog added,
- a comment,
- a linked commit / PR event (GitHub/GitLab, matched by issue key),

**made by the assignee**. Jira's `updated` field is not used alone, because bots, PMs and field syncs touch it.

`idle_working_days = working days between last activity and now` (excluding weekends, public holidays and the person's leave).

| Signal | Rule (default) |
|---|---|
| **Stale** | status ∈ {In Progress, In Review} and `idle_working_days > 2` (more than 2 working days) |
| **Not started** | status = To Do and sprint is > 40% elapsed |
| **Overdue** | `duedate < today` and status ≠ Done |
| **No estimate** | `timeoriginalestimate` and story points both empty |

Stale ratio = stale ÷ open issues. Alert at > 30%.

## 4. Days of work assigned (verified against dates and time)

Remaining effort per issue, first available of:
1. `timeestimate` (Jira remaining estimate, hours),
2. `timeoriginalestimate − timespent`,
3. `storyPoints × team hours-per-point` (calibrated from the last 3 sprints: `Σ timespent ÷ Σ completed SP`).

`work_assigned_days = Σ remaining_hours ÷ focus_hours_per_day`

**Verification** (flags estimates that no longer match reality):
- `logged_vs_elapsed = timespent ÷ (working hours since moved to In Progress × 0.75)` — below 0.3 means work is not being logged or not happening; shown as "Low logged time".
- `estimate_drift = (timespent + timeestimate) ÷ timeoriginalestimate` — above 1.5 flags "Estimate grew 50%+".
- A task In Progress for more than 2× its original estimate in working days is flagged "Longer than estimated".

## 5. Capacity and utilization

`capacity_days_left = working days left in sprint − leave days − holidays` (partial days allowed; meetings > 50% of a day from calendar reduce it by 0.5).

`utilization % = work_assigned_days ÷ capacity_days_left × 100`

| Band | Range | Badge |
|---|---|---|
| Under | < 60% | `info` · Under |
| Healthy | 60–85% | `ok` · Healthy |
| At capacity | 85–100% | `accent` · At capacity |
| Over | 100–120% | `warn` · Over |
| Critical | > 120% | `danger` · Critical |

Example (the dashboard): 14 issues, 8 open, remaining 69 h ÷ 6 h = **11.5 d**; 10 working days left − 1 day leave = **9.0 d**; 11.5 ÷ 9.0 = **128% · Critical**, over by 2.5 d.

**Daily utilization** (`TeamHeatmap`) spreads each issue's remaining hours across its planned days (start → due date, or evenly to sprint end) and divides by that day's focus hours.

## 6. Sprint-end metrics (feed the Delivery score)

- Commitment met % = SP done ÷ SP committed at sprint start (scope added mid-sprint shown separately).
- Carry-over = issues rolled to next sprint.
- Cycle time = In Progress → Done, median, working days.
- Reopen rate = issues moved Done → not Done within 14 days.
