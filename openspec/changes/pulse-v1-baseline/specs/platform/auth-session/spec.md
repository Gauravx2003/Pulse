# Spec Delta

## Purpose

Defines how users prove who they are with a work email and password, how credentials are stored and recovered, and how sessions are created, limited and revoked. SSO and multi-factor authentication are out of scope for v1.

## ADDED Requirements

### Requirement: Password sign-in
The system SHALL authenticate users with a work email and password and SHALL reject deactivated accounts and accounts whose role no longer exists. After sign-in the user SHALL land on the first page their role allows: Action center for Managers and Admins, Overview for Employees.

#### Scenario: Valid credentials
- **WHEN** an active user submits a correct email and password
- **THEN** a session is created, per-session view state is reset and the user lands on their role's first page

#### Scenario: Wrong credentials give no hint
- **WHEN** the email is unknown or the password is wrong
- **THEN** the system shows "Email or password is incorrect." and the wording is the same for both cases

#### Scenario: Deactivated account
- **WHEN** a deactivated user submits correct credentials
- **THEN** sign-in is refused with "This account is deactivated. Contact HR."

### Requirement: Password storage
The system SHALL store passwords only as salted bcrypt hashes with cost 12 or higher. The system SHALL NOT log, return or email a password.

#### Scenario: No plaintext at rest
- **WHEN** the user table and application logs are inspected after a sign-in and a password change
- **THEN** no password or reversible form of it appears anywhere

### Requirement: Password policy
The system SHALL require user-chosen passwords of at least 8 characters and SHALL reject a password equal to the user's email or name. Passwords inserted by the demo seed SHALL be exempt from this policy.

#### Scenario: Short password
- **WHEN** a user sets a 6-character password
- **THEN** the change is rejected and the message states the 8-character minimum

#### Scenario: Password equals email
- **WHEN** a user sets their email as the password
- **THEN** the change is rejected

### Requirement: Account lockout and throttling
The system SHALL lock an account for 15 minutes after 5 consecutive failed sign-ins and SHALL also throttle sign-in attempts per source IP. Lockout SHALL NOT reveal whether the account exists.

#### Scenario: Lockout after five failures
- **WHEN** 5 consecutive sign-ins fail for one email within 15 minutes
- **THEN** further attempts for that email are refused for 15 minutes even with the correct password and the event is audited

#### Scenario: Many emails from one IP
- **WHEN** one IP submits sign-ins for many different emails above the throttle limit
- **THEN** the IP is rate-limited with a 429 response

### Requirement: Invite and first sign-in
The system SHALL onboard new users with a single-use, expiring set-password link delivered through the outbox (and by email when mail is configured) and SHALL NOT send passwords by any channel. A user SHALL become Active on first successful password set.

#### Scenario: Invite accepted
- **WHEN** an Invited user opens a valid link within its lifetime and sets a compliant password
- **THEN** the user becomes Active, the link is invalidated and a session is created

#### Scenario: Expired or reused link
- **WHEN** a link is expired or already used
- **THEN** it is refused and the user is told to ask HR for a new invite

### Requirement: Password reset and change
The system SHALL let users reset a forgotten password through a single-use expiring link and change their password while signed in by entering the current one. A reset or change SHALL revoke every other session of that user.

#### Scenario: Reset request for unknown email
- **WHEN** someone requests a reset for an email that has no account
- **THEN** the response is identical to a known email and no message is queued

#### Scenario: Reset revokes sessions
- **WHEN** a user completes a password reset
- **THEN** all existing sessions of that user end and the event is audited

### Requirement: Session lifetime and revocation
The system SHALL keep server-side sessions that expire 12 hours after sign-in, SHALL rotate the session identifier on sign-in, and SHALL end a user's sessions immediately when the user is deactivated, deleted, changes their password or has role permissions changed.

#### Scenario: Deactivation ends sessions
- **WHEN** an Admin deactivates a signed-in user
- **THEN** that user's next request returns 401 and the session cannot be reused

#### Scenario: Twelve-hour expiry
- **WHEN** a session is older than 12 hours
- **THEN** the next request requires sign-in again

### Requirement: Session transport
The system SHALL keep the session identifier in a cookie that is HttpOnly, Secure and SameSite=Strict, and SHALL NOT expose it to page scripts or put it in URLs or logs.

#### Scenario: Script cannot read the session
- **WHEN** page script reads `document.cookie`
- **THEN** the session cookie is not present

### Requirement: Demo accounts are flag-gated
The system SHALL create the demo accounts and the password `demo123` only when the setting `PULSE_DEMO_SEED` is on, and SHALL refuse to start under the production profile when that setting is on or a known demo account exists.

#### Scenario: Demo seed on
- **WHEN** `PULSE_DEMO_SEED` is on in a non-production profile
- **THEN** the demo accounts exist and each signs in with `demo123`

#### Scenario: Production start with demo data
- **WHEN** the service starts under the production profile with `PULSE_DEMO_SEED` on
- **THEN** startup fails with an error naming the setting
