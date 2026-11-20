# pull-requests Specification

## Purpose

Defines how pull requests from TFS and GitHub are checked against the PR standard and the review-comment standard, and how results are shown.

## Requirements

### Requirement: PR standard checks
The system SHALL judge each pull request against seven checks: title pattern `WORKITEM type(scope): summary` (feat, fix, refactor, perf, test, docs, chore; 10–72 characters, no full stop), a linked work item, the description template (Summary, How it was tested, Risk / rollback), size of 400 changed lines or fewer, 2 approvals, build passed, and blocking threads resolved before merge. Each check SHALL be switchable in Rules.

#### Scenario: Title fails
- **WHEN** a title is "fixed stuff."
- **THEN** the title check fails and a suggested rewrite is shown

### Requirement: Pending checks
A check that cannot be judged yet SHALL show Pending and be left out of the score. PR standard % SHALL be passed over judged checks, averaged across PRs, with a target of 85%.

#### Scenario: Open PR
- **WHEN** a PR is open with no build result
- **THEN** the build check is Pending and not counted

### Requirement: Review comment standard
The system SHALL classify comments as Standard, No label, Needs a reason or Too short using conventional-comment labels and a reason, requiring 20 or more characters for issue, suggestion and todo, and SHALL show a suggested rewrite for non-standard comments.

#### Scenario: Short issue comment
- **WHEN** an "issue:" comment has 8 characters
- **THEN** it is classed Too short

### Requirement: Rubber-stamp
The system SHALL flag an approval of a PR over 200 lines with no comment as a rubber-stamp.

#### Scenario: Large PR approved silently
- **WHEN** a reviewer approves a 350-line PR without commenting
- **THEN** it counts as a rubber-stamp for that reviewer

### Requirement: Team and person views
The system SHALL provide a Team tab (KPIs, people table, period table, checks across team, comment classes, merged-without-standard list) and a Person tab (KPIs, per-check pass share, own comment mix, PR list with a dot per check, reviews given), for sprint, month or All.

#### Scenario: All period
- **WHEN** All is chosen
- **THEN** the PR list is grouped by month and the period dropdown is hidden

### Requirement: PR detail
The system SHALL show a PR's source, repo, status, title, author, dates, size, first review time, work item, each check with its reason, reviewers with votes and each comment with its class, plus a link to the source.

#### Scenario: Link out
- **WHEN** a user clicks the source link
- **THEN** it opens the PR in TFS or GitHub

### Requirement: Admin-configurable standard
Holders of rules.manage SHALL configure the checks, title pattern, size limit and approvals, and every user SHALL be able to view the standard.

#### Scenario: Preview standard
- **WHEN** an Admin changes the size limit to 300 and clicks Preview standard
- **THEN** the preview shows the new limit before saving

### Requirement: Feeds
PR standard results SHALL feed Discipline rule g, Collaboration, appraisal evidence and achievements, and SHALL be presented as signals for 1:1s, never a ranking.

#### Scenario: Discipline g
- **WHEN** a person's PR standard is below 85%
- **THEN** Discipline rule g shows Improve
