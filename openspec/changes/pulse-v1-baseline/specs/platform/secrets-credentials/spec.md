# Spec Delta

## Purpose

Defines how integration credentials entered on the Integrations screen are handled in v1: validated, never exposed, masked and audited. In v1 no live connection is made, so no usable secret is stored.

## ADDED Requirements

### Requirement: No usable secret stored in v1
The system SHALL accept credential input on connect and change forms, validate its format, and store only non-secret fields and a masked placeholder (last 4 characters). It SHALL NOT persist the full secret value and SHALL NOT make any outbound call with it.

#### Scenario: Save a token
- **WHEN** an Admin saves a Jira API token
- **THEN** only the masked last 4 characters and non-secret fields are stored and no network request is sent

### Requirement: Write-only display
The system SHALL NOT return any secret to a client. The UI SHALL show only the last 4 characters. A secret field left blank on edit SHALL keep the stored placeholder.

#### Scenario: Reading credentials
- **WHEN** an Admin opens Settings for a connected tool
- **THEN** secrets appear masked to the last 4 characters and the API response contains no full value

#### Scenario: Blank keeps value
- **WHEN** an Admin saves changed non-secret fields and leaves the token blank
- **THEN** the stored placeholder is unchanged

### Requirement: Test before save
The system SHALL enable Save only after a passing connection test. In v1 the test SHALL validate formats and field presence and return a simulated result with the granted scopes of the seeded tool; a failure SHALL show a status and what to check without echoing the secret.

#### Scenario: Invalid format
- **WHEN** the token format is wrong
- **THEN** the form shows what to check, Save stays disabled and stored values are unchanged

### Requirement: No secrets in logs and audit
The system SHALL redact secret input from logs, errors and audit entries; audit entries SHALL name the tool and the fields changed only.

#### Scenario: Audit after change
- **WHEN** credentials are changed
- **THEN** the audit entry names the tool and field names and contains no secret value

### Requirement: Rotation and expiry display
The system SHALL show each credential's last rotation and expiry against the policy (Jira and Confluence 90 days, HRMS 180, GitHub, Google and PagerDuty 365) from seeded state, SHALL show "Token expires in N days" on the tile, and SHALL raise an Admin item in the Action center near expiry.

#### Scenario: Token near expiry
- **WHEN** a seeded token expires in 3 days
- **THEN** the tile shows "Token expires in 3 days" and Fix opens Change credentials

### Requirement: Disconnect
The system SHALL on Disconnect, after confirmation, stop the tool's simulated sync, clear the stored placeholder and record the action in the credential activity log while keeping seeded data.

#### Scenario: Disconnect Slack
- **WHEN** an Admin confirms Disconnect on Slack
- **THEN** the placeholder is cleared, Sync now is disabled and prior kudos remain
