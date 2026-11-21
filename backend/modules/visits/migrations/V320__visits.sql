-- V320 customer visits (Track 3, task 18.1; spec time/customer-visits, reference spec section 8.1).
-- Lifecycle: Planned -> Report due -> Feedback pending -> Awaiting manager -> Verified / Not counted.
-- The status is derived (report, feedback, verification and today), never stored.
-- Points are not stored either: they come from calc/people through CalcService (tasks 4.10/4.11).
-- DML grants to pulse_app come from V100's default privileges.

CREATE TABLE visits (
    id                   uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    person_id            uuid         NOT NULL REFERENCES people (id),       -- the engineer on site
    customer             text         NOT NULL CHECK (length(customer) BETWEEN 1 AND 120),
    city                 text         NOT NULL CHECK (length(city) BETWEEN 1 AND 80),
    purpose              text         NOT NULL CHECK (purpose IN ('Deployment / installation', 'Troubleshooting',
                                      'Escalation', 'Training / workshop', 'Demo / POC', 'Health check')),
    from_on              date         NOT NULL,
    to_on                date         NOT NULL,
    requested_by         text         NOT NULL CHECK (length(requested_by) BETWEEN 1 AND 120),
    owner_id             uuid         NOT NULL REFERENCES users (id),        -- account owner: records feedback
    reference            text         CHECK (length(reference) <= 60),       -- CRM deal or support ticket
    notes                text         NOT NULL DEFAULT '' CHECK (length(notes) <= 1000),
    created_by           uuid         REFERENCES users (id),

    -- Visit report (the engineer, after the visit)
    report_done          text         CHECK (length(report_done) <= 2000),
    report_onsite_hours  numeric(5,1) CHECK (report_onsite_hours >= 0),
    report_travel_hours  numeric(5,1) CHECK (report_travel_hours >= 0),
    report_outcome       text         CHECK (report_outcome IN ('Resolved', 'Partially resolved', 'Follow-up needed',
                                      'Deal progressed')),
    report_follow_ups    text         CHECK (length(report_follow_ups) <= 1000),
    report_contact       text         CHECK (length(report_contact) <= 200),
    reported_on          date,

    -- Customer feedback (the account owner)
    feedback_csat        numeric(2,1) CHECK (feedback_csat BETWEEN 1 AND 5),
    feedback_again       boolean,
    feedback_source      text         CHECK (feedback_source IN ('Customer call', 'Email', 'CSAT survey', 'Meeting')),
    feedback_on          date,
    feedback_quote       text         CHECK (length(feedback_quote) <= 1000),
    feedback_by          uuid         REFERENCES users (id),

    -- Verification of the points (effort.confirm). 'pending' from the moment the report is filed.
    -- Track 2's extra-mile items (task 11) will mirror this once that module exists.
    verification         text         CHECK (verification IN ('pending', 'confirmed', 'rejected')),
    verified_by          uuid         REFERENCES users (id),
    verified_at          timestamptz,
    verify_note          text         CHECK (length(verify_note) <= 500),

    -- Ingest columns: CSV imports of planned visits write source = 'csv' (task 16.3).
    source               text         NOT NULL DEFAULT 'pulse',
    external_id          text,

    created_at           timestamptz  NOT NULL DEFAULT now(),
    updated_at           timestamptz  NOT NULL DEFAULT now(),
    version              integer      NOT NULL DEFAULT 1,

    CHECK (to_on >= from_on),
    CHECK (to_on - from_on <= 13),                                            -- at most 14 days
    CHECK ((reported_on IS NULL) = (report_done IS NULL)),
    CHECK (reported_on IS NOT NULL OR feedback_csat IS NULL),                 -- feedback needs a report
    CHECK ((feedback_on IS NULL) = (feedback_csat IS NULL)),
    CHECK ((reported_on IS NULL) = (verification IS NULL))
);
CREATE UNIQUE INDEX visits_source_external_uq ON visits (source, external_id);
CREATE INDEX visits_person_idx ON visits (person_id, from_on);
CREATE INDEX visits_owner_idx ON visits (owner_id, from_on);
CREATE INDEX visits_pending_idx ON visits (from_on) WHERE verification = 'pending';
