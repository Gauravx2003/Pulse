# users-roles Specification

## Purpose

Defines the user directory and lifecycle (add, edit, deactivate, delete, invite) and the roles and permission-matrix screens.

## Requirements

### Requirement: Directory
The system SHALL list users with name, title, department, email, role, manager, status and last login, support search by name, email or role, and show counts of Admins, Managers, Employees and Invited users.

#### Scenario: Manager's directory
- **WHEN** a Manager without users.all opens Users
- **THEN** only their reports appear

### Requirement: Add user
The system SHALL create a user from full name, unique valid work email, role, manager, job title, department, level L1–L5 and SHALL send a set-password invite link. The user SHALL start as Invited and Employees SHALL get a performance profile automatically.

#### Scenario: Duplicate email
- **WHEN** an email that already exists is submitted
- **THEN** the form shows "A user with this email already exists."

#### Scenario: No password entered by admin
- **WHEN** an Admin adds a user
- **THEN** no password field exists and an invite link is emailed

### Requirement: Edit user
The system SHALL allow editing name, role, manager, title, department, level and status (Active, Invited, Deactivated) but not the email, SHALL audit role and manager changes and SHALL record manager changes as effective-dated.

#### Scenario: Email is read-only
- **WHEN** an Admin edits a user
- **THEN** the email field is disabled

### Requirement: Delete user
The system SHALL ask for confirmation naming what is removed (login, profile, OKRs, extra-work records) and suggesting Deactivate, SHALL prevent self-deletion, and SHALL reassign the deleted user's reports to the deleter, applying the privacy-retention rules.

#### Scenario: Delete self
- **WHEN** an Admin tries to delete their own account
- **THEN** the action is refused

#### Scenario: Reports reassigned
- **WHEN** a Manager with 4 reports is deleted
- **THEN** the 4 reports now report to the deleting Admin

### Requirement: Role limits for managers
The system SHALL let holders of users.team add, edit, delete and import only Employees reporting to them, and SHALL refuse editing their own account through the directory.

#### Scenario: Manager edits peer
- **WHEN** a Manager edits a user who does not report to them
- **THEN** the call returns 403

### Requirement: Permission matrix
The system SHALL show permissions grouped by area against roles with user counts, apply a toggle immediately to everyone with that role, keep Admin switches locked on, and show per role the menu its permissions produce.

#### Scenario: Toggle with confirmation of effect
- **WHEN** an Admin toggles a permission off
- **THEN** the change is saved, audited and shown in the "what each role sees" card

### Requirement: Custom roles
The system SHALL create a custom role from an existing role with a name and description and delete it only when no user holds it.

#### Scenario: Create role
- **WHEN** an Admin creates "HR Partner" from Manager
- **THEN** the role appears with Manager's permissions and zero users

### Requirement: User CSV import and export
The system SHALL support the Users import of the CSV framework with required columns name, email and optional role, manager_email, title, department, level, status and ctc (honoured only for users.all holders and ignored with a warning for others), SHALL allow only Admin, Manager and Employee roles, SHALL allow a manager in the same file, SHALL warn for trainee titles, and SHALL invite new users by set-password link.

#### Scenario: Role "intern"
- **WHEN** a row has role "intern"
- **THEN** the row errors with a hint to use Employee
