# Bugs & support

The **Bugs & support** screen shows, for every person: open bugs traced to their own code, bugs they fixed for others (and which of those were proactive), and extra support tickets they took. The manager's *Team* tab shows the same numbers for everyone who reports to them.

## Bugs traced to a person's code

| Step | Source |
|---|---|
| A bug is linked to the change that introduced it | Jira "caused by" / "is caused by" link to the original story, **or** the PR that fixes the bug changes lines last written by an earlier PR (`git blame` on the fix diff) |
| The original PR's author is the **code owner** of the bug | GitHub PR author |
| Environment | Jira `environment` / label: *Staging* (caught before release) or *Production* (escaped) |

Shown per person: **open bugs in own code** (with High count), **bugs from code this quarter**, **reached production**, and each bug's age in working days and who is fixing it.

- Attribution is a learning signal for 1:1s, never blame, and never an automatic score.
- The author can **Dispute** an attribution with a reason; the manager **keeps** it or **removes it from their count**.
- The author can **Take it** when someone else is fixing a bug in their code.

## Resolving other people's bugs proactively

- **Fixed for others** = bugs resolved by the person where the code owner is someone else.
- **Proactive** = the person **self-assigned** the bug from the backlog (Jira changelog: assignee set by the same user), not assigned by a lead.
- *Open bugs you could pick up* lists team bugs in others' code; **Pick up** self-assigns it, **Mark resolved** closes it.
- Each proactive fix earns **+1.5** extra-mile points (editable) after manager confirmation, credited to **Problem Solving** and **Collaboration**.

## Extra support tickets

| Field | Source |
|---|---|
| Ticket, customer, priority | Support desk (Zendesk, Freshdesk or Jira Service Management) |
| How it was taken | *Self-picked from queue* or *Assigned by support lead* (ticket history) |
| Hours | Time logged on the ticket |
| CSAT | Customer satisfaction rating on the resolved ticket |

- Support work counts as **unplanned time** in Effort.
- Each resolved extra ticket earns **+1** point after manager confirmation: self-picked credits **Outcome Ownership** and **Collaboration**; assigned credits **Collaboration**.
- The **Support queue** card lets anyone take an unassigned ticket and resolve it with the hours spent.


## Sprint or month

Support tickets and hours follow the selected sprint or month. A **Sprint by sprint / Month by month** table shows, per period: bugs created in the person's code, high severity, reached production, bugs fixed for others (proactive), support tickets and hours — for one person or the team.
