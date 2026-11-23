-- Task 8.3: the record of each nudge sent to the assignee of a stale issue (spec work/work-items
-- "Nudge"). Pulse-owned, not an ingest table. The rate limit reads the sender's recent rows, so the
-- limit holds on every instance; the message itself goes to the outbox.
CREATE TABLE nudges (
    id           uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    issue_id     uuid        NOT NULL REFERENCES issues (id) ON DELETE CASCADE,
    sender_id    uuid        NOT NULL REFERENCES users (id),
    recipient_id uuid        NOT NULL REFERENCES people (id),
    channel      text        NOT NULL CHECK (channel IN ('slack', 'teams')),
    created_at   timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX nudges_issue_sender_idx ON nudges (issue_id, sender_id, created_at DESC);
CREATE INDEX nudges_sender_idx ON nudges (sender_id, created_at DESC);
