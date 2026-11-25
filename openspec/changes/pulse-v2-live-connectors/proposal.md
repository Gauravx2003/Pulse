# Proposal

## Why

Phase 1 of Pulse v1 (`pulse-v1-baseline`, task groups 1 to 23) is built and accepted on seed data only. Every tool, Jira and GitHub included, is fed by `SeedConnector`, and credential forms store masked placeholders. The baseline promises live read-only Jira and GitHub connectors for the demo.

The baseline assumed Jira Cloud. The team actually uses a self-hosted **Jira Server / Data Center** instance (`http://172.16.10.6:8080`). We verified that it answers `200 OK` on REST API v2 with Basic auth (`username:password`).

This change delivers the connectors one tool at a time:
- **Jira Server comes first**, on a platform layer that has no Jira-specific code.
- **GitHub follows** as one more connector on that same layer.

The work is small and sequential, so one person owns it; there is no three-track split.

## What Changes

- **Generic live connector platform.** None of it is specific to a tool:
  - a `LiveConnector` plug-in contract (`JsonObject` / `JsonArray` only)
  - AES-256-GCM secret storage under `PULSE_SECRET_KEY`
  - a guarded outbound HTTP client: the exact origin each connector declares, `http` or `https`, LAN hosts allowed only when configured, GET only, no redirects, metadata and loopback addresses blocked, timeouts
  - a Sync now orchestrator: cursor, sync log, cache invalidation, failure states, one sync per tool at a time
  - person matching through `external_accounts`
  - Integrations screens that render each connector's form from its config schema
  - a mock server and a reusable connector contract test
- **Jira Server / Data Center connector (first).**
  - **Form fields:** Base URL (`http://` or `https://`, LAN allowed), Username, Password / Token (Basic auth, or a Bearer personal access token), Project Keys, an optional Expiry Date.
  - **REST API v2:**
    - `GET /rest/api/2/myself` for Test connection
    - `GET /rest/api/2/field` to discover the story points and sprint fields
    - `GET /rest/api/2/search` with `project in (...)` JQL, `startAt`/`maxResults` paging and `expand=changelog`
  - Descriptions and comments are read as plain text / wiki markup.
  - People are matched by `emailAddress`, falling back to the Jira username (`name`) for manual linking.
  - Issues and sprints are written through `IngestService` with `source = 'jira'`.
  - Work item screens get a source badge, a deep link and a source filter.
- **GitHub connector (after Jira is accepted).**
  - Fine-grained PAT on one owner/repo.
  - Pull requests and commit days with `source = 'github'`.
  - A source badge and deep link on the PR screens.
  - It reuses every platform piece and adds no platform code.
- **Acceptance.** Re-run the Phase 1 acceptance with seed and live rows together, after Jira and again after GitHub, plus a security pass and a manual live smoke test.
- **Baseline pointer.** Baseline `tasks.md` group 24 is replaced by a pointer to this change.

## Out of scope

- Jira Cloud (REST v3, `.atlassian.net`, `accountId`, ADF).
- Webhooks, scheduled or background live polling, OAuth flows.
- Live connectors for TFS, Slack, HRMS, PagerDuty and the other catalogued tools. They stay seeded and simulated.
- Any write call to Jira or GitHub.
- Secret key rotation and KMS, which remain in "Deferred hardening".
- Bulk account linking by CSV, live evidence in appraisal or 1:1s, and source labels in the performance breakdown. These are possible follow-ups; accounts are linked in the Integrations panel.
- Any change to Phase 1 formulas, thresholds, endpoints or response shapes. New fields are additive only.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `admin/integrations-connectors`:
  - live connector plug-in contract and generic screens
  - Jira Server live connector, its authentication and field discovery; this replaces the Jira Cloud connector
  - safe outbound requests for a configured LAN origin
  - Jira configuration by project keys
  - person matching by `emailAddress` / username
  - one sync per tool
  - partial sync keeps written pages
  - unmatched accounts panel
  - disconnect keeps data
  - GitHub token types
- `platform/secrets-credentials`:
  - the Jira password or token and the GitHub PAT, encrypted
  - secrets decrypted only inside a test or sync
  - save bound to a passing test
  - unreadable secrets
- `platform/privacy-retention`: external tool accounts and live rows on user deletion.
- `work/work-items`: source badge, deep link and source filter; unmatched assignees (Jira).
- `work/pull-requests`: source badge and deep link; unmatched authors (GitHub).

## Impact

- **Backend:**
  - Generic platform code under `backend/src/` (secrets, guarded HTTP, live registry, sync orchestrator, external accounts).
  - Connectors in a new self-registering module, `backend/modules/connectors/`, with `jira/` first and `github/` later.
  - Additive migrations: V190–V199 for platform tables, V290 (Jira) and V291 (GitHub) for work-table columns and views.
- **Frontend:**
  - The Integrations module, with a schema-driven form.
  - A work item source badge, and later a PR source badge.
- **External systems:**
  - Read-only GET requests to the configured Jira Server origin (internal LAN, currently `http`).
  - Later, to `https://api.github.com`.
  - Requests are sent only when an Admin runs Test connection or Sync now.
- **Security:** Basic auth over plain `http` exposes the password on the LAN. The form warns about it, and the runbook recommends `https` or a limited Bearer PAT with a read-only Jira user.
- **Configuration:** `PULSE_SECRET_KEY` is required to save a live credential.
- **Docs:** `docs/live-connectors.md` (new; includes "how to add a connector"), `docs/demo.md`.
