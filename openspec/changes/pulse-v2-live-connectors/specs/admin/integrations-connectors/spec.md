# Spec Delta

## Purpose

Phase 2 changes to the connector tiles and live connectors. Live connectors plug in generically. Jira Server / Data Center comes first (self-hosted, REST API v2) and GitHub follows. This delta also covers one sync per tool at a time, the unmatched accounts panel and what Disconnect keeps. It replaces the earlier Jira Cloud assumptions: `/rest/api/3`, `.atlassian.net`, HTTPS only, `accountId`.

## ADDED Requirements

### Requirement: Live connector plug-in
The system SHALL let a module register a live connector for a tool through its module descriptor. A live connector SHALL declare its non-secret config fields, its secret, its allowed origins and its required scopes, and SHALL provide a connection test and a paged read of changes since a cursor. It SHALL reach the network only through the platform's guarded HTTP client and SHALL write only through `IngestService`.

#### Scenario: Connector registered by a module
- **WHEN** the connectors module registers the Jira connector
- **THEN** the Jira tile uses it for Test connection and Sync now, and no platform code references the connector

#### Scenario: Direct network access refused
- **WHEN** connector code tries a request outside the guarded client, or a non-GET request or an origin it did not declare through it
- **THEN** the request is refused and nothing leaves the process

### Requirement: Generic connector screens
The system SHALL render the credentials and settings form of every live connector from the fields it declares, and SHALL run Test connection, Save, Sync now, Disconnect, tile states, logs and the unmatched accounts panel through the same tool-independent routes and screens, so that adding a connector needs no platform or Integrations screen change.

#### Scenario: Second connector added
- **WHEN** the GitHub connector is registered after Jira
- **THEN** the GitHub tile offers Test connection, Save and Sync now with its own fields, and no platform or Integrations screen code was changed for it

### Requirement: One sync per tool at a time
The system SHALL run at most one live sync per tool at a time and SHALL answer a second Sync now for the same tool, while one is running, with a conflict.

#### Scenario: Double click on Sync now
- **WHEN** an Admin clicks Sync now on Jira while a Jira sync is running
- **THEN** the second request is refused with 409, the tile shows the running sync and only one sync log entry is written

### Requirement: Partial sync keeps written pages
The system SHALL ingest a live sync page by page, each page in its own transaction, SHALL keep the pages already ingested when the sync stops on an error, and SHALL advance the stored cursor only when the whole sync succeeds.

#### Scenario: Server error on the second page
- **WHEN** Jira answers 500 on the second page of the issue search
- **THEN** the first page's rows stay, the cursor is unchanged, and the tile shows "Sync failed · 500"

### Requirement: Unmatched accounts panel
The system SHALL show Admins a list of tool accounts seen by live syncs that are not linked to a person, with tool, username or login, display name and first-seen time. It SHALL let an Admin link an account to a person, unlink it, and mark it as a bot, and SHALL audit each change.

#### Scenario: Mark as bot
- **WHEN** an Admin marks a Jira service user as a bot
- **THEN** it leaves the unmatched list and its edits are excluded from activity and last-activity figures

#### Scenario: Account already linked
- **WHEN** an Admin links a tool account that is already linked to another person
- **THEN** the link is refused with 409 and the existing link is unchanged

### Requirement: Disconnect keeps live data
The system SHALL, when a live tool is disconnected, delete its stored secret, stop further syncs, and keep every row already ingested from that tool and every seed row.

#### Scenario: Disconnect Jira
- **WHEN** an Admin confirms Disconnect on Jira after a sync
- **THEN** the Jira issues stay on the work screens with their Jira badge and Sync now is disabled until new credentials pass a test

### Requirement: Jira Server live connector
The system SHALL provide a Jira connector for a self-hosted Jira Server or Data Center instance that reads issues from `GET /rest/api/2/search` (JQL `project in (<keys>) AND updated >= <cursor>`, `expand=changelog`, paged by `startAt`/`maxResults`) and upserts them through `IngestService` with `source = 'jira'` and the issue key as `external_id`.

#### Scenario: Issues fetched
- **WHEN** an Admin runs Sync now on a connected Jira tile
- **THEN** issues with status, estimate, remaining time, story points, sprint and assignee are stored with `source = 'jira'`

#### Scenario: Incremental sync
- **WHEN** a second sync runs after some issues changed
- **THEN** only issues updated since the stored cursor are fetched and the changed rows are updated in place

#### Scenario: Wiki markup description
- **WHEN** an issue description is a Jira wiki markup string
- **THEN** it is stored as text and no structured-document parsing is attempted

### Requirement: Jira Server authentication
The system SHALL authenticate to Jira Server with Basic auth (`Authorization: Basic base64(username:password)`) by default, and SHALL use `Authorization: Bearer <token>` instead when the Admin selects personal access token. The Jira form SHALL collect these fields:
- Base URL
- Username (Basic auth only)
- Password / Token
- Project Keys
- an optional Expiry Date

#### Scenario: Basic auth
- **WHEN** an Admin tests a Jira connection with a username and password
- **THEN** the requests carry a Basic authorization header and the password appears in no response or log

#### Scenario: Personal access token
- **WHEN** an Admin selects personal access token and enters a token
- **THEN** the requests carry a Bearer authorization header and the username field is not required

### Requirement: Jira field discovery
The system SHALL read the instance's fields from `GET /rest/api/2/field` during Test connection and SHALL offer the custom fields that look like story points and sprint for the Admin to confirm, storing the chosen field ids in the connector config.

#### Scenario: Custom field ids differ
- **WHEN** the instance names its story points field `customfield_10106`
- **THEN** the form proposes it, the Admin confirms it, and the sync reads story points from that field

### Requirement: GitHub token types
The system SHALL accept for GitHub a fine-grained personal access token (recommended, read-only on one repository) or a classic token, SHALL show "Fine-grained token: permissions set on GitHub" when no scopes are reported, and SHALL read the token expiry from the response header when present.

#### Scenario: Fine-grained GitHub token
- **WHEN** an Admin tests a fine-grained token that can read the configured repository
- **THEN** the test passes, shows the login, "Fine-grained token: permissions set on GitHub" and the expiry date from GitHub

## MODIFIED Requirements

### Requirement: Real connection test
The system SHALL let an Admin test a Jira or GitHub connection before saving and SHALL make a real authenticated request:
- for Jira, `GET /rest/api/2/myself` followed by `GET /rest/api/2/field`
- for GitHub, `GET /user` and `GET /repos/{owner}/{repo}`

The test SHALL pass only on HTTP 200, SHALL show the authenticated account and the granted scopes where the tool reports them, and SHALL show the status and what to check on failure without echoing the secret.

#### Scenario: Valid token
- **WHEN** the GitHub token is valid and has access to the repository
- **THEN** the test passes, shows the account login and scopes (or the fine-grained note), and Save becomes available

#### Scenario: Valid Jira credentials
- **WHEN** the Jira username and password are valid
- **THEN** the test passes, shows the Jira username and display name, proposes the story points and sprint fields, and Save becomes available

#### Scenario: Rejected token
- **WHEN** the tool answers 401
- **THEN** the test fails with "401 Unauthorized, check the username and password or token", Save stays disabled and stored values are unchanged

### Requirement: Safe outbound requests
The system SHALL send live connector requests only to the origins a connector declares, as follows:
- **Jira:** exactly the scheme, host and port of the Admin-configured base URL. This may be `http://` or `https://` and may be a private LAN address or host name, for example `http://172.16.10.6:8080`.
- **GitHub:** `https://api.github.com`.

The system SHALL reject a base URL that has user info, a query or a fragment, or that resolves to a loopback, link-local or cloud metadata address. It SHALL treat every origin other than the configured one as disallowed, private addresses included. It SHALL NOT follow redirects. It SHALL apply a connect and read timeout to every request. It SHALL stop a sync that hits a rate limit or server error instead of retrying in a loop.

#### Scenario: LAN Jira allowed
- **WHEN** an Admin saves the base URL `http://172.16.10.6:8080`
- **THEN** requests to that origin are allowed, and the form shows a warning that credentials travel unencrypted over `http`

#### Scenario: Redirect to another internal host
- **WHEN** the configured Jira answers a request with a redirect to `http://10.0.0.5/`
- **THEN** the redirect is not followed, the request fails and no request reaches the other host

#### Scenario: Disallowed Jira host
- **WHEN** an Admin enters the base URL `http://169.254.169.254`
- **THEN** the form rejects it and no request is sent

### Requirement: Jira configuration
The system SHALL let an Admin set the following for Jira:
- the project keys to sync
- the story points and sprint field ids (proposed by field discovery)
- the sync mode (stored only)
- a switch to ignore edits by bots and non-assignees when computing last activity, read from the changelog returned with the issue search

#### Scenario: Last activity with bot edits ignored
- **WHEN** the switch is on and only a bot updated an issue
- **THEN** the issue's last activity does not change

#### Scenario: Project scope
- **WHEN** the project keys are `PUL` and `OPS`
- **THEN** Sync now fetches only issues from those two projects

### Requirement: Person matching
The system SHALL match people across tools by work email (case-insensitive). The system SHALL support:
- manual linking by the tool's account identifier: the Jira username (`name`) or the GitHub login
- listing unmatched accounts in Integrations
- excluding bots and service accounts from activity by default

For Jira, matching SHALL use `assignee.emailAddress` and changelog `author.emailAddress`, falling back to `assignee.name` / `author.name` as the stored account identifier. A live record whose author or assignee is unmatched SHALL be stored without a person and SHALL stay out of per-person figures until the account is linked.

#### Scenario: Email matches
- **WHEN** a Jira assignee's `emailAddress` equals a person's work email in a different letter case
- **THEN** the issue is counted for that person on the next read

#### Scenario: Hidden email
- **WHEN** a Jira user has no `emailAddress` in the response
- **THEN** the user appears under unmatched accounts with their Jira username for manual linking

#### Scenario: Link after sync
- **WHEN** an Admin links an unmatched Jira username to a person
- **THEN** that person's existing Jira rows count in their figures from the next read

## REMOVED Requirements

### Requirement: Jira Cloud live connector
**Reason**: Pulse targets the internal self-hosted Jira Server / Data Center instance, which exposes REST API v2, not Jira Cloud's `/rest/api/3/search/jql`.
**Migration**: Replaced by "Jira Server live connector" and "Jira Server authentication" in this change.
