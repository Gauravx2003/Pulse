# SprintTaskTable

The active-sprint issue list for one person or team, pulled from Jira, with a per-row freshness signal.

**Consumer provides:** rows of `{key, summary, status, storyPoints, remainingHours, lastActivityAt, dueDate}`; sprint name and dates; last sync time.

- **Last updated** = the latest of: status change, comment, worklog, or field edit **by the assignee** (Jira `updated` alone counts bot and PM edits — don't use it).
- Shown relative in working days ("3 working days ago"); weekends and the person's leave (from HRMS / calendar) don't count.
- Row flag: *Stale* when In Progress / To Do-with-due-date and no assignee activity for more than 2 working days (team-configurable). *Overdue* when `dueDate` < today and not Done. Flagged rows get `accent-soft` ground plus the word in the Signal column.
- Numbers right-aligned in `tabular`; issue key in `brand-ink` and links to Jira.
- Actions: *Nudge assignee* sends a Slack/Teams DM; it never changes the Jira issue.
