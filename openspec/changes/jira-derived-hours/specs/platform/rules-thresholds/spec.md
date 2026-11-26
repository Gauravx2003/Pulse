# Spec Delta

## ADDED Requirements

### Requirement: Working-day window
The system SHALL keep a working-day window rule, a start and an end time of day, in the staleness and capacity group, with the default 10:00–18:00. The window SHALL be read in the company time zone (Asia/Kolkata) and used to split work intervals into hours. The system SHALL reject a start or end that is not a valid HH:MM, and an end that is not after the start.

#### Scenario: Default window
- **WHEN** rules have never been changed
- **THEN** the working-day window is 10:00–18:00

#### Scenario: Invalid window
- **WHEN** a rules.manage holder saves the window 18:00–10:00
- **THEN** the save is rejected with a message on the window, and the previous rule version stays in force

#### Scenario: Window changed
- **WHEN** the window is changed to 09:30–18:30 and saved
- **THEN** a new rule version is recorded and audited, and hours from work intervals use the new window from the next read
