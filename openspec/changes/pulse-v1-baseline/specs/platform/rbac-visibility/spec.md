# Spec Delta

## Purpose

Defines roles, permissions and per-person visibility, and guarantees they are enforced on the server for every request. The UI hiding a control is never the only check.

## ADDED Requirements

### Requirement: Built-in roles and permissions
The system SHALL provide the roles Admin, Manager and Employee and the permissions self.view, team.view, all.view, okr.own, okr.team, oneonone, effort.confirm, reviews.calibrate, users.team, users.all, roles.manage, integrations.manage, rules.manage, review360.give, review360.manage, appraisal.manage, appraisal.approve, training.manage and attendance.upload, with the default grants of the reference permission matrix.

#### Scenario: Default Employee grants
- **WHEN** a new Employee is created
- **THEN** the user holds exactly self.view, okr.own, oneonone and review360.give

#### Scenario: Admin keeps every permission
- **WHEN** a permission is added to the catalogue
- **THEN** the Admin role holds it and its switch cannot be turned off

### Requirement: Server-side enforcement on every call
The system SHALL check authentication, permission and visibility on every API call before reading or writing data. A request for data the caller may not see SHALL return the same 403 or 404 as a nonexistent record, so existence is not disclosed.

#### Scenario: Direct URL without permission
- **WHEN** an Employee requests another Employee's overview by id
- **THEN** the API returns 403 with no data and the UI shows "You don't have access"

#### Scenario: Existence is not leaked
- **WHEN** a Manager requests a person who is not a report and a person id that does not exist
- **THEN** both responses are indistinguishable

### Requirement: Visibility rules
The system SHALL show a person's data only to that person, to their current direct manager (team.view), to holders of all.view, and to the exceptions: a mentor sees their mentees' training data and an account owner sees visits they own. Peers SHALL NOT see each other's pages. team.view SHALL apply to direct reports. Attendance check-in times SHALL be visible to the person and their direct manager only.

#### Scenario: Peer access refused
- **WHEN** an Employee requests a peer's Progress page
- **THEN** access is refused

#### Scenario: Mentor exception
- **WHEN** a mentor who is not the manager opens a mentee's training plan
- **THEN** the plan is shown but the mentee's OKRs, attendance and appraisal data are refused

### Requirement: Effective-dated reporting lines
The system SHALL store each person's manager as effective-dated history, SHALL evaluate visibility against the current line, and SHALL use the line in force at the period or cycle date for historical calculations such as appraisal evidence.

#### Scenario: Manager changes mid-quarter
- **WHEN** a person's manager changes on 15 August
- **THEN** the new manager sees the person's data from that date onward as current and the previous manager no longer has access

### Requirement: Single enforcement point
The system SHALL resolve visibility in one policy service that is the only code building visibility filters, and data access for personal, salary, attendance, review and pulse data SHALL go through repository helpers that require a policy scope argument. Every endpoint SHALL have a per-role contract test.

#### Scenario: Repository without a scope
- **WHEN** code calls a repository helper for personal data without a policy scope
- **THEN** the call is rejected at build or test time and cannot return rows

#### Scenario: Contract test matrix
- **WHEN** the contract tests run for an endpoint
- **THEN** Admin, Manager, Employee and peer expectations are each asserted

### Requirement: Separation of duties
The system SHALL refuse these actions on oneself: approving or proposing own increment, overriding own Discipline score, confirming own extra-mile points, verifying own visit, calibrating oneself, resolving own bug dispute, and assigning oneself as 360° reviewer of another subject's results. A refused action SHALL be audited.

#### Scenario: HR approves own increment
- **WHEN** an Admin with appraisal.approve attempts to approve their own proposal
- **THEN** the call fails with 422 and an audit entry is written

### Requirement: Custom roles and least privilege
The system SHALL allow Admins to create custom roles from an existing role and to delete a custom role only when no user holds it. The system SHALL support a role set that separates HR duties (salary, 360°, attendance) from system administration duties (integrations, secrets, roles) so no single role is required to hold both.

#### Scenario: Delete role in use
- **WHEN** an Admin deletes a custom role held by 3 users
- **THEN** the deletion is refused and states the count

#### Scenario: System admin without salary access
- **WHEN** a custom role holds integrations.manage and roles.manage but not appraisal.manage
- **THEN** its users cannot read any salary or appraisal endpoint

### Requirement: Permission changes take effect immediately
The system SHALL apply a role permission change to all users holding the role on their next request and SHALL record the change in the audit log with the previous and new values.

#### Scenario: Revoke while signed in
- **WHEN** an Admin turns off okr.team for the Manager role
- **THEN** Managers' next call to team OKRs returns 403 without needing to sign in again

### Requirement: Managers' user-management limits
The system SHALL restrict holders of users.team to Employees who report to them, SHALL prevent them from granting Manager or Admin roles, and SHALL make new users they create report to them.

#### Scenario: Manager grants Admin
- **WHEN** a Manager attempts to set a report's role to Admin
- **THEN** the call fails with 403 and is audited
