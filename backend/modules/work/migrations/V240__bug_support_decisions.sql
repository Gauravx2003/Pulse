-- V240 Bugs & support decisions (Track 2, task 12.1, spec work/bugs-support).
-- bugs and support_tickets mirror Jira and the support desk and are written only by IngestService.
-- Pulse's own decisions live in their own tables, so a sync never overwrites them, and the two
-- *_current views are what every reader uses: the synced row with Pulse's decisions applied.
-- Grants: V100 set default privileges, so pulse_app gets DML on these tables and reads the views.

-- The raw attribution results the ingest brings: the Jira "caused by" link and blame on the fixing PR's
-- diff. origin_person_id stays the resolved code owner (the link wins, BugAttribution in calc/work).
ALTER TABLE bugs
    ADD COLUMN caused_by_person_id uuid REFERENCES people (id),
    ADD COLUMN blame_person_id     uuid REFERENCES people (id);

-- "Take it" (the code owner takes the fix), "Pick up" (someone fixes a bug in others' code: proactive)
-- and "Mark resolved" by the person fixing it.
CREATE TABLE bug_work (
    bug_id      uuid        PRIMARY KEY REFERENCES bugs (id) ON DELETE CASCADE,
    assignee_id uuid        NOT NULL REFERENCES people (id),
    proactive   boolean     NOT NULL,
    status      text        NOT NULL CHECK (status IN ('In Progress', 'Resolved')),
    resolved_on date,
    updated_by  uuid        REFERENCES users (id),
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    version     integer     NOT NULL DEFAULT 1,
    CHECK ((status = 'Resolved') = (resolved_on IS NOT NULL))
);

-- A code owner disputes an attribution; their manager keeps it or removes it from their count.
CREATE TABLE bug_disputes (
    id         uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    bug_id     uuid        NOT NULL REFERENCES bugs (id) ON DELETE CASCADE,
    person_id  uuid        NOT NULL REFERENCES people (id),           -- the code owner disputing
    reason     text        NOT NULL CHECK (char_length(btrim(reason)) >= 10),
    raised_by  uuid        NOT NULL REFERENCES users (id),
    raised_at  timestamptz NOT NULL DEFAULT now(),
    decision   text        CHECK (decision IN ('keep', 'remove')),
    decided_by uuid        REFERENCES users (id),
    decided_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    version    integer     NOT NULL DEFAULT 1,
    CHECK ((decision IS NULL) = (decided_at IS NULL))
);
CREATE UNIQUE INDEX bug_disputes_open_uq ON bug_disputes (bug_id) WHERE decision IS NULL;
CREATE INDEX bug_disputes_person_idx ON bug_disputes (person_id, raised_at);

-- Taking a queue ticket and resolving it (hours and a resolution note are required).
CREATE TABLE ticket_work (
    ticket_id       uuid         PRIMARY KEY REFERENCES support_tickets (id) ON DELETE CASCADE,
    person_id       uuid         NOT NULL REFERENCES people (id),
    how_taken       text         NOT NULL,
    status          text         NOT NULL CHECK (status IN ('Open', 'Resolved')),
    hours           numeric(6,2) CHECK (hours > 0),
    resolution_note text,
    resolved_on     date,
    updated_by      uuid         REFERENCES users (id),
    created_at      timestamptz  NOT NULL DEFAULT now(),
    updated_at      timestamptz  NOT NULL DEFAULT now(),
    version         integer      NOT NULL DEFAULT 1,
    CHECK ((status = 'Resolved') = (resolved_on IS NOT NULL AND hours IS NOT NULL
                                    AND char_length(btrim(coalesce(resolution_note, ''))) > 0))
);

-- A bug as Pulse sees it: a removed dispute takes it out of the owner's count (origin_person_id null,
-- attributed_person_id keeps who it was traced to); Pulse's fix work applies unless the sync already
-- shows it resolved.
CREATE VIEW bugs_current AS
SELECT b.id, b.key, b.title, b.severity,
       CASE WHEN b.status = 'Resolved' OR w.status IS NULL THEN b.status ELSE w.status END AS status,
       b.environment,
       CASE WHEN EXISTS (SELECT 1 FROM bug_disputes d WHERE d.bug_id = b.id AND d.decision = 'remove')
            THEN NULL ELSE b.origin_person_id END AS origin_person_id,
       b.origin_person_id AS attributed_person_id,
       b.origin_ref, b.attribution_method, b.caused_by_person_id, b.blame_person_id, b.found_by,
       coalesce(w.assignee_id, b.assignee_id) AS assignee_id,
       CASE WHEN b.status <> 'Resolved' AND w.status = 'Resolved' THEN w.assignee_id ELSE b.fixed_by_id END AS fixed_by_id,
       coalesce(w.proactive, b.proactive) AS proactive,
       b.reported_on,
       CASE WHEN b.status = 'Resolved' THEN b.resolved_on ELSE coalesce(w.resolved_on, b.resolved_on) END AS resolved_on,
       (SELECT d.id FROM bug_disputes d WHERE d.bug_id = b.id AND d.decision IS NULL) AS open_dispute_id,
       b.source, b.external_id, b.synced_at
FROM bugs b
LEFT JOIN bug_work w ON w.bug_id = b.id;

-- A support ticket as Pulse sees it: taken and resolved in Pulse unless the desk already resolved it.
CREATE VIEW support_tickets_current AS
SELECT t.id, t.key, t.title, t.customer, t.priority,
       coalesce(w.person_id, t.person_id) AS person_id,
       coalesce(w.how_taken, t.how_taken) AS how_taken,
       CASE WHEN t.status = 'Resolved' THEN t.hours ELSE coalesce(w.hours, t.hours) END AS hours,
       CASE WHEN t.status = 'Resolved' OR w.status IS NULL THEN t.status ELSE w.status END AS status,
       t.opened_on,
       CASE WHEN t.status = 'Resolved' THEN t.resolved_on ELSE coalesce(w.resolved_on, t.resolved_on) END AS resolved_on,
       t.csat,
       coalesce(w.resolution_note, t.resolution_note) AS resolution_note,
       t.source, t.external_id, t.synced_at
FROM support_tickets t
LEFT JOIN ticket_work w ON w.ticket_id = t.id;
