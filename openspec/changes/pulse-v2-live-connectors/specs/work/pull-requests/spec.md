# Spec Delta

## Purpose

Phase 2 additions to the pull request views for rows that come from the live GitHub connector.

## ADDED Requirements

### Requirement: PR source badge and deep link
The system SHALL show for each pull request its source (Seed or GitHub) and, for live pull requests, a link that opens it on GitHub in a new tab, on the PR list and PR detail.

#### Scenario: Live PR detail
- **WHEN** a Manager opens a pull request synced from GitHub
- **THEN** the detail shows a GitHub badge and an "Open in GitHub" link and the PR standard checks are computed the same way as for seeded PRs

### Requirement: Unmatched PR authors
The system SHALL keep a live pull request or commit day whose author is not linked to a person out of per-person PR and effort figures, and SHALL count it for the person from the next read once the account is linked.

#### Scenario: Unknown GitHub login
- **WHEN** a synced pull request is authored by a login no person is linked to
- **THEN** it appears in the team PR list without a person and in the unmatched accounts list, and is not counted for anyone
