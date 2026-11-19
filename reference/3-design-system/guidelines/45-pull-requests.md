# Pull requests and the PR standard

Pull requests come from **Microsoft TFS (Azure DevOps Server)** and GitHub. Every PR is checked against the PR standard, and every review comment against the review comment standard. The **Pull requests** screen shows one person (employees see their own) or the team (managers, Admin). The period control picks a **sprint** (Sprint 31 – 43), a **month** (April – September 2026) or **All**; a **Sprint by sprint / Month by month** table shows PRs, merged, PR standard %, titles OK, median size, reviews, comments to standard and rubber-stamps per period — click a row to open it. With *All* the PR list is grouped by month.

## PR standard (set by Admin in Rules & thresholds)

| # | Check | Default |
|---|---|---|
| 1 | Title matches the pattern `WORKITEM type(scope): summary` — type is feat, fix, refactor, perf, test, docs or chore; summary 10–72 characters, no full stop | on |
| 2 | Linked work item (Jira key or TFS work item) | on |
| 3 | Description template: Summary · How it was tested · Risk / rollback | on |
| 4 | Size ≤ 400 lines changed | 400 |
| 5 | Approvals before merge | 2 |
| 6 | Build / pipeline passed | on |
| 7 | Blocking threads (issue, todo, “(blocking)”) resolved before merge | on |

First review time is shown on each PR against a 24 h target but is not scored. Checks that cannot be judged yet (a PR still in review) show **Pending** and are left out of the score. **PR standard %** = passed checks ÷ judged checks, averaged over the person’s PRs in the month; target 85%.

Good: `PLAT-2231 feat(ingestion): add per-tenant rate limit` · Not to standard: `fixed bug`, `WIP changes`, `PLAT-2231 changes`. Every failed title shows a suggested rewrite.

## Review comment standard (conventional comments)

Every comment starts with a label — `praise`, `suggestion`, `issue`, `question`, `nitpick`, `todo`, `thought` — with optional `(blocking)` / `(non-blocking)`, and gives the reason. Issue, suggestion and todo need at least 20 characters after the label.

| Class | Example | Shown as |
|---|---|---|
| Standard | `issue (blocking): this map is written from two goroutines without a lock — it will race under load.` | ok |
| No label | `the retry loop is duplicated in webhook.go` | accent |
| Needs a reason | `issue: wrong approach` | accent |
| Too short · no label | `LGTM`, `fix this`, `??` | danger |

Approving a PR over 200 lines with no comment is counted as a **rubber-stamp**. Non-standard comments show a suggested rewrite.

## Where it counts

- **Discipline (g · process adherence)** uses the person’s PR standard % in place of a manual code-review figure.
- **Collaboration** signals show the share of review comments that meet the standard.
- **Appraisal** evidence shows PRs, PR standard % and review comment quality for the year.
- Numbers are signals for 1:1s and retros, never automatic ratings.
