# Spec Delta

## Purpose

Defines the append-only audit trail of sensitive actions, its tamper resistance and who may read it.

## ADDED Requirements

### Requirement: Audited events
The system SHALL write an audit entry for identity events: sign-in, failed sign-in, lockout, password changes and resets; and for administration events: role and permission changes, user create, edit, deactivate and delete, and credential changes.

#### Scenario: Role change
- **WHEN** an Admin changes a role's permissions
- **THEN** an entry records actor, time, role, and the before and after permission sets

#### Scenario: Salary read
- **WHEN** a Manager opens the appraisal evidence of a report
- **THEN** an entry records the actor, the subject and the fields group read

### Requirement: Audited business and data-access events
The system SHALL write an audit entry for overrides (Discipline, bug attribution, appraisal exceptions), confirmations and rejections of points, imports, commits and undos, publish of 360° results, appraisal proposals, approvals, send-backs and releases, salary reads and exports, 360° result reads, data exports, and refused separation-of-duties or permission checks.

#### Scenario: Refused self-approval
- **WHEN** a user attempts to approve their own increment
- **THEN** a refused-outcome entry is written

### Requirement: Entry content
Each entry SHALL carry a unique id, UTC timestamp, actor id, actor role, action, target type and id, outcome (success or refused), source IP, request id and a structured before/after summary. Entries SHALL NOT contain passwords, secrets, session ids or full free-text of reviews.

#### Scenario: No secrets in entries
- **WHEN** credentials for an integration are changed
- **THEN** the entry names the tool and field names changed but contains no secret value

### Requirement: Append-only
The system SHALL store audit entries in an insert-only table, SHALL write each entry in the same transaction as the action it records, and SHALL revoke update and delete rights on the table from the application account. Cryptographic chaining is not required in v1.

#### Scenario: Attempted edit
- **WHEN** the application account tries to update or delete an audit row
- **THEN** the database refuses the statement

#### Scenario: Action rolled back
- **WHEN** the audited action fails and its transaction rolls back
- **THEN** no audit row for it remains

### Requirement: Access to the audit log
The system SHALL expose the audit log only to holders of an explicit audit-read permission, SHALL audit every read of it, and SHALL support filtering by actor, target, action and date with export.

#### Scenario: Reader without permission
- **WHEN** a user without audit-read requests the log
- **THEN** access is refused

### Requirement: Audit retention
The system SHALL retain audit entries for at least 24 months. When a user's personal data is deleted, entries SHALL keep the actor or target as an opaque pseudonymous id and SHALL NOT retain the name or email.

#### Scenario: Deleted user in old entries
- **WHEN** a user was deleted and their past actions are viewed
- **THEN** the entries show a pseudonymous id and no name or email
