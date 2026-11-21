-- V101 sign-in lockout and throttle counters (Track 1, task 3.1).
-- Counters live in PostgreSQL so every HTTP verticle instance sees the same values (design.md
-- decision 1). Keyed by the typed email, not the user id, so unknown emails lock out exactly like
-- real ones and lockout never reveals whether an account exists.

CREATE TABLE login_attempts (
    email           text        PRIMARY KEY,             -- lower-cased, as typed
    failures        integer     NOT NULL DEFAULT 0,
    first_failed_at timestamptz,
    locked_until    timestamptz,
    updated_at      timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE ip_throttle (
    ip           text        PRIMARY KEY,
    window_start timestamptz NOT NULL DEFAULT now(),
    attempts     integer     NOT NULL DEFAULT 0
);
