# Integration credentials

**Settings → Integrations** (Admin only) is where tools are connected and credentials are added, changed, tested and removed.

## Add credentials (Connect)

1. Click **Connect** on an available tool.
2. Choose the **authentication method** when the tool offers more than one (e.g. Jira: *API token* or *OAuth 2.0 (3LO)*; GitHub: *GitHub App* or *Personal access token*).
3. Fill the fields for that method. Each field says where to find the value.
4. **Test connection** — required. It checks required fields, URL and email format, then authenticates and lists the scopes granted. A rejected token shows *401 Unauthorized* with what to check.
5. **Save & connect** is enabled only after a passing test. The first sync runs, the sync log and the credential activity log get an entry.

| Tool | Methods and fields |
|---|---|
| Jira Software | API token: site URL, service-account email, API token · OAuth 2.0: site URL, client ID, client secret |
| Microsoft TFS (Azure DevOps Server) | PAT: collection URL, team project, personal access token (Code, Work items, Build — read) · Windows service account (NTLM): collection URL, project, domain, username, password |
| GitHub | GitHub App: organization, App ID, installation ID, private key · PAT: organization, fine-grained token |
| Confluence | site URL, email, API token |
| Slack | workspace, bot token (xoxb-…), signing secret |
| Google Calendar | Workspace domain, service-account JSON key (domain-wide delegation) |
| HRMS (Keka) | base URL, client ID, client secret, API key |
| PagerDuty | subdomain, read-only API key |
| Azure DevOps / GitLab | organization or GitLab URL (+ group), access token |
| Microsoft Teams | tenant ID, client ID, client secret |
| Zendesk | subdomain, agent email, API token |
| GitHub Actions | repositories and deploy workflow (uses the GitHub App) |

## View and change credentials

- **Settings** on a connected tool opens three tabs: **Credentials** (method, non-secret values, secrets masked as `••••••••` + last 4 characters, saved date and by whom, rotation policy, expiry, last test), **Data & scopes** (what Pulse reads and the scopes requested) and **Sync** (today's sync events, *Sync now*).
- **Change credentials** opens the same form pre-filled with non-secret values. **Leave a secret blank to keep the current one**; paste a new value to replace it. The new values replace the old ones only after a passing **Test connection**; synced history is kept.
- **Test connection** re-tests the stored credentials.
- **Fix** appears on a tool with a problem — a failed sync (e.g. *HRMS 401 Unauthorized*) or a token about to expire (e.g. *Slack token expires in 3 days*) — and opens *Change credentials* with the error shown at the top.
- **Disconnect** asks for confirmation, stops syncing and deletes the stored credentials; synced data stays.

## Credentials overview and audit

- The **Credentials** table lists every connected tool with method, saved date and person, rotation status against the tool's policy (Jira and Confluence 90 days, HRMS 180, GitHub / Google / PagerDuty 365), expiry and last test result, with **View** and **Change**.
- **Credential activity** is an audit trail of every add, change, disconnect and failed sync, with time and person.
- Production: secrets are encrypted at rest (KMS), never returned to the browser after saving, and every change is logged.
