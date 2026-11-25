# Spec Delta

## Purpose

Phase 2 additions to credential handling for the live GitHub and Jira connectors: where a secret may be decrypted, how save is bound to a passing test, and how unreadable secrets are handled.

## ADDED Requirements

### Requirement: Decryption only where needed
The system SHALL decrypt a stored GitHub or Jira secret only inside the sync run and SHALL never send a stored secret to the client, the test endpoint, logs, the sync log or the audit log.

#### Scenario: Settings opened after save
- **WHEN** an Admin opens Settings for a connected GitHub tile
- **THEN** the response holds the non-secret config and the last 4 characters only, and no ciphertext

### Requirement: Save bound to a passing test
The system SHALL accept a GitHub or Jira credential save only with proof of a passing connection test for the same tool and the same values, made within the last 10 minutes and usable once.

#### Scenario: Values changed after test
- **WHEN** an Admin passes the test, then edits the token and saves without testing again
- **THEN** the save is rejected and the stored credential is unchanged

### Requirement: Unreadable secret
The system SHALL, when a stored secret cannot be decrypted (key missing, changed or ciphertext altered), send no request, mark the tile "Sync failed · key", and ask the Admin to enter the credentials again.

#### Scenario: Key changed
- **WHEN** `PULSE_SECRET_KEY` is changed and an Admin clicks Sync now on Jira
- **THEN** no request is sent, the tile shows "Sync failed · key" and Fix opens Change credentials
