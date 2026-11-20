# rules-thresholds Specification

## Purpose

Defines the Admin-editable thresholds, weights and point values behind every score, with validation, defaults and reset.

## Requirements

### Requirement: Editable rule groups
The system SHALL let holders of rules.manage edit these groups: staleness and capacity; utilization bands; performance index weights; culture value weights and data share; Discipline bands; effort and extra-mile points; customer visits; pull request standard; training rating and sign-off; attendance and leave; and SHALL expose appraisal standards to holders of appraisal.approve.

#### Scenario: Admin edits a threshold
- **WHEN** an Admin sets "stale after" to 3 working days
- **THEN** live stale counts use 3 on every screen from the next request

### Requirement: Weights must total 100
The system SHALL show a "Total 100%" badge for index weights, culture value weights and appraisal score weights, and SHALL refuse to save a set that does not total 100%.

#### Scenario: Invalid total
- **WHEN** index weights are set to 35, 15, 25, 15, 20
- **THEN** the save is rejected and the badge shows the total in danger state

### Requirement: Value validation
The system SHALL validate each rule against its range and type, SHALL validate the PR title pattern as a safe regular expression that is length-limited and checked for catastrophic backtracking, and SHALL reject a late-after time that is not a valid HH:MM.

#### Scenario: Unsafe pattern
- **WHEN** an Admin saves a title pattern that causes catastrophic backtracking
- **THEN** it is rejected with an explanation

### Requirement: Global defaults
The system SHALL ship the defaults of reference section 12, including a global office policy of 3 days a week, notice of 2 days for casual and 7 for earned leave, late after 10:30 with 3 late days a month, appraisal weights 50/20/20/10, cap 20%, budget 12% of payroll and eligibility 6 months.

#### Scenario: Fresh install
- **WHEN** the system starts with no changes
- **THEN** every rule equals its documented default

### Requirement: Reset to defaults
The system SHALL restore all rules to defaults on Reset after a confirmation that names the number of values that will change, and SHALL record before and after in the audit log.

#### Scenario: Reset
- **WHEN** an Admin confirms Reset defaults
- **THEN** all rules return to defaults, a new rule version is created and the audit log holds the old values

### Requirement: Versioned and audited changes
The system SHALL create a new rule version on every save, SHALL record actor, time and diff in the audit log, and SHALL show who last changed each card.

#### Scenario: Compare versions
- **WHEN** an Admin opens history for a card
- **THEN** each version shows actor, time and changed values
