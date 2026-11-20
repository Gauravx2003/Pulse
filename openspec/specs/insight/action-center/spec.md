# action-center Specification

## Purpose

Defines the manager's landing page: one prioritized list of what needs attention, early signals about people, forecast and capacity.

## Requirements

### Requirement: Do today list
The system SHALL list items sorted High, Medium, Low, each with a source badge, person, text and one action opening the right screen, tab and person.

#### Scenario: Empty list
- **WHEN** nothing needs the user
- **THEN** the card reads "Nothing needs you right now."

### Requirement: Work and people triggers
The system SHALL raise: over the overload threshold High; 2 or more stale issues Medium; days with commits but no worklog Low; 1:1 older than 10 working days Medium; weekly pulse with mood 2 or lower or workload 5 High.

#### Scenario: Overload
- **WHEN** a report is over the overload threshold
- **THEN** a High item with a Rebalance action appears

### Requirement: Effort, training, review and quality triggers
The system SHALL raise: extra-mile items awaiting confirmation Medium; visits to verify Medium; overdue visit reports Low; training final review due High; mentor blockers in the last 5 days High; open 360° reviews Medium when 10 or fewer working days remain; high-severity bugs Medium; disputed attribution Medium; rubber-stamp approvals Low.

#### Scenario: Mentor blocker
- **WHEN** a mentor raised a blocker 2 days ago
- **THEN** a High item appears for the manager

### Requirement: Appraisal, attendance and integration triggers
The system SHALL raise: draft appraisal proposals Low for managers; proposals awaiting approval Medium for HR; a past month not uploaded Medium and an upload due near month end Low for HR; a report with an absence Medium, and 2 or more short-notice days, office policy under 70% or late check-ins over allowance Low for managers; a failing integration High and a token near expiry Low for Admins.

#### Scenario: Failing integration
- **WHEN** an integration sync fails
- **THEN** a High item appears for Admins

### Requirement: People signals
The system SHALL show per person a risk of High, Watch, Low or OK with each signal and a suggested next step. Signals SHALL be conversation prompts, never a rating, and new joiners on onboarding SHALL not be flagged for utilization.

#### Scenario: Signal set
- **WHEN** signals are evaluated
- **THEN** they cover sustained overload (over 100% in 2 of the last 4 sprints), under-used (below 60% in 2 of 3), weekend work while overloaded, unplanned work of 30% or more, Discipline below 3, 1:1 overdue, low or falling pulse, falling index, no recognition this quarter and training behind

#### Scenario: Click through
- **WHEN** a manager clicks a person row
- **THEN** Progress for that person opens

### Requirement: Sprint forecast
The system SHALL fill open issues in due-date order against remaining focus hours per person and list what does not fit as likely to carry over.

#### Scenario: Does not fit
- **WHEN** remaining work exceeds capacity left
- **THEN** the overflowing issues are listed as likely to carry over

### Requirement: Next-sprint capacity
The system SHALL compute focus days as working days minus leave, planned visits, training and mentoring hours, times velocity (done story points per day over the last 3 sprints), to suggest story points per person and for the team.

#### Scenario: Team total
- **WHEN** the table loads
- **THEN** a team total row sums the person suggestions

### Requirement: Skills coverage
The system SHALL compute areas by people from PRs and Jira components over 6 months and mark each area Single point of failure, Thin or Covered with a pairing suggestion.

#### Scenario: Single owner
- **WHEN** only one person touched an area
- **THEN** it is marked Single point of failure with a pairing suggestion

### Requirement: Team pulse, wins and digest
The page SHALL show a 6-week mood and workload trend, recent team wins (kudos, 5-star visits, completed training, proactive fixes) and a preview of the weekly digest.

#### Scenario: Digest preview
- **WHEN** the manager opens Weekly digest
- **THEN** the Monday summary preview shows with a Close button

### Requirement: Visibility
The Action center SHALL show only reports for Managers and everyone for all.view, and SHALL be the landing page for both.

#### Scenario: Employee access
- **WHEN** an Employee opens it
- **THEN** the "You don't have access" card appears
