# Proposal

## Why

Phase 1 of Pulse v1 (`pulse-v1-baseline`, task groups 1 to 23) is built and accepted on seed data only. Every tool, GitHub and Jira included, is fed by `SeedConnector`, and credential forms store masked placeholders. The baseline promises live read-only GitHub and Jira Cloud connectors for the demo.

This change delivers them one tool at a time. **GitHub comes first**, on a platform layer that has no GitHub-specific code. **Jira follows** as one more connector on that same layer. The work is small and sequential, so one person owns it; there is no three-track split.

## What Changes

- **Generic live connector platform.** None of it is specific to a tool:
  - a `LiveConnector` plug-in contract
  - AES-256-GCM secret storage under `PULSE_SECRET_KEY`
  - a guarded outbound HTTP client: hosts declared by each connector, GET only, no redirects, timeouts
  - a Sync now orchestrator: cursor, sync log, cache invalidation, failure states, one sync per tool at a time
  - person matching through `external_accounts`
  - Integrations screens that render each connector's settings form from its config schema
  - a mock server and a reusable connector contract test
- **GitHub live connector (first).**
  - Personal access token (fine-grained recommended) on one owner/repo.
  - Pull requests and commit days written through `IngestService` with `source = 'github'`.
  - Real Test connection and Sync now.
  - Source badge and deep link on the PR screens.
- **Jira Cloud live connector (after GitHub is accepted).**
  - Site URL, email and a classic API token, scoped by project keys.
  - Issues and sprints with `source = 'jira'`.
  - Source badge, deep link and source filter on the work item screens.
  - It reuses every platform piece and adds no platform code.
- **Acceptance.** Re-run the Phase 1 acceptance with seed and live rows together, after GitHub and again after Jira, plus a security pass and a manual live smoke test.
- **Baseline pointer.** Baseline `tasks.md` group 24 is replaced by a pointer to this change.

## Out of scope

- Webhooks, scheduled or background live polling, OAuth flows.
- Live connectors for TFS, Slack, HRMS, PagerDuty and the other catalogued tools. The platform makes them possible later, but they stay seeded and simulated here.
- Any write call to GitHub or Jira.
- Secret key rotation and KMS, which remain in "Deferred hardening".
- Bulk account linking by CSV, live evidence in appraisal or 1:1s, and source labels in the performance breakdown. These are possible follow-ups; accounts are linked in the Integrations panel.
- Any change to Phase 1 formulas, thresholds, endpoints or response shapes. New fields are additive only.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `admin/integrations-connectors`:
  - live connector plug-in contract
  - one sync per tool
  - partial sync keeps written pages
  - unmatched accounts panel
  - disconnect keeps data
  - GitHub and Jira token types
  - Jira project-key scope
- `platform/secrets-credentials`: secrets decrypted only inside a sync; save bound to a passing test; unreadable secrets.
- `platform/privacy-retention`: external tool accounts and live rows on user deletion.
- `work/pull-requests`: source badge and deep link; unmatched authors (GitHub).
- `work/work-items`: source badge, deep link and source filter; unmatched assignees (Jira).

## Impact

- **Backend:**
  - Generic platform code under `backend/src/` (secrets, guarded HTTP, live registry, sync orchestrator, external accounts).
  - Connectors in a new self-registering module, `backend/modules/connectors/`, with one package per tool (`github/`, later `jira/`).
  - Additive migrations: V190–V199 for platform tables, V290–V299 for columns on the work tables.
- **Frontend:**
  - The Integrations module, with a schema-driven settings form.
  - A PR source badge, and later a work item source badge.
- **External systems:** read-only GET requests to `https://api.github.com`, and later to `https://*.atlassian.net`. They are sent only when an Admin runs Test connection or Sync now.
- **Configuration:** `PULSE_SECRET_KEY` is required to save a live credential.
- **Docs:** `docs/live-connectors.md` (new; includes "how to add a connector"), `docs/demo.md`.
