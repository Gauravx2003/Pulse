# Action center, people signals, Progress and team wins

Pulse should tell a manager **what to do next** and let every employee **see their own progress** — without anyone hunting through ten screens.

## Action center (managers and Admin — their landing page)

| Section | What it shows | Source |
|---|---|---|
| KPIs | Items that need you (high priority count), people to watch, current-sprint forecast (% of remaining work that fits), team pulse, next-sprint capacity in SP | All modules |
| **Do today** | One prioritized list (High / Medium / Low) with an action button: overloaded people, stale issues, code-only days, 1:1s overdue, low weekly pulse, extra-mile points to confirm, customer visits to verify or reports overdue, training reviews and mentor blockers, mentors behind on sessions, open 360° reviews, high-severity bugs and disputes, rubber-stamp PR approvals, appraisal drafts / approvals, failing integrations (Admin) | Work items, Effort, 1:1s, Pulse, Customer visits, Training, Reviews, Bugs, PRs, Appraisal, Integrations |
| **People signals** | Early warnings per person with the reason and a suggested next step; risk *High / Watch / Low / OK* | See below |
| **Sprint forecast** | Per person: remaining hours vs focus hours left; open issues are filled in due-date order and what does not fit is listed as *likely to carry over* | Jira |
| **Next sprint capacity** | Working days − leave − planned customer visits − training and mentoring hours → focus days × velocity (done SP per day, last 3 sprints) = suggested SP per person and for the team | HRMS, visits, training, Jira |
| **Skills coverage** | Areas × people from PRs and Jira components (6 months); *Single point of failure* / *Thin* / *Covered* with a pairing suggestion | TFS, GitHub, Jira |
| Team pulse · Team wins | 6-week mood and workload trend; recent wins | Pulse, kudos, visits, training, bugs |
| **Weekly digest** | Preview of the Monday email / Slack summary | — |

### People signals (signals for a conversation, never a rating)

Sustained overload (over 100% in 2 of the last 4 sprints) · Under-used (below 60% in 2 of 3 sprints) · Weekend work while overloaded · High unplanned work (≥ 30%) · Jira updates lagging (Discipline < 3) · 1:1 overdue (> 10 working days) · Pulse check-in low (mood ≤ 2 or workload 5) or mood falling · Performance index falling · No recognition this quarter · Training behind (≥ 2 overdue modules). New joiners on onboarding are not flagged for utilization.

## Progress (every employee; managers can open anyone they manage)

- **Next steps** — collected from every screen: stale issues, code-only days, PR titles to fix, reviews to give, training feedback to acknowledge / sign off / check-ins due, visit reports, weekly pulse, OKRs off track.
- **Achievements** earned from data: Daily updates, PR standard, Great reviewer, Commitment keeper, Proactive fixer, Delighted customer, Training completed, Mentor, Key result achieved — each tied to a culture value and shown on **Team wins**.
- Performance index trend (4 quarters), **utilization by sprint** with the overall average, culture values Q2 → Q3 (biggest gains first), OKRs, PR standard by month, training ratings.
- **Weekly pulse** — two questions every Friday (mood 1–5, workload 1–5, optional note). The manager sees it; a low answer notifies the manager and appears in the Action center.
- **Team wins** — kudos, 5-star customer visits, completed training, proactive fixes: visible to the whole team.

## Overview, Work items and Team utilization by sprint, month or release

The period control on these three screens adds **Release** (NMS 8.0 – 8.3, from the Jira / TFS fix version) to Sprint and Month. The active sprint shows the live view (remaining work, stale signals, forecast). A past sprint, a month or a release shows: issues done / committed, story points and commitment %, carried over, median cycle time, estimate accuracy, hours logged and **utilization** (hours logged ÷ working days × 8 h), a per-sprint summary, the release card (sprints, share of the release), and the full issue list from Jira sprint history with filters (Done, Carried over, Bugs, Open now) and an issue timeline. Overview always shows **Utilization by sprint** with the overall average across finished sprints.
