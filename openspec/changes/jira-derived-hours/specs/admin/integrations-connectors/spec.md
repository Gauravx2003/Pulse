# Spec Delta

## ADDED Requirements

### Requirement: Jira worklogs
The system SHALL store each Jira worklog that the issue search returns as hours logged on its start date. The hours SHALL be credited to the worklog author's Jira account, which is resolved to a person at read time like an issue assignee. A worklog of an unmatched or bot account SHALL count for no person.

#### Scenario: Worklog synced
- **WHEN** a synced issue has a 3-hour worklog started on 24 Nov 2026 by a linked Jira user
- **THEN** that person has 3 hours logged on 24 Nov 2026 from the next read

#### Scenario: Re-sync
- **WHEN** the same worklog is synced again with 4 hours
- **THEN** the person has 4 hours on that day, not 7

### Requirement: Jira work intervals from Start and End fields
The system SHALL let an Admin configure the Jira field ids of Dev start, Dev end, QA start and QA end. For an issue with no worklog and no time spent, the system SHALL record each configured pair that has a start as a work interval with its start, its end (empty while open) and the Jira account it is credited to. Jira time tracking, when present, SHALL take precedence, and no interval SHALL be recorded for that issue.

#### Scenario: Dev interval recorded
- **WHEN** an issue without time tracking has Start Time (Dev) 24 Nov 2026 10:00 and End Time (Dev) 25 Nov 2026 13:00
- **THEN** a Dev work interval with those times is stored for the issue, credited to the Jira account that was the issue's assignee at 24 Nov 2026 10:00

#### Scenario: Hand-over from Dev to QA
- **WHEN** an issue was assigned to a developer during its Dev interval and to a QA engineer when its QA interval started
- **THEN** the Dev interval is credited to the developer and the QA interval to the QA engineer, whoever the assignee is now

#### Scenario: Open interval of a resolved issue
- **WHEN** an issue without time tracking has a Start Time (Dev) and no End Time (Dev), and was resolved on 25 Nov 2026 at 12:00
- **THEN** the stored Dev interval ends at 25 Nov 2026 12:00

#### Scenario: Time tracking wins
- **WHEN** an issue has a worklog and also Start and End Time (Dev)
- **THEN** only the worklog counts as hours logged for that issue

#### Scenario: Interval moved
- **WHEN** End Time (Dev) of an issue is changed in Jira and the issue syncs again
- **THEN** the stored interval has the new end and no hours remain from the old one

#### Scenario: Fields not configured
- **WHEN** the Dev and QA field ids are empty
- **THEN** no work interval is stored and hours logged come from worklogs only

### Requirement: Field discovery for Start and End fields
The system SHALL, during Jira Test connection, propose the datetime custom fields named "Start Time (Dev)", "End Time (Dev)", "Start Time (QA)" and "End Time (QA)" for the Admin to confirm, in the same way as the story points and sprint fields.

#### Scenario: Fields proposed
- **WHEN** the instance has datetime fields named Start Time (Dev) and End Time (Dev)
- **THEN** Test connection proposes their ids for Dev start and Dev end, and they are used only after the Admin confirms and saves
