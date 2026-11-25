# Spec Delta

## Purpose

Phase 2 additions to the work item views for rows that come from the live Jira connector.

## ADDED Requirements

### Requirement: Source badge and deep link
The system SHALL show for each work item its source (Seed or Jira) and, for live items, a link that opens the issue in Jira in a new tab, and SHALL offer a source filter on the work item lists.

#### Scenario: Live Jira issue
- **WHEN** a Manager opens the live work list after a Jira sync
- **THEN** synced issues show a Jira badge and an "Open in Jira" link, beside the seeded issues

#### Scenario: Filter by source
- **WHEN** the Manager filters by source Jira
- **THEN** only issues with `source = 'jira'` are listed

### Requirement: Unmatched assignees
The system SHALL keep a live issue whose assignee is not linked to a person out of per-person figures and person views, SHALL include it in team-level counts, and SHALL count it for the person from the next read once the account is linked.

#### Scenario: Assignee linked later
- **WHEN** an Admin links the Jira account of an unmatched assignee
- **THEN** that person's workload and work list include the issue on the next load, with no new sync
