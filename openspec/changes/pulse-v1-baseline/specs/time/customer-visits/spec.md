# Spec Delta

## Purpose

Defines the customer visit lifecycle from planning to verification, its validation, points and the effect on capacity.

## ADDED Requirements

### Requirement: Lifecycle
A visit SHALL move through Planned, Report due, Feedback pending, Awaiting manager, then Verified or Not counted, in that order, with actions limited by role and state.

#### Scenario: Out-of-order action
- **WHEN** someone verifies a visit with no report
- **THEN** the action is refused

### Requirement: Plan or log
The system SHALL let managers plan and engineers log a visit with engineer, purpose, customer, city, from, to (at most 14 days), requested by, account owner and optional reference and notes, and SHALL reject overlap with another visit and with leave.

#### Scenario: Overlap with leave
- **WHEN** a visit overlaps approved leave
- **THEN** the form errors with the overlap

#### Scenario: Too long
- **WHEN** a visit spans 15 days
- **THEN** the form shows "A visit can be at most 14 days."

### Requirement: Capacity effect
Visit days SHALL leave sprint capacity and show as Visit or On duty everywhere, and planned visits SHALL appear as On duty (D) days in attendance.

#### Scenario: Visit in active sprint
- **WHEN** a visit falls in the active sprint
- **THEN** capacity drops by those days and a capacity alert appears on the person view

### Requirement: Report
The engineer SHALL submit what was done, hours on site, travel hours, outcome, follow-ups and customer contact; until then the visit shows Report due and the manager MAY send a reminder.

#### Scenario: Missing fields
- **WHEN** the report is submitted without hours on site
- **THEN** the form shows the required-field error

### Requirement: Customer feedback
The account owner SHALL be notified and record CSAT 1–5, whether the customer would ask again, source, date and quote. Account owners SHALL see and record feedback only for visits they own.

#### Scenario: Not the owner
- **WHEN** an account owner opens feedback for a visit owned by someone else
- **THEN** access is refused

### Requirement: Verification and points
A holder of effort.confirm SHALL verify or reject. Points SHALL be 1.5 per visit, 0.5 per extra day, 1 per weekend or holiday day, 0.5 for escalation and 1 when CSAT is at least 4.5, capped at 8 a month, and values SHALL be credited by purpose with weekend days adding Resilience. A person SHALL NOT verify their own visit.

#### Scenario: Points example
- **WHEN** a 3-day escalation visit includes 1 weekend day and CSAT 4.8
- **THEN** points are 1.5 + 1.0 + 1 + 0.5 + 1 = 5

### Requirement: Views
The system SHALL provide Team, Person and account-owner views, a visit detail modal, Q3 or year-to-date periods and CSV import, comparing people with the team average and excluding new joiners from it.

#### Scenario: Person with no visits
- **WHEN** the team table loads
- **THEN** people with no visits are listed
