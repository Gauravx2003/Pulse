# Spec Delta

## Purpose

Defines the manager's team workload view: utilization bands, heatmap, people table and rebalance suggestions.

## ADDED Requirements

### Requirement: Utilization and bands
The system SHALL compute utilization as the sum of remaining hours divided by focus hours a day, over working days left less leave, holidays and visit days, and SHALL band it: under below 60, healthy 60–85, at capacity 85–100, over 100–120 and critical above 120. Closed periods SHALL use hours logged divided by working days times 8 hours.

#### Scenario: Band edges
- **WHEN** utilization is exactly 100%
- **THEN** it is At capacity, and 100.1% is Over

### Requirement: Team KPIs and period views
The system SHALL show for a live sprint team utilization, overloaded and under-utilized people, and stale share, and for a closed period team utilization, story points done, carried over, over 100% and under 60%, with a utilization-by-sprint chart where clicking a sprint opens it.

#### Scenario: Open a sprint
- **WHEN** a manager clicks a sprint on the chart
- **THEN** the page switches to that sprint

### Requirement: Daily heatmap
The system SHALL show person by working day with band colors, using logged over focus hours before today and remaining over capacity from today, with distinct cells for Leave, Visit, Training and pre-joining, and row click opening the person.

#### Scenario: Cell labels
- **WHEN** a person is on leave on a day
- **THEN** that cell reads Leave and not a utilization value

### Requirement: People table
The system SHALL list people sorted by utilization with open and stale issues, work, capacity, utilization meter, daily updates, Discipline and index, for the manager's reports only (all for all.view).

#### Scenario: Report-only
- **WHEN** a Manager opens Team utilization
- **THEN** only their reports appear

### Requirement: Rebalance suggestion
The system SHALL suggest moving a named issue and its story points from an over-capacity person to an under-used person with matching skills, SHALL show the resulting levels, SHALL never suggest trainees on onboarding, and SHALL make no change in Jira.

#### Scenario: Suggestion text
- **WHEN** Priya is at 171% and Dev at 52%
- **THEN** the strip reads "Move PLAT-2198 (24 h) from Priya Shah (171%) to Dev Patel (52%). New levels ≈ 104% and 96%." with Reassign in Jira

### Requirement: Fairness
The system SHALL exclude leave, holidays, visit days and pre-joining days from denominators and SHALL not flag onboarding joiners for utilization.

#### Scenario: Joiner excluded
- **WHEN** a joiner is on onboarding
- **THEN** they are not counted as under-used
