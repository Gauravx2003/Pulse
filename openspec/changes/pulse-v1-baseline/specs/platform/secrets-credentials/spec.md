# Spec Delta

## Purpose

Defines how integration credentials entered on the Integrations screen are handled in v1: validated, never exposed, masked and audited. The GitHub token and Jira API token must be usable for live calls, so they are stored encrypted; credentials of seeded tools stay masked placeholders only.

## ADDED Requirements

### Requirement: Secret storage
The system SHALL store the GitHub personal access token and the Jira API token encrypted at rest with AES-256-GCM under a key taken from the environment (`PULSE_SECRET_KEY`), SHALL refuse to start a live connector when the key is missing, and SHALL store for every other tool only non-secret fields and a masked placeholder (last 4 characters) and make no outbound call with it.

#### Scenario: Save a Jira token
- **WHEN** an Admin saves a Jira API token after a passing test
- **THEN** the database holds only the ciphertext, the last 4 characters and non-secret fields, and the plain token is in no table or log

#### Scenario: Save a Slack token
- **WHEN** an Admin saves a Slack token
- **THEN** only the masked last 4 characters and non-secret fields are stored and no network request is sent

#### Scenario: Missing key
- **WHEN** no `PULSE_SECRET_KEY` is set and an Admin saves a GitHub token
- **THEN** the save is rejected with a clear message and nothing is stored

### Requirement: Write-only display
The system SHALL NOT return any secret to a client. The UI SHALL show only the last 4 characters. A secret field left blank on edit SHALL keep the stored value.

#### Scenario: Reading credentials
- **WHEN** an Admin opens Settings for a connected tool
- **THEN** secrets appear masked to the last 4 characters and the API response contains no full value or ciphertext

#### Scenario: Blank keeps value
- **WHEN** an Admin saves changed non-secret fields and leaves the token blank
- **THEN** the stored secret is unchanged

### Requirement: Test before save
The system SHALL enable Save only after a passing connection test. For GitHub and Jira the test SHALL be the real authenticated request defined in admin/integrations-connectors; for the other tools it SHALL validate formats and field presence and return a simulated result with the granted scopes of the seeded tool. A failure SHALL show a status and what to check without echoing the secret.

#### Scenario: Invalid format
- **WHEN** the token format is wrong
- **THEN** the form shows what to check, Save stays disabled and stored values are unchanged

### Requirement: No secrets in logs and audit
The system SHALL redact secret input and the Authorization header from logs, errors, sync log entries and audit entries; audit entries SHALL name the tool and the fields changed only.

#### Scenario: Audit after change
- **WHEN** credentials are changed
- **THEN** the audit entry names the tool and field names and contains no secret value

#### Scenario: Failed request logged
- **WHEN** a live request fails
- **THEN** the log shows the tool, URL path and status and no token

### Requirement: Rotation and expiry display
The system SHALL show each credential's last rotation and expiry against the policy (Jira and Confluence 90 days, HRMS 180, GitHub, Google and PagerDuty 365) from stored state, SHALL show "Token expires in N days" on the tile, and SHALL raise an Admin item in the Action center near expiry.

#### Scenario: Token near expiry
- **WHEN** a token expires in 3 days
- **THEN** the tile shows "Token expires in 3 days" and Fix opens Change credentials

### Requirement: Disconnect
The system SHALL on Disconnect, after confirmation, stop the tool's sync, delete the stored secret or placeholder and record the action in the credential activity log while keeping previously ingested data.

#### Scenario: Disconnect Slack
- **WHEN** an Admin confirms Disconnect on Slack
- **THEN** the placeholder is cleared, Sync now is disabled and prior kudos remain

#### Scenario: Disconnect GitHub
- **WHEN** an Admin confirms Disconnect on GitHub
- **THEN** the ciphertext is deleted, no further request is made with it and the pull requests already synced remain
