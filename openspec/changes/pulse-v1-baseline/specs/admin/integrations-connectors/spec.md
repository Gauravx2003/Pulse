# Spec Delta

## Purpose

Defines the connector tiles that represent tool data in Pulse, how people are matched across tools, simulated sync behaviour, tile states and the Integrations screen. In v1 all tool data is pre-seeded and no live connection, OAuth or webhook exists. Credential handling is in platform/secrets-credentials.

## ADDED Requirements

### Requirement: Connector catalogue
The system SHALL show tiles for Jira, Microsoft TFS / Azure DevOps Server, GitHub, Slack and the HRMS backed by pre-seeded data, and SHALL list Azure DevOps Boards, GitLab, Bitbucket, Confluence, Notion, Teams, calendar, support desk, PagerDuty and CI/CD as available tiles, each with its auth methods, data read and the modules it feeds.

#### Scenario: Seeded connector
- **WHEN** an Admin opens Integrations
- **THEN** seeded tools show as Connected with a "synced N min ago" status and no outbound call is made

#### Scenario: Catalogued but not seeded
- **WHEN** a catalogued tool has no seed data
- **THEN** its tile shows "Not connected" with Connect disabled and a note that it is planned

### Requirement: Read-only behaviour
The system SHALL read tool data only from its seeded store and SHALL produce outbound effects only for explicit user actions: a stale-task nudge and kudos to Slack or Teams, which in v1 are written to the notifications outbox. Reassigning work SHALL be left to the source tool.

#### Scenario: Rebalance suggestion
- **WHEN** a rebalance is suggested
- **THEN** Pulse shows the suggestion and links to Jira and makes no change in Jira

### Requirement: Least-privilege scopes
The system SHALL show for each tool the minimum scopes Pulse needs and the scopes listed by the simulated test, and SHALL warn when the listed scopes exceed what Pulse uses.

#### Scenario: Over-broad scope
- **WHEN** a seeded tool lists a write scope
- **THEN** the test result warns that write scope is not needed

### Requirement: Simulated sync
The system SHALL let an Admin choose a sync mode per tool in Settings (Webhooks + hourly, Hourly poll only, Nightly only) as a stored setting, and SHALL run Sync now as a simulation that re-derives aggregates from seeded data, updates "synced N min ago" and writes a sync log entry. No webhook receiver or external poll exists in v1.

#### Scenario: Sync now
- **WHEN** an Admin clicks Sync now
- **THEN** a sync log entry is written with counts, the tile shows "synced just now" and no network call is made

### Requirement: Person matching
The system SHALL match people across tools by work email, SHALL support manual linking by tool account id where the tool hides emails, SHALL list unmatched accounts in Integrations, and SHALL exclude bots and service accounts from activity by default.

#### Scenario: Hidden email
- **WHEN** a Jira Cloud account exposes no email
- **THEN** it appears under unmatched accounts for manual linking

### Requirement: Tile states
The system SHALL show per tool one of: "Connected · synced N min ago", "Token expires in N days", "Sync failed · code", or "Not connected", with Settings, Fix or Connect actions accordingly.

#### Scenario: Failed sync
- **WHEN** sync fails with 401
- **THEN** the tile shows "Sync failed · 401 Unauthorized" with a Fix action

### Requirement: Jira configuration
The system SHALL let an Admin pick boards, the story points field and the sync mode, and a switch to ignore edits by bots and non-assignees when computing last activity.

#### Scenario: Last activity with bot edits ignored
- **WHEN** the switch is on and only a bot updated an issue
- **THEN** the issue's last activity does not change

### Requirement: Activity and sync logs
The system SHALL show a credential activity log (add, change, disconnect, failed sync with time and person) and a daily sync log with Sync now.

#### Scenario: Manual sync
- **WHEN** an Admin clicks Sync now
- **THEN** a sync runs and a log entry shows its counts and outcome

### Requirement: Access
Only holders of integrations.manage SHALL see the Integrations screen and its credentials table.

#### Scenario: Manager opens Integrations
- **WHEN** a Manager opens the route
- **THEN** the "You don't have access" card is shown
