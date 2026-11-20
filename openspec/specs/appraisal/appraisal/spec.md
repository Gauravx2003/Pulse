# appraisal Specification

## Purpose

Defines the appraisal score, bands, increment recommendations, budget and approval workflow, with salary data confidential to appraisal roles.

## Requirements

### Requirement: Access and confidentiality
Appraisal and salary data SHALL be available only to holders of appraisal.manage (visible people) and appraisal.approve; Employees SHALL never see the Appraisal screen or endpoints; every read SHALL be audited.

#### Scenario: Employee request
- **WHEN** an Employee calls an appraisal endpoint
- **THEN** it returns 403 and the attempt is audited

### Requirement: Appraisal score
The score SHALL be 0.5 performance index (last 4 quarters) + 0.2 360° (internal and cross-department, latest cycle with at least 2 responses) + 0.2 manager review + 0.1 Discipline, re-weighting missing parts; weights SHALL be editable in Appraisal Standards and total 100%.

#### Scenario: Missing 360°
- **WHEN** no 360° with at least 2 responses exists
- **THEN** the other weights are re-normalized

### Requirement: Bands and recommendation
Bands SHALL be Outstanding 4.5–5.0 (15–20%), Exceeds 4.0–4.49 (10–15%), Strong 3.5–3.99 (7–10%), Meets 3.0–3.49 (4–7%), Developing 2.5–2.99 (0–3%), Below expectations under 2.5 (0% + improvement plan). The recommended increment SHALL be band low + (high − low) × position in band, rounded to 0.5%, capped at 20% and pro-rated for 6–11 months of service.

#### Scenario: Eligibility
- **WHEN** a person has 4 months of service
- **THEN** they are not eligible and the recommendation is 0

### Requirement: Cap and budget
The hard cap SHALL be 20% per person, enforced server-side. The budget SHALL default to 12% of team payroll, be editable in Appraisal Standards, and block submission when proposed spend exceeds it. The effective date SHALL be set per appraisal cycle, defaulting to 1 October of the cycle year.

#### Scenario: Over budget
- **WHEN** proposals total more than the budget
- **THEN** Submit to HR is blocked with the amounts shown

#### Scenario: Cap
- **WHEN** a proposed value of 22% is entered
- **THEN** it is rejected

### Requirement: Draft and exceptions
A manager SHALL apply recommendations and adjust proposed percentages while Draft; an exception outside the band SHALL need a written reason, and a promotion MAY be flagged. Submission SHALL be blocked while any exception has no reason.

#### Scenario: Missing reason
- **WHEN** a manager submits with an unexplained exception
- **THEN** the toast names the people to fix

### Requirement: Approval workflow
A holder of appraisal.approve SHALL approve or send back each submitted proposal or approve all submitted, then release letters effective on the cycle date. A person SHALL NOT approve or propose their own increment, and each transition SHALL be audited with actor and time.

#### Scenario: Send back
- **WHEN** HR sends a proposal back
- **THEN** it returns to Draft for that manager with a reason

### Requirement: Frozen at submit
A submitted proposal SHALL keep the score, evidence references and rule version computed at submission; later rule or data changes SHALL NOT alter it, and a send-back SHALL recompute.

#### Scenario: Later rule edit
- **WHEN** weights change after submission
- **THEN** submitted proposals keep their scores

### Requirement: Evidence panel
The system SHALL show per person the score breakdown, quarterly index, OKR pillar, culture values, extra-mile points, bugs and proactive fixes, support, visits, PRs and PR standard, training rating and the year's 360° comments (without reviewer identity).

#### Scenario: 360 comments
- **WHEN** the evidence panel opens
- **THEN** comments appear grouped and unattributed

### Requirement: Salary source
Current CTC SHALL come from the people seed table and from the optional `ctc` column of the Users CSV import (accepted only from users.all holders), and SHALL be excluded from logs and from every export other than the appraisal export.

#### Scenario: Missing CTC
- **WHEN** a person has no CTC on file
- **THEN** their row shows "No CTC" and cannot be submitted

### Requirement: Export for payroll
The system SHALL export approved proposals as CSV for payroll to appraisal roles only, with formula-safe values and an audit entry.

#### Scenario: Export
- **WHEN** HR exports
- **THEN** the file contains only approved or released proposals and the export is logged
