-- V310 attendance (Track 3, task 17.1; spec time/attendance, reference spec section 7).
-- One record per person per working day, uploaded monthly by HR (attendance.upload) or seeded through
-- IngestService (ingest columns source / external_id / synced_at; CSV uploads write source = 'csv').
-- Statuses: O Office, W WFH, D On duty, L Leave, S Sick or Emergency leave, HD Half day (0.5 leave day),
-- H Holiday, A Absent. Scores are not stored: they come from calc/people (Attendance.stats, task 4.6).
-- Check-in times are visible to the person and their direct manager only (policy domain attendance.checkin).
-- DML grants to pulse_app come from V100's default privileges.

CREATE TABLE attendance_records (
    id          uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    person_id   uuid        NOT NULL REFERENCES people (id),
    day         date        NOT NULL,
    status      text        NOT NULL CHECK (status IN ('O', 'W', 'D', 'L', 'S', 'HD', 'H', 'A')),
    leave_type  text        CHECK (leave_type IN ('Casual', 'Sick', 'Earned', 'Comp off', 'Emergency')),
    applied_on  date,
    check_in    time,
    check_out   time        CHECK (check_out IS NULL OR check_in IS NULL OR check_out > check_in),
    source      text        NOT NULL DEFAULT 'seed',
    external_id text,
    synced_at   timestamptz,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    version     integer     NOT NULL DEFAULT 1,
    CONSTRAINT attendance_records_person_day_uq UNIQUE (person_id, day)
);
CREATE UNIQUE INDEX attendance_records_source_external_uq ON attendance_records (source, external_id);
CREATE INDEX attendance_records_day_idx ON attendance_records (day);

-- A month counts only once it is uploaded; a later upload of the same month replaces this row.
CREATE TABLE attendance_uploads (
    id               uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    month            text        NOT NULL CHECK (month ~ '^[0-9]{4}-(0[1-9]|1[0-2])$'),
    uploaded_by      uuid        REFERENCES users (id),
    uploaded_by_name text        NOT NULL,
    uploaded_at      timestamptz NOT NULL DEFAULT now(),
    file_name        text        NOT NULL CHECK (length(file_name) <= 255),
    row_count        integer     NOT NULL CHECK (row_count >= 0),
    source           text        NOT NULL DEFAULT 'seed',
    external_id      text,
    synced_at        timestamptz,
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    version          integer     NOT NULL DEFAULT 1,
    CONSTRAINT attendance_uploads_month_uq UNIQUE (month)
);
CREATE UNIQUE INDEX attendance_uploads_source_external_uq ON attendance_uploads (source, external_id);
