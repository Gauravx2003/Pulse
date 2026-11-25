# Spec Delta

## Purpose

Phase 2 changes to credential handling for the live Jira Server and GitHub connectors: what is encrypted, where a secret may be decrypted, how save is bound to a passing test, and how unreadable secrets are handled.

## ADDED Requirements

### Requirement: Decryption only where needed
The system SHALL decrypt a stored Jira or GitHub secret only inside a test or sync run and SHALL never send a stored secret to the client, logs, the sync log or the audit log.

#### Scenario: Settings opened after save
- **WHEN** an Admin opens Settings for a connected Jira tile
- **THEN** the response holds the non-secret config (base URL, username, project keys, field ids) and the last 4 characters of the secret only, and no ciphertext

### Requirement: Save bound to a passing test
The system SHALL accept a Jira or GitHub credential save only with proof of a passing connection test for the same tool and the same values, made within the last 10 minutes and usable once.

#### Scenario: Values changed after test
- **WHEN** an Admin passes the test, then edits the password and saves without testing again
- **THEN** the save is rejected and the stored credential is unchanged

### Requirement: Unreadable secret
The system SHALL, when a stored secret cannot be decrypted (key missing, changed or ciphertext altered), send no request, mark the tile "Sync failed · key", and ask the Admin to enter the credentials again.

#### Scenario: Key changed
- **WHEN** `PULSE_SECRET_KEY` is changed and an Admin clicks Sync now on Jira
- **THEN** no request is sent, the tile shows "Sync failed · key" and Fix opens Change credentials

## MODIFIED Requirements

### Requirement: Secret storage
The system SHALL store the following encrypted at rest with AES-256-GCM under a key taken from the environment (`PULSE_SECRET_KEY`):
- the Jira Server password or personal access token
- the GitHub personal access token

The system SHALL refuse to save or start a live connector when the key is missing. For every other tool it SHALL store only non-secret fields and a masked placeholder (last 4 characters), and SHALL make no outbound call with it.

#### Scenario: Save a Jira token
- **WHEN** an Admin saves a Jira password or personal access token after a passing test
- **THEN** the database holds only the ciphertext, its IV, the last 4 characters and non-secret fields, and the plain password is in no table or log

#### Scenario: Save a Slack token
- **WHEN** an Admin saves a Slack token
- **THEN** only the masked last 4 characters and non-secret fields are stored and no network request is sent

#### Scenario: Missing key
- **WHEN** no `PULSE_SECRET_KEY` is set and an Admin saves a Jira password
- **THEN** the save is rejected with a clear message and nothing is stored
