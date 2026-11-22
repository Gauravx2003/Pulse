-- V140 integrations screens (Track 1, task 5.4, spec admin/integrations-connectors).
-- Phase 1 is a seeded simulation: credential forms store only masked placeholders, never a secret.
-- Live connections (integration_connections, sync_state, external_accounts) arrive in V190 (group 24).

-- Per-tool settings shown on the Integrations screen.
CREATE TABLE integration_settings (
    tool                  text        PRIMARY KEY,                 -- tile id: jira, gh, tfs, sl, hr ...
    method                text,                                    -- auth method label, null: not connected
    credentials           jsonb       NOT NULL DEFAULT '{}'::jsonb, -- field -> value; secret fields masked
    credentials_saved_at  timestamptz,
    credentials_saved_by  uuid        REFERENCES users (id) ON DELETE SET NULL,
    tested_at             timestamptz,
    test_ok               boolean,
    token_expires_on      date,                                    -- known expiry of the stored token, if any
    sync_mode             text        NOT NULL DEFAULT 'webhooks_hourly'
                          CHECK (sync_mode IN ('webhooks_hourly', 'hourly', 'nightly')),
    config                jsonb       NOT NULL DEFAULT '{}'::jsonb, -- Jira: boards, storyPointsField, ignoreBotEdits
    created_at            timestamptz NOT NULL DEFAULT now(),
    updated_at            timestamptz NOT NULL DEFAULT now(),
    version               integer     NOT NULL DEFAULT 1
);

-- Credential activity log: add, change, disconnect, failed sync, expiry warning.
CREATE TABLE credential_log (
    id       uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    tool     text        NOT NULL,
    action   text        NOT NULL CHECK (action IN ('added', 'changed', 'disconnected', 'sync_failed', 'warning')),
    message  text        NOT NULL,                                 -- never a secret
    actor_id uuid        REFERENCES users (id) ON DELETE SET NULL, -- null: System
    at       timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX credential_log_at_idx ON credential_log (at DESC);
