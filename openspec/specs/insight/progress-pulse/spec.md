# progress-pulse Specification

## Purpose

Defines each person's Progress page (next steps, achievements, trends) and the weekly pulse check.

## Requirements

### Requirement: Next steps
The system SHALL collect next steps from every screen with an Open button each: stale issues, code-only days, PR titles to fix, reviews to give, training feedback to acknowledge, sign-off or check-ins due, visit reports, the weekly pulse and OKRs off track.

#### Scenario: Nothing waiting
- **WHEN** there are no next steps
- **THEN** the card reads "Nothing waiting — nice work."

### Requirement: Achievements
The system SHALL award achievements from data, each tied to a culture value: Daily updates, PR standard, Great reviewer, Commitment keeper, Proactive fixer, Delighted customer, Training completed, Mentor and Key result achieved.

#### Scenario: Key result achieved
- **WHEN** a key result reaches its target
- **THEN** an achievement appears with its value badge

### Requirement: Trends
The system SHALL show the performance index over 4 quarters, utilization by sprint with the overall average, culture values Q2 to Q3 sorted by biggest gain, OKRs this quarter, PR standard by month and training ratings.

#### Scenario: Biggest gain first
- **WHEN** culture values are compared
- **THEN** they are sorted by gain descending

### Requirement: Weekly pulse
Every Friday each Employee SHALL be asked for mood 1–5 and workload 1–5 with an optional note; both answers SHALL be required; answers SHALL be visible only to the person, their manager and holders of all.view; a mood of 2 or lower or a workload of 5 SHALL notify the manager and create a Do today item.

#### Scenario: Missing answer
- **WHEN** only mood is answered
- **THEN** the form shows "Answer both questions."

#### Scenario: Peer view
- **WHEN** a peer requests another's pulse
- **THEN** access is refused

### Requirement: Pulse note handling
Pulse notes SHALL be length-limited, rendered as text, excluded from logs and removed on user deletion per privacy-retention.

#### Scenario: Log check
- **WHEN** a pulse is submitted
- **THEN** logs contain no note text

### Requirement: Team wins
Team wins SHALL be visible to the whole team and SHALL list kudos, 5-star visits, completed training and proactive fixes.

#### Scenario: Visible to peers
- **WHEN** a teammate opens Team wins
- **THEN** wins are listed without private data such as pulse or salary

### Requirement: Employee parity
An Employee SHALL see on Progress what their manager sees about them, with the reference of every number, and managers SHALL see Start 1:1.

#### Scenario: Same numbers
- **WHEN** a manager and the Employee open the same Progress
- **THEN** the KPIs are identical
