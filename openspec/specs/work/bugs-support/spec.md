# bugs-support Specification

## Purpose

Defines bug attribution to code owners, the dispute and take-over flows, proactive fixes and extra support tickets, as learning signals rather than blame.

## Requirements

### Requirement: Attribution
The system SHALL attribute a bug to a code owner through a Jira "caused by" link or through blame on the fixing PR's diff, SHALL record Staging or Production (in v1 the link and blame results come from seeded data), and SHALL present attribution as a learning signal, never as an automatic score or blame.

#### Scenario: Caused-by link wins
- **WHEN** a bug has a caused-by link and a blame result differs
- **THEN** the link is used

### Requirement: Dispute
The system SHALL let a person dispute an attribution with a required reason, SHALL let their manager keep it or remove it from the count, and SHALL audit the decision.

#### Scenario: Dispute removed
- **WHEN** a manager chooses "Remove from their count"
- **THEN** the bug no longer counts for that person and the decision is logged

### Requirement: Take it and pick up
The system SHALL let a person take the fix of a bug in their own code that someone else is fixing, and pick up open bugs in others' code, setting proactive when self-assigned.

#### Scenario: Pick up
- **WHEN** a person picks up a bug in another's code and resolves it
- **THEN** a proactive-fix item of 1.5 points is created awaiting confirmation

### Requirement: Support tickets
The system SHALL list support tickets taken beyond sprint work (self-picked or assigned) with hours and CSAT, award 1 point after confirmation, let anyone take an unassigned queue ticket, and require hours and a resolution note to resolve.

#### Scenario: Resolve ticket
- **WHEN** a person resolves a ticket with hours 2 and a note
- **THEN** the ticket is closed and a 1-point item awaits confirmation

### Requirement: Views
The system SHALL provide a Team tab (KPIs, people table, disputes to decide) for managers and a Person tab (open bugs in own code with Mark resolved, Dispute, Take it; fixed for others; bugs to pick up; period table; tickets; queue), employees seeing the Person tab only.

#### Scenario: Employee view
- **WHEN** an Employee opens Bugs & support
- **THEN** no Team tab is offered

### Requirement: Visibility and fairness
Bug data SHALL follow platform visibility, and copy SHALL not use blame wording.

#### Scenario: Copy check
- **WHEN** open bugs are listed
- **THEN** the subtitle reads "a learning signal, not blame"
