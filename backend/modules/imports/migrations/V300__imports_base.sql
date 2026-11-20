-- V300 CSV import base (Track 3, task 16.1).
-- Tables for the shared check / commit / undo pipeline of spec platform/csv-import-framework
-- (design.md decision 8). Conventions follow V100 (design.md decision 4): UUID keys, created_at,
-- version on editable rows. DML grants to pulse_app come from V100's default privileges.
--
-- Module tables of Track 3 (attendance V310, visits V320, ...) arrive in their own migrations.

-- ---------------------------------------------------------------- staging (Check step)
-- One row per Check. Nothing here is business data: the row is deleted on commit, cancel or
-- expiry. The id is the check id returned to the client; Commit accepts it only from the same
-- user and import type, before expires_at, and for the same file hash.
CREATE TABLE import_checks (
    id          uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    import_type text        NOT NULL,                   -- registry key, e.g. 'attendance', 'okrs'
    mode        text        NOT NULL,                   -- type-specific, e.g. 'replace_months', 'merge_days'
    file_name   text        NOT NULL,
    file_hash   text        NOT NULL,                   -- SHA-256 hex of the uploaded bytes
    file_bytes  integer     NOT NULL CHECK (file_bytes >= 0 AND file_bytes <= 5242880),
    rows_data   jsonb       NOT NULL DEFAULT '[]'::jsonb,  -- parsed, normalized rows ready to commit
    result      jsonb       NOT NULL DEFAULT '{}'::jsonb,  -- counts, errors, warnings, preview
    created_at  timestamptz NOT NULL DEFAULT now(),
    expires_at  timestamptz NOT NULL DEFAULT now() + interval '30 minutes',
    CHECK (expires_at > created_at)
);
CREATE INDEX import_checks_user_idx ON import_checks (user_id, import_type);
CREATE INDEX import_checks_expires_idx ON import_checks (expires_at);

-- ---------------------------------------------------------------- history (Commit step)
-- One row per committed import, newest first on the Data imports screen.
-- check_id is unique so a repeated Commit finds the first result instead of importing twice;
-- it has no foreign key because the staging row is deleted on commit.
-- Undo is available while undone_at IS NULL and now() < undo_until.
CREATE TABLE import_log (
    id           uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    check_id     uuid        NOT NULL,
    import_type  text        NOT NULL,
    mode         text        NOT NULL,
    file_name    text        NOT NULL,
    file_hash    text        NOT NULL,
    user_id      uuid        REFERENCES users (id) ON DELETE SET NULL,
    committed_at timestamptz NOT NULL DEFAULT now(),
    rows_read    integer     NOT NULL DEFAULT 0 CHECK (rows_read >= 0),
    imported     integer     NOT NULL DEFAULT 0 CHECK (imported >= 0),
    skipped      integer     NOT NULL DEFAULT 0 CHECK (skipped >= 0),
    errors       integer     NOT NULL DEFAULT 0 CHECK (errors >= 0),
    warnings     integer     NOT NULL DEFAULT 0 CHECK (warnings >= 0),
    summary      jsonb       NOT NULL DEFAULT '{}'::jsonb,  -- result shown on the Done step
    undo_until   timestamptz NOT NULL DEFAULT now() + interval '24 hours',
    undone_at    timestamptz,
    undone_by    uuid        REFERENCES users (id) ON DELETE SET NULL,
    version      integer     NOT NULL DEFAULT 1,
    CHECK (undone_by IS NULL OR undone_at IS NOT NULL)   -- undone_by may be nulled by user deletion
);
CREATE UNIQUE INDEX import_log_check_uq ON import_log (check_id);
CREATE INDEX import_log_committed_idx ON import_log (committed_at DESC);
CREATE INDEX import_log_type_idx ON import_log (import_type, committed_at DESC);
CREATE INDEX import_log_user_idx ON import_log (user_id, committed_at DESC);

-- ---------------------------------------------------------------- before-images (Undo)
-- What each committed row looked like before the import, written in the commit transaction.
-- Undo replays them in reverse seq order:
--   insert -> delete the row; update -> restore before_data; delete -> re-insert before_data.
-- after_version is the row version the import left behind; if the live row's version differs,
-- the row changed later and undo is refused for that import, listing the conflicting records.
CREATE TABLE import_before_images (
    id            uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    import_log_id uuid        NOT NULL REFERENCES import_log (id) ON DELETE CASCADE,
    seq           integer     NOT NULL CHECK (seq >= 0),
    target_table  text        NOT NULL,
    target_id     uuid        NOT NULL,
    action        text        NOT NULL CHECK (action IN ('insert', 'update', 'delete')),
    before_data   jsonb,                                -- null for insert
    after_version integer,                              -- null for delete
    created_at    timestamptz NOT NULL DEFAULT now(),
    CHECK ((action = 'insert') = (before_data IS NULL)),
    CHECK ((action = 'delete') = (after_version IS NULL))
);
CREATE UNIQUE INDEX import_before_images_seq_uq ON import_before_images (import_log_id, seq);
CREATE INDEX import_before_images_target_idx ON import_before_images (target_table, target_id);
