# Spec Delta

## ADDED Requirements

### Requirement: Jira sprint names
The system SHALL store the name of each Jira sprint it syncs, as Jira returns it in the issue's sprint field (in both the text and the object form), and SHALL update it when the sprint is renamed in Jira. A sprint without a name in Jira SHALL be stored without one.

#### Scenario: Name stored
- **WHEN** a synced issue is in Jira sprint 142 named "Atlas 2.4.1"
- **THEN** sprint J142 is stored with the name "Atlas 2.4.1"

#### Scenario: Sprint renamed
- **WHEN** sprint 142 is renamed "Atlas 2.4.2" in Jira and an issue in it syncs again
- **THEN** the stored name becomes "Atlas 2.4.2", with no second sprint row

#### Scenario: Sprints synced before the change
- **WHEN** sprints were stored before names were synced, and the next sync reads every issue
- **THEN** every dated sprint gets its name
