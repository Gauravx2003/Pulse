# Spec Delta

## Purpose

Defines objectives and key results, their progress and pillar scoring, check-ins and CSV import and export.

## ADDED Requirements

### Requirement: Objective and key results
An objective SHALL have text, level (Individual or Team), a required quarter, an optional Jira epic and optional value, and 1 to 5 key results each with description, start, target, current and unit. Targets MAY be lower than start. Validation SHALL require objective text, a quarter, every key result described and target different from start.

#### Scenario: Missing quarter
- **WHEN** an objective is saved without a quarter
- **THEN** the form shows "Select a quarter."

### Requirement: Progress
Key result progress SHALL be (current − start) ÷ (target − start) clamped to 0–1, objective progress SHALL be the average of its key results, and status SHALL be On track at 70% or more, At risk at 40–69% and Off track below 40%.

#### Scenario: Falling target
- **WHEN** latency start 900, target 400, current 650
- **THEN** progress is 50%

### Requirement: OKR pillar
The OKR pillar SHALL be 1 + progress ÷ 0.7 × 3 capped at 5, using the current quarter's individual OKRs and falling back to the manager's rating when none exist.

#### Scenario: On-track objective
- **WHEN** average progress is 0.7
- **THEN** the pillar is 4.0

### Requirement: Permissions
okr.own SHALL allow creating, editing, checking in and deleting own OKRs; okr.team SHALL allow the same for visible people and Team OKRs. Deleting SHALL require confirmation stating the number of key results and that it cannot be undone.

#### Scenario: Edit others without permission
- **WHEN** an Employee edits a peer's objective
- **THEN** the call returns 403

### Requirement: Check-in
The system SHALL let a user record a new current value with an optional note for a key result and show the new progress.

#### Scenario: Check-in toast
- **WHEN** a check-in is saved
- **THEN** a toast shows the new progress

### Requirement: Import and export
The system SHALL import and export one row per key result, update matching objectives (same quarter, owner and objective) so a new current value acts as a bulk check-in, and allow okr.own users to import only their own rows.

#### Scenario: Import own rows only
- **WHEN** an Employee's file contains another owner's rows
- **THEN** those rows error as not editable

### Requirement: Signals
An OKR under 40% SHALL appear as a next step in Progress and an achieved key result SHALL appear as an achievement.

#### Scenario: Off track
- **WHEN** an objective is at 30%
- **THEN** a next step "OKR off track" with an Open button appears
