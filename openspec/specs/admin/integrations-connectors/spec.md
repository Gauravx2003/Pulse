# integrations-connectors Specification

## Purpose

Defines the connector tiles that represent tool data in Pulse, how people are matched across tools, sync behaviour, tile states and the Integrations screen. In v1 GitHub (personal access token) and Jira Cloud (API token) are live read-only connectors; every other tool is pre-seeded and simulated, and no OAuth flow or webhook exists. Credential handling is in platform/secrets-credentials.

## Requirements

### Requirement: Connector catalogue
The system SHALL show tiles for Jira, Microsoft TFS / Azure DevOps Server, GitHub, Slack and the HRMS. Jira and GitHub SHALL use live connectors once connected and seeded data until then; the other tiles SHALL use seeded data only. Azure DevOps Boards, GitLab, Bitbucket, Confluence, Notion, Teams, calendar, support desk, PagerDuty and CI/CD SHALL be listed as available tiles with their auth methods, data read and the modules they feed.

#### Scenario: Seeded connector
- **WHEN** an Admin opens Integrations
- **THEN** seeded tools show as Connected with a "synced N min ago" status and no outbound call is made for them

#### Scenario: Live connector connected
- **WHEN** an Admin has saved a passing GitHub or Jira connection
- **THEN** its tile shows a Live badge and the time of the last real sync

#### Scenario: Catalogued but not seeded
- **WHEN** a catalogued tool has no seed data
- **THEN** its tile shows "Not connected" with Connect disabled and a note that it is planned

### Requirement: Read-only behaviour
The system SHALL read tool data only from its stored data, SHALL fill that store from live GitHub and Jira only through read-only GET requests, and SHALL produce other outbound effects only for explicit user actions: a stale-task nudge and kudos to Slack or Teams, which in v1 are written to the notifications outbox. Reassigning work SHALL be left to the source tool.

#### Scenario: Rebalance suggestion
- **WHEN** a rebalance is suggested
- **THEN** Pulse shows the suggestion and links to Jira and makes no change in Jira

#### Scenario: No write calls
- **WHEN** any live connector runs
- **THEN** it sends only GET requests to the tool

### Requirement: Least-privilege scopes
The system SHALL show for each tool the minimum scopes Pulse needs and the scopes reported by the connection test (live for GitHub and Jira, simulated for the other tools), and SHALL warn when the reported scopes exceed what Pulse uses.

#### Scenario: Over-broad scope
- **WHEN** the test of a tool reports a write scope
- **THEN** the test result warns that write scope is not needed

### Requirement: GitHub live connector
The system SHALL provide a GitHub connector that takes a personal access token and an owner and repository, SHALL fetch pull requests from `/repos/{owner}/{repo}/pulls?state=all` and commit activity from `/repos/{owner}/{repo}/commits`, SHALL follow pagination, SHALL aggregate commits into one record per person per day, and SHALL upsert the results through `IngestService` with `source = 'github'` and the GitHub id as `external_id`.

#### Scenario: Pull requests fetched
- **WHEN** an Admin runs Sync now on a connected GitHub tile
- **THEN** pull requests with their state, open and merge times and author are stored with `source = 'github'`

#### Scenario: Commit days aggregated
- **WHEN** a person has three commits on one day
- **THEN** one commit-day record with a count of 3 is stored for that person and day

#### Scenario: Repeat sync
- **WHEN** Sync now runs twice with no new activity on GitHub
- **THEN** no duplicate rows are created and `synced_at` is updated

### Requirement: Jira Cloud live connector
The system SHALL provide a Jira connector that takes a site URL, an account email and an API token, SHALL fetch issues from `/rest/api/3/search/jql` for the configured boards, SHALL follow its next-page token, and SHALL upsert the issues through `IngestService` with `source = 'jira'` and the Jira issue key as `external_id`.

#### Scenario: Issues fetched
- **WHEN** an Admin runs Sync now on a connected Jira tile
- **THEN** issues with status, estimate, remaining time and assignee are stored with `source = 'jira'`

#### Scenario: Incremental sync
- **WHEN** a second sync runs after some issues changed
- **THEN** only issues updated since the stored cursor are fetched and the changed rows are updated in place

### Requirement: Real connection test
The system SHALL let an Admin test a GitHub or Jira connection before saving and SHALL make a real authenticated request: for GitHub `GET /user` and `GET /repos/{owner}/{repo}`, for Jira `GET /rest/api/3/myself`. The test SHALL pass only on HTTP 200, SHALL show the authenticated account and the granted scopes, and SHALL show the status and what to check on failure without echoing the secret.

#### Scenario: Valid token
- **WHEN** the token is valid and has access to the repository
- **THEN** the test passes, shows the account login and scopes, and Save becomes available

#### Scenario: Rejected token
- **WHEN** the tool answers 401
- **THEN** the test fails with "401 Unauthorized, check the token", Save stays disabled and stored values are unchanged

### Requirement: Sync now
The system SHALL run Sync now for GitHub and Jira by calling the connector's `fetchChanges(since)` and writing through `IngestService`, SHALL write a sync log entry with the tool, counts and outcome, and SHALL invalidate the calculation cache so every screen shows the new figures. In v1 a live sync runs only through Sync now.

#### Scenario: Live Sync now
- **WHEN** an Admin clicks Sync now on GitHub
- **THEN** the connector fetches from GitHub, a sync log entry shows its counts, the tile shows "synced just now" and the next read of calculated figures reflects the new rows

#### Scenario: Tool unreachable
- **WHEN** the tool times out or answers 429 or 5xx during a sync
- **THEN** the sync stops, rows already written stay, the tile shows "Sync failed" with the code and a Fix action, and the failure is logged

### Requirement: Simulated sync for seeded tools
The system SHALL let an Admin choose a sync mode per tool in Settings (Webhooks + hourly, Hourly poll only, Nightly only) as a stored setting, and SHALL run Sync now for a seeded tool as a simulation that re-derives aggregates from seeded data, updates "synced N min ago" and writes a sync log entry. No webhook receiver exists in v1.

#### Scenario: Seeded Sync now
- **WHEN** an Admin clicks Sync now on Slack
- **THEN** a sync log entry is written with counts and no network call is made

### Requirement: Seed and live data coexist
The system SHALL load the seed data at startup through `SeedConnector` with `source = 'seed'` whenever the demo seed is on, SHALL keep seed rows when a live connector is connected or synced, SHALL store live rows beside them under their own source, and SHALL have calculation and screens read rows of every source together.

#### Scenario: Both sources present
- **WHEN** GitHub has synced and the demo seed is on
- **THEN** the demo users and all screens remain populated and the live pull requests appear in the same lists and figures as the seeded ones

#### Scenario: Live sync does not remove seed data
- **WHEN** a live sync fails or returns no records
- **THEN** no seed row is changed or deleted

### Requirement: Safe outbound requests
The system SHALL send live connector requests only to `https://api.github.com` for GitHub and to an `https` host ending in `.atlassian.net` for Jira, SHALL reject any other site URL on save, SHALL NOT follow redirects, SHALL apply a connect and read timeout to every request, and SHALL stop a sync that hits a rate limit instead of retrying in a loop.

#### Scenario: Disallowed Jira host
- **WHEN** an Admin enters a site URL that is not an `https` `.atlassian.net` address
- **THEN** the form rejects it and no request is sent

### Requirement: Person matching
The system SHALL match people across tools by work email, SHALL support manual linking by tool account id where the tool hides emails, SHALL list unmatched accounts in Integrations, and SHALL exclude bots and service accounts from activity by default. A live record whose author or assignee is unmatched SHALL be stored without a person and SHALL stay out of per-person figures until the account is linked.

#### Scenario: Hidden email
- **WHEN** a Jira Cloud account exposes no email
- **THEN** it appears under unmatched accounts for manual linking

#### Scenario: Link after sync
- **WHEN** an Admin links an unmatched GitHub login to a person
- **THEN** that person's existing GitHub rows count in their figures from the next read

### Requirement: Tile states
The system SHALL show per tool one of: "Connected · synced N min ago", "Token expires in N days", "Sync failed · code", or "Not connected", with Settings, Fix or Connect actions accordingly.

#### Scenario: Failed sync
- **WHEN** sync fails with 401
- **THEN** the tile shows "Sync failed · 401 Unauthorized" with a Fix action

### Requirement: Jira configuration
The system SHALL let an Admin pick boards, the story points field and the sync mode, and a switch to ignore edits by bots and non-assignees when computing last activity.

#### Scenario: Last activity with bot edits ignored
- **WHEN** the switch is on and only a bot updated an issue
- **THEN** the issue's last activity does not change

### Requirement: Activity and sync logs
The system SHALL show a credential activity log (add, change, disconnect, failed sync with time and person) and a daily sync log with Sync now.

#### Scenario: Manual sync
- **WHEN** an Admin clicks Sync now
- **THEN** a sync runs and a log entry shows its counts and outcome

### Requirement: Access
Only holders of integrations.manage SHALL see the Integrations screen and its credentials table.

#### Scenario: Manager opens Integrations
- **WHEN** a Manager opens the route
- **THEN** the "You don't have access" card is shown
