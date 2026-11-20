# effort-extra-mile Specification

## Purpose

Defines hours and days worked, code-only day detection, automatic detection of unplanned and extra work, manager confirmation and the points and caps that credit culture values.

## Requirements

### Requirement: Hours and days
The system SHALL compute hours logged from Jira worklogs, days worked as hours divided by 8, active days from a worklog or Git activity, code-only days (Git activity without a worklog), long days and weekend work, over the working-day calendar.

#### Scenario: Code-only day
- **WHEN** a person commits on a working day with no worklog
- **THEN** the day is marked code-only and a "Log time" prompt is shown

### Requirement: Extra-work detection
The system SHALL detect extra work automatically and show it as Awaiting manager with points: added mid-sprint 1, incident or hotfix 2, took over 1, helped on another's issue 1, untracked useful work 1, reviews for others 0.5 per 5, extra support ticket 1, proactive fix 1.5, each with its credited values.

#### Scenario: Mid-sprint addition
- **WHEN** an issue's Sprint field is set after the sprint started
- **THEN** an item appears with 1 point crediting Adaptability and Agility in Execution, with the changelog evidence

### Requirement: Confirmation
Only holders of effort.confirm SHALL confirm or reject items, only confirmed items SHALL score, a rejection SHALL need no score effect, and a person SHALL NOT confirm their own items.

#### Scenario: Confirm changes bonus at once
- **WHEN** a Manager confirms a 1-point item crediting 2 values
- **THEN** each value's bonus rises by 0.025 immediately

### Requirement: Caps
The system SHALL add 0.05 per confirmed point split across credited values, cap the bonus at 0.3 per value per quarter, cap points at 6 per person per month and visit points at 8 per month separately.

#### Scenario: Monthly cap
- **WHEN** confirmed points would exceed 6 in a month
- **THEN** the excess does not score and the cap is shown

### Requirement: Who gave it
The system SHALL show for each item who requested it (changelog author, escalation or Self-initiated) and rank requesters by unplanned hours on the team view.

#### Scenario: Top requester
- **WHEN** the team view loads
- **THEN** requesters appear with hours and item counts as bars

### Requirement: Planning guardrail
An unplanned share above 30% SHALL be shown as a planning signal for the manager, not a performance issue for the employee. Copy SHALL state this.

#### Scenario: High unplanned share
- **WHEN** 35% of logged time is unplanned
- **THEN** the alert reads "This is a planning signal for the manager, not a performance problem"

### Requirement: Views
The system SHALL provide a Team tab (KPIs, people table, period totals, requesters, review of points with Confirm all) and a Person tab (time breakdown, daily log calendar with markers, unplanned and extra work, culture updates), plus an evidence modal showing how each item was detected.

#### Scenario: Evidence modal
- **WHEN** a user opens an item
- **THEN** hours, points, status, requester, detection note and credited values are shown
