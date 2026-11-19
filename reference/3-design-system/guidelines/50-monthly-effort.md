# Effort, unplanned work and extra-mile points

The **Effort** screen (formerly Monthly effort) shows, per person and **per sprint or per month**, how many hours and days were worked, how much of it was unplanned, who gave that work, and turns confirmed extra work into culture-value credit.

## Hours and days worked (Jira + GitHub)

| Number | Rule |
|---|---|
| Hours logged | Σ Jira worklogs by the person with `started` inside the month |
| Days worked | hours logged ÷ hours per day (default 8, editable) |
| Working days | weekdays in the month − public holidays − approved leave (HRMS), up to today |
| Active days | working days with a worklog **or** GitHub activity (commit, PR, review); weekend work is shown separately |
| Code-only days | GitHub activity but no Jira worklog → hours are missing from utilization; the employee gets a "Log time" prompt |
| Weekend work | worklogs or commits on Sat/Sun, usually incidents — shown, never expected |

The **daily log** calendar shows each day: hours logged, code-only, long day (> 100% of hours per day), weekend work, leave and holidays.

## What counts as unplanned or extra work

| Type | How it is detected | Default points (when Done) | Culture values credited |
|---|---|---|---|
| Added mid-sprint | Jira changelog: `Sprint` field set after the sprint's start date | 1 | Adaptability, Agility in Execution |
| Incident / hotfix | PagerDuty / Opsgenie assignment, or issue type Incident, plus the linked GitHub fix | 2 | Resilience, Outcome Ownership |
| Took over | Jira changelog: assignee changed to this person mid-sprint | 1 | Collaboration, Outcome Ownership |
| Helped on someone else's issue | GitHub commits or substantive reviews on a branch whose Jira issue is assigned to someone else | 1 | Collaboration |
| Untracked useful work | Merged GitHub PR with no Jira key in branch, title or commits | 1 | Resourcefulness |
| Reviews for others | GitHub reviews on PRs authored by others | 0.5 per 5 reviews | Collaboration |
| Extra support ticket | Resolved support-desk ticket taken beyond sprint work | 1 | Outcome Ownership, Collaboration |
| Proactive fix of someone else's bug | Self-assigned bug whose code owner is someone else, resolved | 1.5 | Problem Solving, Collaboration |

**Who gave it** = the Jira changelog author who added the issue to the sprint or changed the assignee, the PagerDuty escalation, or "Self-initiated" for untracked work. The manager view ranks requesters by unplanned hours, so over-use of interrupts is visible.

## From points to culture values

1. Items are detected automatically and shown as **Awaiting manager**.
2. The manager **confirms** or **rejects** each done item (evidence shown on click). Only confirmed items score.
3. Each confirmed point adds **+0.05** to each credited value, split across values, capped at **+0.3 per value per quarter**; monthly points are capped at **6** per person.
4. The culture screen shows the 360 score, the bonus and the resulting score; the item also appears in that value's evidence log.

## Guardrails

- An unplanned share above 30% shows a **planning** signal for the manager, not a performance issue for the employee.
- Points reward the work, never the hours: caps stop extra hours from becoming the goal.
- Moral & Ethics never receives points from tool data.


## Period: sprint or month

Every screen with period data — **Effort**, **Bugs & support**, **Pull requests** and **Customer visits** — has the same period control: **Sprint | Month** (Pull requests also **All**) and a dropdown of sprints (Sprint 31 – Sprint 43, two weeks each, Sprint 43 is the active three-week sprint) or months (April – September 2026). The choice is shared: pick *Sprint 42* on Effort and Bugs & support opens on Sprint 42 too. A sprint covers Monday of its first week to the Sunday before the next sprint, so weekend work is never lost between sprints. Each screen also has a **Sprint by sprint / Month by month** table for the person or the team; click a row to open that period.
