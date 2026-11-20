-- V100 base schema (Track 1, task 2.1).
-- Conventions (design.md decision 4): UUID keys, created_at/updated_at/version on editable rows,
-- effective-dated reporting lines and rule versions. Tables that mirror an external tool carry the
-- ingest columns source/external_id/synced_at with a unique index on (source, external_id)
-- (design.md decision 6); seed rows use the prototype key as external_id.
--
-- Runs as the migration (owner) account. The application runs as role pulse_app, which gets DML
-- only: it cannot run DDL and cannot UPDATE or DELETE audit_log (decision 7).

-- ---------------------------------------------------------------- application role
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'pulse_app') THEN
        CREATE ROLE pulse_app NOLOGIN;
    END IF;
    IF NOT pg_has_role(current_user, 'pulse_app', 'MEMBER') THEN
        EXECUTE format('GRANT pulse_app TO %I', current_user);
    END IF;
END $$;

-- ---------------------------------------------------------------- roles and users
CREATE TABLE roles (
    id          uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    name        text        NOT NULL,
    description text        NOT NULL DEFAULT '',
    built_in    boolean     NOT NULL DEFAULT false,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    version     integer     NOT NULL DEFAULT 1
);
CREATE UNIQUE INDEX roles_name_uq ON roles (lower(name));

CREATE TABLE role_permissions (
    role_id    uuid NOT NULL REFERENCES roles (id) ON DELETE CASCADE,
    permission text NOT NULL,
    PRIMARY KEY (role_id, permission)
);

CREATE TABLE users (
    id            uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    email         text        NOT NULL,
    name          text        NOT NULL,
    role_id       uuid        REFERENCES roles (id),      -- null: role deleted -> "Your role no longer exists"
    title         text,
    department    text,
    status        text        NOT NULL DEFAULT 'Invited'
                  CHECK (status IN ('Active', 'Invited', 'Deactivated')),
    password_hash text,                                   -- bcrypt; null until the invite is accepted
    last_login_at timestamptz,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    version       integer     NOT NULL DEFAULT 1
);
CREATE UNIQUE INDEX users_email_uq ON users (lower(email));
CREATE INDEX users_role_idx ON users (role_id);

-- Performance profile, created for Employees.
CREATE TABLE people (
    id          uuid          PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     uuid          UNIQUE REFERENCES users (id),
    name        text          NOT NULL,
    title       text,                                   -- role title, e.g. "Software Engineer II"
    department  text,                                   -- team, e.g. "Platform"
    level       text          CHECK (level IN ('L1', 'L2', 'L3', 'L4', 'L5')),
    joined_on   date,
    ctc         numeric(12,2) CHECK (ctc >= 0),         -- annual CTC in lakh INR; appraisal service only
    source      text          NOT NULL DEFAULT 'seed',
    external_id text,
    synced_at   timestamptz,
    created_at  timestamptz   NOT NULL DEFAULT now(),
    updated_at  timestamptz   NOT NULL DEFAULT now(),
    version     integer       NOT NULL DEFAULT 1
);
CREATE UNIQUE INDEX people_source_external_uq ON people (source, external_id);

-- Effective-dated manager history; the open line (valid_to IS NULL) is the current manager.
CREATE TABLE reporting_lines (
    id         uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    uuid        NOT NULL REFERENCES users (id),
    manager_id uuid        NOT NULL REFERENCES users (id),
    valid_from date        NOT NULL,
    valid_to   date,
    created_at timestamptz NOT NULL DEFAULT now(),
    CHECK (valid_to IS NULL OR valid_to > valid_from),
    CHECK (manager_id <> user_id)
);
CREATE UNIQUE INDEX reporting_lines_current_uq ON reporting_lines (user_id) WHERE valid_to IS NULL;
CREATE INDEX reporting_lines_manager_idx ON reporting_lines (manager_id) WHERE valid_to IS NULL;

-- ---------------------------------------------------------------- sessions and audit
CREATE TABLE sessions (
    token_hash   bytea       PRIMARY KEY,                -- SHA-256 of the opaque cookie value
    user_id      uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    csrf_token   text        NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now(),
    expires_at   timestamptz NOT NULL,
    last_seen_at timestamptz NOT NULL DEFAULT now(),
    source_ip    text
);
CREATE INDEX sessions_user_idx ON sessions (user_id);
CREATE INDEX sessions_expires_idx ON sessions (expires_at);

-- Insert-only. No foreign keys: actor and target survive user deletion as opaque ids.
CREATE TABLE audit_log (
    id          uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    occurred_at timestamptz NOT NULL DEFAULT now(),
    actor_id    uuid,
    actor_role  text,
    action      text        NOT NULL,
    target_type text,
    target_id   text,
    outcome     text        NOT NULL CHECK (outcome IN ('success', 'refused')),
    source_ip   text,
    request_id  text,
    summary     jsonb       NOT NULL DEFAULT '{}'::jsonb
);
CREATE INDEX audit_log_occurred_idx ON audit_log (occurred_at);
CREATE INDEX audit_log_actor_idx ON audit_log (actor_id, occurred_at);
CREATE INDEX audit_log_target_idx ON audit_log (target_type, target_id);

-- ---------------------------------------------------------------- rules
-- Current value per rule key; every save also writes a full snapshot to rule_versions.
CREATE TABLE rule_versions (
    version    integer     PRIMARY KEY,
    rule_set   jsonb       NOT NULL,                     -- every rule key -> value at this version
    diff       jsonb       NOT NULL DEFAULT '{}'::jsonb, -- key -> {before, after}
    created_at timestamptz NOT NULL DEFAULT now(),       -- effective from
    created_by uuid,
    reason     text
);

CREATE TABLE rules (
    key        text        PRIMARY KEY,                  -- e.g. "index.weights.delivery"
    rule_group text        NOT NULL,                     -- card on Rules & thresholds
    value      jsonb       NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    updated_by uuid,
    version    integer     NOT NULL DEFAULT 1
);
CREATE INDEX rules_group_idx ON rules (rule_group);

-- ---------------------------------------------------------------- calendar
CREATE TABLE holidays (
    id          uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    holiday_on  date        NOT NULL,
    name        text        NOT NULL,
    location    text,                                    -- null: every site
    source      text        NOT NULL DEFAULT 'seed',
    external_id text,
    synced_at   timestamptz,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    version     integer     NOT NULL DEFAULT 1
);
CREATE UNIQUE INDEX holidays_source_external_uq ON holidays (source, external_id);
CREATE UNIQUE INDEX holidays_date_location_uq ON holidays (holiday_on, coalesce(location, ''));

CREATE TABLE sprints (
    id          uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    key         text        NOT NULL,                    -- "S43"
    number      integer     NOT NULL,
    starts_on   date        NOT NULL,                    -- Monday of the first week
    work_ends_on date       NOT NULL,                    -- last working day ("end")
    ends_on     date        NOT NULL,                    -- Sunday before the next sprint ("to")
    source      text        NOT NULL DEFAULT 'seed',
    external_id text,
    synced_at   timestamptz,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    version     integer     NOT NULL DEFAULT 1,
    CHECK (work_ends_on >= starts_on AND ends_on >= work_ends_on)
);
CREATE UNIQUE INDEX sprints_source_external_uq ON sprints (source, external_id);
CREATE INDEX sprints_range_idx ON sprints (starts_on, ends_on);

CREATE TABLE releases (
    id           uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    key          text        NOT NULL,                   -- "8.2"
    name         text        NOT NULL,                   -- "NMS 8.2"
    starts_on    date        NOT NULL,
    ends_on      date        NOT NULL,
    release_date date,
    status       text        NOT NULL CHECK (status IN ('In progress', 'Released')),
    source       text        NOT NULL DEFAULT 'seed',
    external_id  text,
    synced_at    timestamptz,
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),
    version      integer     NOT NULL DEFAULT 1,
    CHECK (ends_on >= starts_on)
);
CREATE UNIQUE INDEX releases_source_external_uq ON releases (source, external_id);

-- ---------------------------------------------------------------- notifications, outbox, sync log
CREATE TABLE notifications (
    id         uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    kind       text        NOT NULL,
    text       text        NOT NULL,                     -- never salary, 360° comments or reviewer identity
    link       text,
    read_at    timestamptz,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX notifications_user_unread_idx ON notifications (user_id, created_at DESC) WHERE read_at IS NULL;
CREATE INDEX notifications_user_idx ON notifications (user_id, created_at DESC);

-- Outbound messages (invite and reset links, Slack copies); delivered by a job when configured.
CREATE TABLE outbox (
    id         uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    channel    text        NOT NULL CHECK (channel IN ('email', 'slack', 'teams')),
    recipient  text        NOT NULL,
    subject    text,
    body       text        NOT NULL,
    payload    jsonb       NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    sent_at    timestamptz,
    attempts   integer     NOT NULL DEFAULT 0,
    last_error text
);
CREATE INDEX outbox_pending_idx ON outbox (created_at) WHERE sent_at IS NULL;

CREATE TABLE sync_log (
    id           uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    tool         text        NOT NULL,
    started_at   timestamptz NOT NULL DEFAULT now(),
    finished_at  timestamptz,
    outcome      text        NOT NULL CHECK (outcome IN ('running', 'success', 'failed')),
    counts       jsonb       NOT NULL DEFAULT '{}'::jsonb,
    message      text,                                   -- never secrets or Authorization headers
    triggered_by uuid,
    simulated    boolean     NOT NULL DEFAULT true
);
CREATE INDEX sync_log_tool_idx ON sync_log (tool, started_at DESC);

-- ---------------------------------------------------------------- culture signals
-- The 12 culture data parameters with no live source in v1 (design.md decision 6).
CREATE TABLE culture_signals (
    id            uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    person_id     uuid        NOT NULL REFERENCES people (id) ON DELETE CASCADE,
    quarter       text        NOT NULL,                  -- "Q3"
    param_key     text        NOT NULL,
    raw_value     numeric,
    display_value text        NOT NULL,
    source_label  text        NOT NULL,
    is_simulated  boolean     NOT NULL DEFAULT false,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    version       integer     NOT NULL DEFAULT 1,
    UNIQUE (person_id, quarter, param_key)
);

-- ---------------------------------------------------------------- privileges
DO $$
DECLARE
    s text := current_schema();
BEGIN
    EXECUTE format('GRANT USAGE ON SCHEMA %I TO pulse_app', s);
    EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA %I TO pulse_app', s);
    EXECUTE format('GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA %I TO pulse_app', s);
    -- Tables created later by Tracks 2 and 3 (same migration account) get the same DML grants.
    EXECUTE format('ALTER DEFAULT PRIVILEGES IN SCHEMA %I GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO pulse_app', s);
    EXECUTE format('ALTER DEFAULT PRIVILEGES IN SCHEMA %I GRANT USAGE, SELECT ON SEQUENCES TO pulse_app', s);
END $$;

REVOKE UPDATE, DELETE, TRUNCATE ON audit_log FROM pulse_app;
REVOKE INSERT, UPDATE, DELETE, TRUNCATE ON pulse_schema_history FROM pulse_app;
