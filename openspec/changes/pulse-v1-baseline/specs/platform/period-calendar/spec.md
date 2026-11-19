# Spec Delta

## Purpose

Defines the shared period model (sprint, month, release, quarter, all), the working-day calendar and the clock, so every screen computes the same dates and denominators.

## ADDED Requirements

### Requirement: Period keys
The system SHALL identify periods by key: sprint `s:S43`, month `m:2026-09`, release `r:8.2`, quarter `q:Q3` and `all`. A sprint SHALL run from the Monday of its first week to the Sunday before the next sprint and MAY be longer than two weeks. A month SHALL be a calendar month and the current month SHALL be labelled "(to date)".

#### Scenario: Three-week sprint
- **WHEN** sprint S43 runs 21 Sep to 9 Oct
- **THEN** its range is 21 days and it is the active period until 9 Oct

#### Scenario: Same dates, same numbers
- **WHEN** a sprint and a month cover the same working days for a person
- **THEN** shared metrics for both periods are identical

### Requirement: Shared period choice
The system SHALL carry the chosen sprint, month or release across the screens that support it, except Attendance which keeps its own choice and defaults to the last uploaded month.

#### Scenario: Choice carries over
- **WHEN** a user selects Sprint 42 on Effort and opens Bugs & support
- **THEN** Bugs & support opens on Sprint 42

### Requirement: Working-day definition
A working day SHALL be a weekday that is not a company holiday, not a leave day, not a customer-visit day for that person and not before that person's joining date. Every denominator (capacity, daily-update compliance, office policy, worklog checks) SHALL use this definition.

#### Scenario: Leave and visit excluded
- **WHEN** a person has 1 leave day and 2 visit days in a 10-weekday sprint
- **THEN** their working days for that sprint are 7

#### Scenario: Before joining
- **WHEN** a person joins on the 8th of a month
- **THEN** days before the 8th are shown as "—" and excluded everywhere

### Requirement: Holiday calendar
The system SHALL keep a holiday calendar managed by holders of attendance.upload or rules.manage through CSV import or manual entry, with date and name per holiday, and SHALL support an optional location so holidays can differ by site.

#### Scenario: Holiday import
- **WHEN** a holiday file is committed
- **THEN** those dates stop counting as working days and dependent numbers are recalculated

### Requirement: Live versus closed periods
The system SHALL use live data for an active period and frozen snapshots for closed periods, and SHALL label each view accordingly.

#### Scenario: Closed sprint
- **WHEN** a user opens a finished sprint
- **THEN** the numbers come from the snapshot taken at close and do not change when rules are later edited

### Requirement: Clock and time zone
The system SHALL take "today" from the server clock in the company time zone Asia/Kolkata, SHALL store instants in UTC, and SHALL display dates as "28 Sep" in en-GB with 24-hour times in the company time zone. A `PULSE_TODAY` setting MAY pin "today" for demos and tests and SHALL be ignored under the production profile.

#### Scenario: Pinned today
- **WHEN** `PULSE_TODAY` is 2026-09-28 in a non-production profile
- **THEN** every screen treats 28 Sep 2026 as today

#### Scenario: Pin ignored in production
- **WHEN** `PULSE_TODAY` is set under the production profile
- **THEN** it is ignored and the server clock is used

#### Scenario: Late check-in boundary
- **WHEN** a check-in is at 10:31 company time
- **THEN** it is late regardless of the viewer's browser time zone
