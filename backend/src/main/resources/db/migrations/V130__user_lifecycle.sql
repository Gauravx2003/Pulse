-- V130 user lifecycle (Track 1, task 5.1): spec admin/users-roles and platform/privacy-retention.
--
--   users.level       the level chosen on Add / Edit user for every account; people.level keeps the
--                     performance profile's copy for Employees.
--   users.deleted_at  set when a user is deleted. The row stays (other tables and the audit log keep
--                     its id as an opaque pseudonymous id) but its name, email and password are erased.
--   user_deletions    the deletion record (spec "Deleted data not restored silently"): pseudonymous ids
--                     and the date, so a restore from backup can re-apply the deletion. It never holds a
--                     name, email or free text.

ALTER TABLE users ADD COLUMN level text CHECK (level IN ('L1', 'L2', 'L3', 'L4', 'L5'));
ALTER TABLE users ADD COLUMN deleted_at timestamptz;

UPDATE users u SET level = p.level FROM people p WHERE p.user_id = u.id;

CREATE INDEX users_live_idx ON users (lower(name)) WHERE deleted_at IS NULL;

CREATE TABLE user_deletions (
    id         uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    uuid        NOT NULL UNIQUE,              -- pseudonymous: resolves to no name or email
    person_id  uuid,
    deleted_on date        NOT NULL,
    deleted_by uuid,
    created_at timestamptz NOT NULL DEFAULT now()
);

-- The deletion record is history: the application may add to it, never change it.
REVOKE UPDATE, DELETE, TRUNCATE ON user_deletions FROM pulse_app;
