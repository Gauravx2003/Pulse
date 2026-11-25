-- V190 live connector platform (Track 1, task 24.1, change pulse-v2-live-connectors, design.md decisions 2, 4, 5).
-- Additive only: no Phase 1 table, column or constraint changes. Seeded tools keep using
-- integration_settings (V140); these tables are used only by registered live connectors.

-- Accounts seen in a live tool (Jira username, GitHub login) and the person they belong to.
-- Live rows keep the raw tool account id; their person is resolved at read time through this table,
-- so linking an account applies from the next read with no re-sync. person_id null: unmatched.
CREATE TABLE external_accounts (
    id              uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    person_id       uuid        REFERENCES people (id) ON DELETE SET NULL,
    tool            text        NOT NULL,                        -- tile id: jira, gh
    tool_account_id text        NOT NULL,                        -- Jira Server: username (name); GitHub: login id
    login           text        NOT NULL,                        -- what the Admin sees and links by
    display_name    text,
    email           text,                                        -- null when the tool hides it
    is_bot          boolean     NOT NULL DEFAULT false,          -- excluded from activity figures
    first_seen_at   timestamptz NOT NULL DEFAULT now(),
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT external_accounts_tool_account_uq UNIQUE (tool, tool_account_id)
);
CREATE INDEX external_accounts_person_idx ON external_accounts (person_id) WHERE person_id IS NOT NULL;
CREATE INDEX external_accounts_unmatched_idx ON external_accounts (tool, first_seen_at DESC)
    WHERE person_id IS NULL AND NOT is_bot;

-- Live sync progress per tool. status doubles as the one-sync-per-tool lock (a conditional update
-- to 'running'); the cursor advances only when a whole sync succeeds.
CREATE TABLE sync_state (
    tool            text        PRIMARY KEY,
    cursor          text,                                        -- connector-defined; null: never synced
    last_run_at     timestamptz,
    status          text        NOT NULL DEFAULT 'idle'
                    CHECK (status IN ('idle', 'running', 'succeeded', 'failed')),
    last_error_code text                                         -- e.g. 401, 429, 500, timeout, key; never a secret
);

-- One live connection per tool: non-secret config and the AES-256-GCM encrypted secret (task 24.2).
-- Disconnect clears the secret columns and keeps every ingested row.
CREATE TABLE integration_connections (
    tool              text        PRIMARY KEY,
    config            jsonb       NOT NULL DEFAULT '{}'::jsonb,   -- validated against the connector's configSchema
    secret_ciphertext bytea,                                     -- null: no stored secret
    secret_iv         bytea,                                     -- 96-bit random IV per secret
    secret_last4      text,                                      -- the only part of a secret ever returned
    expires_at        timestamptz,                               -- token expiry, reported or entered
    state             text        NOT NULL DEFAULT 'disconnected'
                      CHECK (state IN ('connected', 'disconnected')),
    rotated_at        timestamptz,                               -- last time the secret was replaced
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    version           integer     NOT NULL DEFAULT 1,
    CONSTRAINT integration_connections_secret_pair_ck
        CHECK ((secret_ciphertext IS NULL) = (secret_iv IS NULL)),
    CONSTRAINT integration_connections_last4_ck
        CHECK (secret_last4 IS NULL OR char_length(secret_last4) <= 4)
);
