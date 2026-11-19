# Spec Delta

## Purpose

Defines the Data imports screen and the column and check definitions of each CSV import type that has no live integration. The shared pipeline is in platform/csv-import-framework.

## ADDED Requirements

### Requirement: Import cards
The system SHALL show one card per import the user may run with what it feeds, required columns, last upload, and Upload CSV, Template and Export current actions.

#### Scenario: Manager's cards
- **WHEN** a Manager opens Data imports
- **THEN** only OKRs, Users (own team) and Customer visits are offered

### Requirement: OKR import
The system SHALL import OKRs with required columns quarter, owner_email, objective, key_result, start, target and optional level, current, unit, value, epic. Same quarter, owner and objective SHALL form one objective. Errors: unknown quarter, owner not visible or editable, target equal to start, non-numeric values, duplicate key result in an objective. An unknown value SHALL be a warning.

#### Scenario: Bulk check-in
- **WHEN** a file with new current values for existing key results is committed in update mode
- **THEN** the key results are updated as check-ins

### Requirement: Attendance import
The system SHALL import attendance with required columns employee_email, date, status and optional leave_type, applied_on, check_in, check_out. status SHALL be one of O, W, D, L, S, H, A or HD; leave_type one of Casual, Sick, Earned, Comp off, Emergency; check_in and check_out HH:MM in the company time zone.

#### Scenario: Unknown status
- **WHEN** a row has status "X"
- **THEN** the row errors and lists the allowed values

### Requirement: Attendance import checks
The attendance check SHALL report errors for unknown user or no profile, future or pre-joining date, month out of range, two rows for one person and day, leave without type, check-out not after check-in, and leave on a visit day; and warnings for weekend work, holiday row, missing days and no check-in.

#### Scenario: Leave without type
- **WHEN** a row has status L and an empty leave_type
- **THEN** the row errors "Leave needs a type"

#### Scenario: Modes
- **WHEN** Replace months is chosen
- **THEN** all days of the listed people in the file's months are replaced, and with Merge days only the listed days change

### Requirement: Attendance upload permission and notifications
Only holders of attendance.upload (HR/Admin) SHALL upload or replace attendance. A commit SHALL notify managers and recalculate capacity, Discipline rule h and the Collaboration presence signal.

#### Scenario: Manager attempts upload
- **WHEN** a Manager without attendance.upload posts an attendance check
- **THEN** the API returns 403

### Requirement: Customer visit import
The system SHALL import planned visits for Managers and Admin with required columns employee_email, customer, city, purpose, from, to, requested_by, owner_email and optional reference. Errors: person not in your team, purpose not in the list, over 14 days, overlap with visits, file rows or leave. A past start SHALL be a warning. Commit SHALL notify the engineer.

#### Scenario: Overlap within file
- **WHEN** two rows for one person overlap
- **THEN** both are errors that name the other line

### Requirement: Holiday calendar import
The system SHALL import holidays with columns date and name for holders of attendance.upload or rules.manage. A duplicate date SHALL be an error, a weekend or past date a warning, and the same name a skipped row.

#### Scenario: Weekend holiday
- **WHEN** a holiday falls on Saturday
- **THEN** a warning is shown and the row is importable

### Requirement: Exports and source map
The system SHALL provide round-trip exports for each import type, an appraisal proposals CSV export for payroll to appraisal roles, a "where each module gets its data" table and an upload history with Undo.

#### Scenario: Export audited
- **WHEN** appraisal proposals are exported
- **THEN** the export is audited with actor, time and row count
