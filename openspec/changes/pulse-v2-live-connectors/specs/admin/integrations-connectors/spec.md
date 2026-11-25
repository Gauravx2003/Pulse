# Spec Delta

## Purpose

Phase 2 additions to the connector tiles and live connectors (GitHub first, then Jira): how live connectors plug in generically, one sync at a time per tool, the unmatched accounts panel and what Disconnect keeps.

## ADDED Requirements

### Requirement: Live connector plug-in
The system SHALL let a module register a live connector for a tool through its module descriptor. A live connector SHALL declare its non-secret config fields, its secret, its allowed hosts and its required scopes, and SHALL provide a connection test and a paged read of changes since a cursor. It SHALL reach the network only through the platform's guarded HTTP client and SHALL write only through `IngestService`.

#### Scenario: Connector registered by a module
- **WHEN** the connectors module registers the GitHub connector
- **THEN** the GitHub tile uses it for Test connection and Sync now, and no platform code references the connector

#### Scenario: Direct network access refused
- **WHEN** connector code tries a request outside the guarded client, or a non-GET request or a host it did not declare through it
- **THEN** the request is refused and nothing leaves the process

### Requirement: Generic connector screens
The system SHALL render the credentials and settings form of every live connector from the fields it declares, and SHALL run Test connection, Save, Sync now, Disconnect, tile states, logs and the unmatched accounts panel through the same tool-independent routes and screens, so that adding a connector needs no platform or Integrations screen change.

#### Scenario: Second connector added
- **WHEN** the Jira connector is registered after GitHub
- **THEN** the Jira tile offers Test connection, Save and Sync now with its own fields, and no platform or Integrations screen code was changed for it

### Requirement: One sync per tool at a time
The system SHALL run at most one live sync per tool at a time and SHALL answer a second Sync now for the same tool, while one is running, with a conflict.

#### Scenario: Double click on Sync now
- **WHEN** an Admin clicks Sync now on Jira while a Jira sync is running
- **THEN** the second request is refused with 409, the tile shows the running sync and only one sync log entry is written

### Requirement: Partial sync keeps written pages
The system SHALL ingest a live sync page by page, each page in its own transaction, SHALL keep the pages already ingested when the sync stops on an error, and SHALL advance the stored cursor only when the whole sync succeeds.

#### Scenario: Rate limit on the second page
- **WHEN** GitHub answers 429 on the second page of pull requests
- **THEN** the first page's rows stay, the cursor is unchanged, and the tile shows "Sync failed · 429"

### Requirement: Unmatched accounts panel
The system SHALL show Admins a list of tool accounts seen by live syncs that are not linked to a person, with tool, login or display name and first-seen time. It SHALL let an Admin link an account to a person, unlink it, and mark it as a bot, and SHALL audit each change.

#### Scenario: Mark as bot
- **WHEN** an Admin marks a GitHub account as a bot
- **THEN** it leaves the unmatched list and its activity is excluded from activity and last-activity figures

#### Scenario: Account already linked
- **WHEN** an Admin links a tool account that is already linked to another person
- **THEN** the link is refused with 409 and the existing link is unchanged

### Requirement: Disconnect keeps live data
The system SHALL, when a live tool is disconnected, delete its stored secret, stop further syncs, and keep every row already ingested from that tool and every seed row.

#### Scenario: Disconnect Jira
- **WHEN** an Admin confirms Disconnect on Jira after a sync
- **THEN** the Jira issues stay on the work screens with their Jira badge and Sync now is disabled until new credentials pass a test

### Requirement: GitHub token types
The system SHALL accept for GitHub a fine-grained personal access token (recommended, read-only on one repository) or a classic token, SHALL show "Fine-grained token: permissions set on GitHub" when no scopes are reported, and SHALL read the token expiry from the response header when present.

#### Scenario: Fine-grained GitHub token
- **WHEN** an Admin tests a fine-grained token that can read the configured repository
- **THEN** the test passes, shows the login, "Fine-grained token: permissions set on GitHub" and the expiry date from GitHub

### Requirement: Jira token type
The system SHALL accept for Jira only a classic (unscoped) Atlassian API token, SHALL say so on the form, and SHALL take the token expiry date from the Admin because the API does not report it.

#### Scenario: Scoped Jira token
- **WHEN** an Admin tests a scoped Atlassian API token against the site URL
- **THEN** the test fails with the status and a hint to create a classic API token, and nothing is stored

### Requirement: Jira sync scope by project key
The system SHALL limit a Jira sync to the project keys an Admin enters in Jira Settings, and SHALL reject a key that the test account cannot browse when the settings are saved.

#### Scenario: Two projects configured
- **WHEN** the project keys are `PUL` and `OPS`
- **THEN** Sync now fetches only issues from those two projects
