# work-items Specification

## Purpose

Defines the Overview and Work items views: live and historical Jira work, the stale and not-started logic, the issue drawer and the nudge action.

## Requirements

### Requirement: Overview by period
The system SHALL show for a person and a sprint, month or release the identity header, KPIs, needs-attention issues, utilization by sprint and summary cards. A live sprint SHALL show tasks assigned, stale issues, work assigned versus capacity and utilization with its band; a closed period SHALL show commitment, carry-over, median cycle time, estimate accuracy, hours logged and utilization.

#### Scenario: Overloaded live sprint
- **WHEN** a person has 10.3 days of work against 6 days of capacity
- **THEN** an alert strip states the days over and utilization shows a Critical band

### Requirement: Stale and related flags
The system SHALL flag an issue as stale when In Progress or In Review with more than 2 working days idle, not started when To Do after 40% of the sprint, overdue when past due and not Done, and no estimate when it has neither estimate nor story points. Last activity SHALL be a transition, worklog, comment or linked commit by the assignee, never Jira's updated field alone.

#### Scenario: Idle across a holiday
- **WHEN** an issue is untouched over 2 weekdays that include a holiday
- **THEN** the holiday day does not count and the issue is not stale

### Requirement: Live work list
The system SHALL show a live list with filters (All, Open, Stale / not started, Overdue, In review, Done), search by key or summary and sortable columns, plus a daily load of logged versus planned per working day.

#### Scenario: Filter and search
- **WHEN** a user filters Stale and searches "PLAT-21"
- **THEN** only matching stale issues remain, with counts on the chips

### Requirement: Issue drawer
The system SHALL open a drawer with key, status, type, story points, summary, assignee, due date, signal badges, Original / Logged / Remaining, the date and time checks and an activity timeline in which bot and planning events are greyed and marked "not counted as activity".

#### Scenario: Time checks
- **WHEN** spent ÷ (working hours in progress × 0.75) is below 0.3
- **THEN** the drawer shows "Low logged time" with its explanation

### Requirement: Nudge
The system SHALL let permitted users send a Slack or Teams nudge to the assignee of a stale issue, SHALL rate-limit nudges per issue and sender, and SHALL record each nudge.

#### Scenario: Repeated nudge
- **WHEN** a nudge for the same issue is sent twice within the limit window
- **THEN** the second is refused with a message

### Requirement: History views
The system SHALL show for a closed sprint, month or release a summary, a release card and the issue list with filters (All, Done, Carried over, Bugs, Open now) and a per-issue timeline.

#### Scenario: Release card
- **WHEN** a release period is chosen
- **THEN** the card shows fix version, dates, sprints, person SP done, team SP done and share of the release

### Requirement: New joiners on onboarding
The system SHALL show an Onboarding badge and Training in place of utilization for a new joiner on an active onboarding plan.

#### Scenario: Onboarding joiner
- **WHEN** a joiner with an active onboarding plan opens Overview
- **THEN** utilization is not judged and no overload alert appears

### Requirement: Visibility
Work data SHALL follow platform visibility: own for Employees, reports for Managers, all for all.view.

#### Scenario: Peer's issue
- **WHEN** an Employee opens another Employee's issue drawer by key
- **THEN** access is refused unless it is their own or visible to them
