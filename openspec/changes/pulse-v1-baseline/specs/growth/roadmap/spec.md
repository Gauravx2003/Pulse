# Spec Delta

## Purpose

Defines the career road map: agreed milestones, promotion readiness and the skills matrix.

## ADDED Requirements

### Requirement: Milestones
The system SHALL show a timeline of milestones agreed in 1:1s. An employee SHALL propose a milestone (outcome, when, value it grows) and it SHALL show as Proposed until the manager confirms it.

#### Scenario: Propose
- **WHEN** an Employee proposes a milestone
- **THEN** it shows Proposed and the manager is notified to confirm

### Requirement: Promotion readiness
The system SHALL show the target level and date with criteria marked Met or Open: performance index at least 4.0 for 2 quarters, led a cross-team epic, mentored 2 engineers, and no culture value below 3.0.

#### Scenario: One criterion open
- **WHEN** the index is 4.1 and 4.2 for two quarters but no cross-team epic was led
- **THEN** that criterion is Open and the others Met

### Requirement: Skills matrix
The system SHALL show skills rated by the manager against the level guide with current level, level needed for the next level and gap ("−1" or "Ready"), and an empty state when no matrix exists for the role.

#### Scenario: Gap
- **WHEN** current is 2 and needed is 3
- **THEN** the gap shows "−1"

### Requirement: Permissions and visibility
Only the person, their manager and all.view holders SHALL see a road map; only the manager SHALL confirm milestones and rate skills.

#### Scenario: Peer access
- **WHEN** a peer opens another's road map
- **THEN** access is refused
