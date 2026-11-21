-- V102 invite and password-reset links (Track 1, task 3.3, spec platform/auth-session).
-- Only the SHA-256 of the link token is stored, so a database read does not give working links.
-- A link is single-use (used_at) and expiring (expires_at). Issuing a new link of the same purpose
-- removes the user's earlier unused ones.

CREATE TABLE password_tokens (
    id         uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    purpose    text        NOT NULL CHECK (purpose IN ('invite', 'reset')),
    token_hash bytea       NOT NULL UNIQUE,
    expires_at timestamptz NOT NULL,
    used_at    timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid                                      -- inviter; null for self-service reset
);
CREATE INDEX password_tokens_user_idx ON password_tokens (user_id, purpose) WHERE used_at IS NULL;
CREATE INDEX password_tokens_expires_idx ON password_tokens (expires_at) WHERE used_at IS NULL;
