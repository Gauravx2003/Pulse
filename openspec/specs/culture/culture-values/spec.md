# culture-values Specification

## Purpose

Defines the 11 culture values, how each score is calculated from 360° ratings, data signals and extra-mile bonus, and the Discipline rules and override.

## Requirements

### Requirement: Eleven values and weights
The system SHALL score Outcome Ownership 15%, Moral & Ethics 10%, Decision Making 10%, Problem Solving 10%, Risk Appetite 5%, Adaptability 5%, Agility in Execution 10%, Collaboration 10%, Resilience 5%, Resourcefulness 10% and Discipline 10%; the culture pillar SHALL be their weighted average and weights SHALL total 100%.

#### Scenario: Pillar
- **WHEN** all 11 values are scored
- **THEN** the pillar is the weighted average of the 11 scores

### Requirement: Value score
A value score SHALL be min(5, 360° rating × (1 − s) + data signals × s + extra-mile bonus), with data share s defaulting to 40%. The 360° rating SHALL use the last published cycle weighted manager 40%, internal 25%, cross-department 15%, self 20%, re-normalized and ignoring Not observed. Moral & Ethics SHALL use s = 0 and never tool data.

#### Scenario: Moral & Ethics
- **WHEN** Moral & Ethics is calculated
- **THEN** only the 360° rating and bonus are used and a "360° only" note is shown

### Requirement: Data parameters
Each data parameter SHALL be scored 1–5 by its bands and averaged by weight, with parameters, sources, weights and bands as in reference section 12. A parameter with no data or no opportunity SHALL be left out and remaining weights re-normalized.

#### Scenario: No opportunity
- **WHEN** a person has no incidents
- **THEN** the incidents parameter is marked not applicable and excluded

### Requirement: Reported inputs without a live source
In v1 data parameters with no live source SHALL be read from stored per-person, per-quarter reported inputs with a raw value, display value, source label and simulated flag, shown in the detail view with that label and a "reported input" marker, and replaceable by a live source without changing the formula.

#### Scenario: Parameters covered in v1
- **WHEN** the reported inputs are listed
- **THEN** they are manager follow-ups, decisions documented, median time to decide, decisions reversed, root-cause analyses, spikes and experiments, ideas piloted, risks raised, time to productivity, cross-team issues, docs and templates shared, and hours saved

#### Scenario: Source label shown
- **WHEN** a user opens Decision Making for a seeded person
- **THEN** "Decisions documented" shows its value with the source label "Confluence · Q3" and a reported-input marker

#### Scenario: Missing reported input
- **WHEN** a person has no stored value for one of these parameters
- **THEN** the parameter is marked not applicable and left out, and the other weights are re-normalized

### Requirement: Traceable detail view
For each value the system SHALL show the formula with real numbers, the rater table, the parameter table (source, how measured, value, bands, score, weight), extra-mile items with bonus, behaviour anchors 1–5 with the current band highlighted and the evidence log with Give kudos.

#### Scenario: Reproduce
- **WHEN** a user applies the shown formula to the shown inputs
- **THEN** the result equals the shown score

### Requirement: Period selector
The system SHALL show a Period dropdown (for example Q3 2026, Q2 2026), a radar against the team average and a table of calculation rules for all values where a click opens the value.

#### Scenario: Compare periods
- **WHEN** Q2 is selected
- **THEN** the page shows Q2 scores from the Q2 snapshot

### Requirement: Discipline rules
Discipline SHALL be data only: base band from rule a (compliance 95/85/70/50 → 5/4/3/2, else 1), adjustments from rules b–h (stale, re-planning, status accuracy, time logging, early blockers, process adherence, attendance), never more than 1 below the base, and 3.0 until 4 weeks of data exist.

#### Scenario: Under four weeks
- **WHEN** a person has 2 weeks of data
- **THEN** Discipline shows 3.0

### Requirement: Discipline override
A holder of reviews.calibrate SHALL override a Discipline score with a score 1–5 and a written reason visible to the employee, MAY return to the auto score, and SHALL NOT override their own. Every override and revert SHALL be audited.

#### Scenario: Own score
- **WHEN** a Manager tries to override their own Discipline
- **THEN** the control is absent and the API returns 422

### Requirement: Kudos as evidence
Kudos SHALL appear in the evidence log and count as evidence for Collaboration signals, never as a direct score.

#### Scenario: Give kudos
- **WHEN** a user gives kudos with a value and text
- **THEN** it appears in the recipient's evidence log and is sent to Slack if connected
