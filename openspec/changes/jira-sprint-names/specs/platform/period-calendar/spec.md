# Spec Delta

## ADDED Requirements

### Requirement: Sprint names
The system SHALL label a sprint by its name when it has one, and as "Sprint N" by its number when it has none. The same label SHALL be used wherever the sprint is named: the period picker, period titles, Work items, release sprint lists, Effort items, workload actions and the Action Center forecast. When two sprints in the period list have the same name, each label SHALL also show its sprint key so they can be told apart. A sprint's key SHALL not change with its name.

#### Scenario: Live sprint with a Jira name
- **WHEN** a live sprint named "Atlas 2.4.1" runs 8 Sep to 4 Oct 2026
- **THEN** the period picker lists it as "Atlas 2.4.1 · 8 Sep – 2 Oct", and its issues show "Atlas 2.4.1" in Work items' sprint column

#### Scenario: Seed sprint without a name
- **WHEN** seeded sprint S43 has no name
- **THEN** it is labelled "Sprint 43" as before, and every figure for it is unchanged

#### Scenario: Same name on two boards
- **WHEN** two sprints are both named "Sprint 12" on different boards
- **THEN** each label also shows its key, for example "Sprint 12 (J140)" and "Sprint 12 (J141)"

#### Scenario: Name changed in the tool
- **WHEN** a sprint is renamed in Jira and synced again
- **THEN** the new name is shown, and a saved period choice for that sprint still opens it
