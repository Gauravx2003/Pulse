# Integrations

Each integration is shown as an `IntegrationTile` that says what it reads. Pulse only **reads**, except the explicit nudge and kudos actions.

| Tool | Auth | Data read | Feeds |
|---|---|---|---|
| **Jira Software / Jira Cloud** | OAuth 2.0 (3LO) or API token; webhooks | Boards, active sprints, issues, status changelog, worklogs, comments, estimates, story points | Tasks assigned, staleness, days assigned, utilization, Delivery, Quality |
| **Azure DevOps Boards** | OAuth / PAT | Iterations, work items, history | Same as Jira (alternative) |
| **Microsoft TFS / Azure DevOps Server (on-premises)** | Personal access token or Windows (NTLM) service account | Pull requests, reviewers and votes, review threads and comments, builds, linked work items | Pull requests screen, PR standard, review comment quality, Discipline (g), Collaboration |
| **GitHub / GitLab / Bitbucket** | GitHub App / OAuth | PRs, reviews, commits (matched to issue keys) | Last-activity for staleness, review contribution, Agility, Collaboration |
| **Confluence / Notion** | OAuth | Pages authored (design docs, RFCs, RCAs) | Decision Making, Problem Solving evidence |
| **Slack / Microsoft Teams** | App install | `/kudos` messages, reactions on kudos | Culture evidence log; sends stale-task nudges and weekly digests |
| **Google Calendar / Outlook** | OAuth | Full-day OOO, meeting load | Capacity |
| **HRMS (Keka, Darwinbox, BambooHR, Zoho People)** | API key | Employees, reporting lines, holidays, approved leave, role level | Capacity, org structure, level-based normalisation |
| **ServiceNow / Freshdesk / Zendesk** | API key | Tickets resolved, CSAT | Customer impact |
| **PagerDuty / Opsgenie** | API key | On-call shifts, incidents acknowledged/resolved | Capacity (on-call reduces focus hours), Resilience evidence |
| **CI/CD (GitHub Actions, Jenkins)** | Token | Deployments, failed changes | Team-level DORA metrics |

## Sync rules

- Webhooks for Jira, GitHub and Slack (near real-time); hourly poll fallback; nightly full reconcile.
- Identity matching by work email across tools; unmatched accounts listed in Settings for manual linking.
- Tile status: *Connected · synced N min ago* (`ok`), *Token expires in N days* (`accent`), *Sync failed · <code>* (`danger`), *Not connected* (neutral).
- Data retention: raw events 13 months; aggregated scores kept with the review record.
