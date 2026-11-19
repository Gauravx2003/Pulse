# Spec Delta

## Purpose

Defines monthly attendance and leave records, their rules and score, and their effect on capacity, Discipline rule h and Collaboration. Attendance ships in the foundation phase because the working-day calendar depends on it.

## ADDED Requirements

### Requirement: Record and statuses
The system SHALL hold one record per person per working day with status Office (O), WFH (W), On duty (D), Leave (L), Sick or Emergency leave (S), Half day (HD, 0.5 leave day), Holiday (H) or Absent (A), plus leave type, applied-on date, check-in and check-out. On duty SHALL count as an office day, and planned customer visits SHALL show as D automatically.

#### Scenario: Sick leave is excused
- **WHEN** a day is Sick or Emergency leave
- **THEN** it is shown as S, excused and never penalizes any score

### Requirement: Upload by HR only
Only holders of attendance.upload SHALL upload or replace a monthly attendance file using the canonical columns employee_email, date, status, leave_type, applied_on, check_in, check_out. Managers SHALL NOT upload.

#### Scenario: Replace a month
- **WHEN** HR commits a file in Replace mode for September
- **THEN** September rows for the people in the file are replaced and the upload is recorded with month, uploader, time, file and rows

### Requirement: Notice classification
Leave applied at least 2 days ahead (7 for earned) SHALL be planned and later leave SHALL be short notice; Sick and Emergency SHALL be Excused; a day with no leave applied and no attendance SHALL be Absent.

#### Scenario: Short notice
- **WHEN** casual leave is applied 1 day ahead
- **THEN** it is classed Short notice

### Requirement: Office policy
The system SHALL apply a global policy of 3 office days a week (configurable in Rules), reduced to the available days in weeks with leave or holidays, counting On duty days as office days. Office policy % SHALL be the sum of min(office + duty days, required) over the sum of required, required being min(3, available days) per week.

#### Scenario: Leave week
- **WHEN** a person has 2 available days in a week and attends 2 office days
- **THEN** that week scores 100%

### Requirement: Punctuality
Late SHALL mean check-in after the late-after time (default 10:30) in the company time zone, with 3 late days a month allowed, pro-rated as 3 × working days ÷ 21 for other periods. Punctuality % SHALL be (check-ins − max(0, late − allowance)) ÷ check-ins.

#### Scenario: Within allowance
- **WHEN** a person is late 3 times in a month
- **THEN** punctuality is 100%

### Requirement: Attendance score
The score SHALL be the average of the parts that have data (leave with notice %, office policy %, punctuality %), with Good at 85% or more, Watch at 70–84% and Talk below 70%.

#### Scenario: Missing part
- **WHEN** a person has no check-in data
- **THEN** the score averages the two parts with data

### Requirement: Effects on other numbers
Leave and absent days SHALL leave capacity and every working-day denominator. Discipline rule h SHALL use the Q3 score: below 85% subtracts 0.25, below 70% subtracts 0.5 and each absence subtracts 0.25 more, within the Discipline floor. Collaboration SHALL get an "Office and on-site presence" signal at 15% weight with bands 95/85/70/55%. An Admin switch SHALL turn attendance off for culture values, and Moral & Ethics SHALL never use attendance.

#### Scenario: Upload updates rule h
- **WHEN** an attendance month is committed
- **THEN** rule h and the Collaboration signal update, and they revert when the import is undone

### Requirement: Views and privacy
The system SHALL provide a Team tab (KPIs, people table, day-by-day grid, monthly uploads) and a Person tab (KPIs, calendar, score build-up, leave list, late check-ins, month-by-month, culture impact), a period dropdown of months (flagged "not uploaded"), sprints and quarters defaulting to the last uploaded month. Employees SHALL see only their own record and check-in times SHALL be visible to the person and their direct manager only.

#### Scenario: Not-uploaded banner
- **WHEN** a month in the period has no upload
- **THEN** an accent banner shows with Upload CSV for uploaders only

### Requirement: Reminders
The system SHALL raise an HR item when a past month is not uploaded and a lower-priority item as month end nears, and SHALL list people with an absence, 2 or more short-notice days, office policy under 70% or late check-ins over allowance for their manager.

#### Scenario: Past month missing
- **WHEN** a past month has no upload
- **THEN** HR sees a Medium Do today item
