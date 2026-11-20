# reviews-performance Specification

## Purpose

Defines the performance review view: index breakdown, trend, calibration for managers and the review summary.

## Requirements

### Requirement: Performance index
The index SHALL be (35 Delivery + 15 Quality + 25 Culture + 15 OKR + 10 Growth) ÷ 100 on a 1–5 scale under default weights and SHALL be shown with its pillar breakdown (weight, score, what feeds it) and a 4-quarter trend.

#### Scenario: Breakdown
- **WHEN** a person opens Performance review
- **THEN** each pillar shows weight, score and its inputs

### Requirement: Calibration table
Holders of reviews.calibrate SHALL see a table for their team with each pillar, the index and a proposed rating of Exceeds at 4.2 or more, Meets at 3.5 or more, else Developing. The table SHALL be visible to managers and HR only, and the index SHALL be an input to calibration, not the rating.

#### Scenario: Employee view
- **WHEN** an Employee opens Performance review
- **THEN** no calibration table appears

### Requirement: Submit for calibration
A manager SHALL submit the team's reviews for calibration, SHALL be unable to calibrate themselves, and the submission SHALL be audited.

#### Scenario: Submitted
- **WHEN** a manager submits
- **THEN** the review is marked submitted with time and actor

### Requirement: Review summary
The system SHALL show a summary modal with quarter, title, level, pillar, weight, score and the index, with Copy summary.

#### Scenario: Copy
- **WHEN** a user clicks Copy summary
- **THEN** the text is copied and a toast confirms
