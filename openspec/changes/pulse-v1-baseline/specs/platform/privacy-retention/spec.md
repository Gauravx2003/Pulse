# Spec Delta

## Purpose

Defines how personal, salary and feedback data are protected, retained, anonymized and deleted, including what happens to 360° comments when a user leaves.

## ADDED Requirements

### Requirement: Data retention windows
The system SHALL keep raw tool events for 13 months, SHALL keep aggregated scores with the review record they belong to, and SHALL purge expired raw events automatically.

#### Scenario: Event ages out
- **WHEN** a raw Jira event is older than 13 months
- **THEN** it is deleted while the aggregated scores derived from it remain

### Requirement: User deletion and anonymization
The system SHALL, when a user is deleted, remove their personal data (name, email, contact, free-text pulse notes, 1:1 notes, kudos text they wrote) within 30 days, SHALL replace remaining references in aggregates and the audit log with an opaque pseudonymous id, and SHALL tell the Admin before confirming what is removed and what is retained.

#### Scenario: Deletion confirmation
- **WHEN** an Admin deletes a user
- **THEN** the dialog lists what is removed and what is kept anonymized and suggests Deactivate as an alternative

#### Scenario: Name no longer resolvable
- **WHEN** 30 days have passed after deletion
- **THEN** no table resolves the pseudonymous id back to a name, email or free text of that user

### Requirement: 360° comment retention on deletion
The system SHALL keep 360° comments after a user is deleted only in anonymized form: reviewer ids are unlinked from comments immediately, and comments written about a deleted subject are retained only as comments stripped of the subject's identity and of names and emails detected in the text, attributed to no person, and used only for aggregate culture analysis. The subject MAY request full removal of comments about them.

#### Scenario: Reviewer deleted
- **WHEN** a reviewer who wrote comments is deleted
- **THEN** their comments remain on the subject's results with no link to the reviewer

#### Scenario: Subject deleted
- **WHEN** a subject is deleted
- **THEN** comments about them are unlinked from any person record, scrubbed of detected names and emails, and no longer appear in any individual view

### Requirement: Deactivation versus deletion
The system SHALL keep a deactivated user's records for the retention windows, SHALL block sign-in and sessions, and SHALL show deactivated reports to managers only with users.all.

#### Scenario: Deactivated report
- **WHEN** a Manager without users.all lists their reports
- **THEN** deactivated reports are not shown

### Requirement: Salary and appraisal data protection
The system SHALL return salary and increment values only to appraisal.manage or appraisal.approve holders within their visibility, SHALL exclude them from logs, search results and CSV exports other than the appraisal export and the Users export for users.all holders, and SHALL audit every read. Field-level encryption is deferred beyond v1.

#### Scenario: Employee requests salary
- **WHEN** an Employee requests any appraisal or salary endpoint
- **THEN** access is refused

### Requirement: Data subject requests
The system SHALL let an Admin with users.all export all personal data held about a person in a portable format and SHALL support correction and deletion requests, each recorded in the audit log and completed within 30 days.

#### Scenario: Export request
- **WHEN** an Admin requests a person's data export
- **THEN** a file with that person's records is produced, the read is audited and sensitive 360° commenter identities are not included

### Requirement: Deleted data not restored silently
The system SHALL keep a record of deletions (pseudonymous id and date) so that any restore from a backup can re-apply them. Formal backup procedures are deferred beyond v1.

#### Scenario: Deletion record
- **WHEN** a user is deleted
- **THEN** a deletion record with a pseudonymous id and date is kept
