# one-on-ones-kudos Specification

## Purpose

Defines 1:1 meetings with signal-based agendas, shared notes, action items and kudos.

## Requirements

### Requirement: Agenda from signals
The system SHALL generate a suggested agenda for the next 1:1 from signals: stale work, overload, low pulse, training items and visits.

#### Scenario: Overload
- **WHEN** a person is over 100% utilization
- **THEN** the suggested agenda includes workload

### Requirement: Shared notes and action items
Both participants SHALL be able to edit shared notes and action items (owner, due, done), and the notes SHALL be private to the two people and HR (holders of all.view).

#### Scenario: Peer access
- **WHEN** a peer requests the notes
- **THEN** access is refused

### Requirement: Past 1:1s and overdue signal
The system SHALL list past 1:1s with notes and action items, and the last 1:1 date SHALL drive "1:1 overdue" after more than 10 working days.

#### Scenario: Overdue
- **WHEN** the last 1:1 was 11 working days ago
- **THEN** a Medium Do today item appears for the manager

### Requirement: Kudos
A user holding oneonone SHALL give kudos to a person for a value with text, MAY send it to Slack or Teams, SHALL be unable to give kudos to themselves, and kudos SHALL appear in a feed as evidence, not score. Slack /kudos messages SHALL be ingested.

#### Scenario: Self kudos
- **WHEN** a user selects themselves as recipient
- **THEN** the form refuses

### Requirement: Free-text safety
Notes and kudos text SHALL be length-limited and rendered as text only.

#### Scenario: Long note
- **WHEN** a note exceeds the limit
- **THEN** the save is rejected with the limit stated
