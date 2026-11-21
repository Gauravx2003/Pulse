-- V200 Track 2 data layer (task 7.1): work items, worklogs, commits, pull requests, bugs, support,
-- extra-mile items and the weekly pulse.
--
-- Conventions follow V100 (design.md decision 4): UUID keys, created_at/updated_at/version on
-- editable rows. Tables that mirror an external tool (Jira, GitHub, TFS, support desk) carry the
-- ingest columns source/external_id/synced_at with a unique index on (source, external_id)
-- (design.md decision 6); seed rows use the prototype key as external_id and are written only
-- through IngestService (task 5.4). extra_items and weekly_pulse are Pulse-owned and have none.
--
-- Person columns reference people(id). On ingest tables they are nullable: a live record whose
-- author or assignee is not yet linked to a person is stored with a null person and stays out of
-- per-person figures until linked (design.md decision 6, person matching).
--
-- Every table has an index whose leading column is its policy scope column (the person the
-- row belongs to, or the parent row for child tables), so policy-scoped repositories (task 7.3)
-- never scan. Formulas (stale, utilization, PR standard, points) are not stored here; they live
-- in calc/work and run through CalcService (design.md decision 5).
--
-- Grants: V100 set default privileges, so pulse_app gets DML on these tables automatically.

-- ---------------------------------------------------------------- Jira work items
CREATE TABLE issues (
    id               uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    key              text         NOT NULL,                  -- "PLAT-2231"
    summary          text         NOT NULL,
    issue_type       text         NOT NULL DEFAULT 'Story',  -- Story, Task, Bug, ... (as in the tool)
    status           text         NOT NULL
                     CHECK (status IN ('To Do', 'In Progress', 'In Review', 'Done')),
    assignee_id      uuid         REFERENCES people (id),
    sprint_id        uuid         REFERENCES sprints (id),
    release_id       uuid         REFERENCES releases (id),
    story_points     numeric(5,1) CHECK (story_points >= 0),
    estimate_hours   numeric(7,2) CHECK (estimate_hours >= 0),   -- original estimate
    spent_hours      numeric(7,2) NOT NULL DEFAULT 0 CHECK (spent_hours >= 0),
    remaining_hours  numeric(7,2) CHECK (remaining_hours >= 0),
    due_on           date,
    started_on       date,                                   -- first move to In Progress
    resolved_on      date,
    carried_over     boolean      NOT NULL DEFAULT false,    -- not finished in its sprint, moved on
    reopened         boolean      NOT NULL DEFAULT false,
    was_stale        boolean      NOT NULL DEFAULT false,    -- history flag for closed periods
    source           text         NOT NULL DEFAULT 'seed',
    external_id      text,
    synced_at        timestamptz,
    created_at       timestamptz  NOT NULL DEFAULT now(),
    updated_at       timestamptz  NOT NULL DEFAULT now(),
    version          integer      NOT NULL DEFAULT 1,
    CHECK (resolved_on IS NULL OR started_on IS NULL OR resolved_on >= started_on)
);
CREATE UNIQUE INDEX issues_source_external_uq ON issues (source, external_id);
CREATE INDEX issues_assignee_idx ON issues (assignee_id, sprint_id);
CREATE INDEX issues_sprint_idx ON issues (sprint_id);
CREATE INDEX issues_release_idx ON issues (release_id);
CREATE INDEX issues_key_idx ON issues (key);

-- Activity timeline (Jira changelog, comments, worklogs and linked GitHub events). "Last activity"
-- is the latest event by the assignee that is not a bot or planning event; Jira's updated field
-- is never used alone (spec work/work-items).
CREATE TABLE issue_events (
    id           uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    issue_id     uuid        NOT NULL REFERENCES issues (id) ON DELETE CASCADE,
    occurred_at  timestamptz NOT NULL,
    kind         text        NOT NULL
                 CHECK (kind IN ('created', 'transition', 'worklog', 'comment', 'commit', 'pull_request',
                                 'field_change', 'sprint_change', 'assignee_change')),
    field        text,                                       -- changed field for *_change and transition
    from_value   text,
    to_value     text,
    actor_id     uuid        REFERENCES people (id),         -- null for bots and unlinked accounts
    actor_name   text        NOT NULL,                       -- "Priya Shah", "Jira Automation"
    is_bot       boolean     NOT NULL DEFAULT false,
    summary      text        NOT NULL,                       -- "Status → In Progress", "Worklog 2h"
    source       text        NOT NULL DEFAULT 'seed',
    external_id  text,
    synced_at    timestamptz,
    created_at   timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX issue_events_source_external_uq ON issue_events (source, external_id);
CREATE INDEX issue_events_issue_idx ON issue_events (issue_id, occurred_at DESC);
CREATE INDEX issue_events_actor_idx ON issue_events (actor_id, occurred_at);

-- Hours logged per person per day; issue_id is null for time not booked on a specific issue.
CREATE TABLE worklogs (
    id          uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    person_id   uuid         REFERENCES people (id),
    issue_id    uuid         REFERENCES issues (id) ON DELETE SET NULL,
    work_date   date         NOT NULL,
    hours       numeric(5,2) NOT NULL CHECK (hours >= 0 AND hours <= 24),
    logged_at   timestamptz,                                 -- worklog timestamp, for verification
    source      text         NOT NULL DEFAULT 'seed',
    external_id text,
    synced_at   timestamptz,
    created_at  timestamptz  NOT NULL DEFAULT now(),
    updated_at  timestamptz  NOT NULL DEFAULT now(),
    version     integer      NOT NULL DEFAULT 1
);
CREATE UNIQUE INDEX worklogs_source_external_uq ON worklogs (source, external_id);
CREATE INDEX worklogs_person_idx ON worklogs (person_id, work_date);
CREATE INDEX worklogs_issue_idx ON worklogs (issue_id);

-- Git activity aggregated to one row per person per day (design.md decision 6); feeds last
-- activity, code-only days and "helped on another's issue".
CREATE TABLE commit_days (
    id              uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    person_id       uuid        REFERENCES people (id),
    commit_date     date        NOT NULL,
    commit_count    integer     NOT NULL DEFAULT 1 CHECK (commit_count >= 0),
    issue_keys      text[]      NOT NULL DEFAULT '{}',       -- issue keys found in branch, title or message
    first_commit_at timestamptz,
    last_commit_at  timestamptz,
    source          text        NOT NULL DEFAULT 'seed',
    external_id     text,                                    -- e.g. "<person>|<date>"
    synced_at       timestamptz,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    version         integer     NOT NULL DEFAULT 1,
    CHECK (last_commit_at IS NULL OR first_commit_at IS NULL OR last_commit_at >= first_commit_at)
);
CREATE UNIQUE INDEX commit_days_source_external_uq ON commit_days (source, external_id);
CREATE INDEX commit_days_person_idx ON commit_days (person_id, commit_date);

-- ---------------------------------------------------------------- pull requests (TFS and GitHub)
CREATE TABLE pull_requests (
    id                 uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    number             text         NOT NULL,                -- "!1401" (TFS) or "#761" (GitHub)
    tool               text         NOT NULL CHECK (tool IN ('TFS', 'GitHub')),
    repo               text         NOT NULL,
    title              text         NOT NULL,
    suggested_title    text,                                 -- seeded rewrite; live rows compute it
    author_id          uuid         REFERENCES people (id),
    work_item_key      text,                                 -- linked Jira / TFS work item
    status             text         NOT NULL CHECK (status IN ('Active', 'Completed', 'Abandoned')),
    opened_at          timestamptz  NOT NULL,
    merged_at          timestamptz,
    lines_changed      integer      NOT NULL CHECK (lines_changed >= 0),
    files_changed      integer      CHECK (files_changed >= 0),
    has_summary        boolean      NOT NULL DEFAULT false,  -- description template parts
    has_testing        boolean      NOT NULL DEFAULT false,
    has_risk           boolean      NOT NULL DEFAULT false,
    build_passed       boolean,                              -- null: no build result yet
    first_review_hours numeric(6,1) CHECK (first_review_hours >= 0),
    url                text,
    source             text         NOT NULL DEFAULT 'seed',
    external_id        text,
    synced_at          timestamptz,
    created_at         timestamptz  NOT NULL DEFAULT now(),
    updated_at         timestamptz  NOT NULL DEFAULT now(),
    version            integer      NOT NULL DEFAULT 1,
    CHECK (merged_at IS NULL OR merged_at >= opened_at)
);
CREATE UNIQUE INDEX pull_requests_source_external_uq ON pull_requests (source, external_id);
CREATE INDEX pull_requests_author_idx ON pull_requests (author_id, opened_at);
CREATE INDEX pull_requests_opened_idx ON pull_requests (opened_at);
CREATE INDEX pull_requests_work_item_idx ON pull_requests (work_item_key);

CREATE TABLE pr_reviews (
    id              uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    pull_request_id uuid        NOT NULL REFERENCES pull_requests (id) ON DELETE CASCADE,
    reviewer_id     uuid        REFERENCES people (id),
    vote            text        NOT NULL
                    CHECK (vote IN ('Approved', 'Approved with suggestions', 'Waiting for author',
                                    'Rejected', 'No vote')),
    reviewed_at     timestamptz,
    source          text        NOT NULL DEFAULT 'seed',
    external_id     text,
    synced_at       timestamptz,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    version         integer     NOT NULL DEFAULT 1
);
CREATE UNIQUE INDEX pr_reviews_source_external_uq ON pr_reviews (source, external_id);
CREATE INDEX pr_reviews_reviewer_idx ON pr_reviews (reviewer_id, pull_request_id);
CREATE INDEX pr_reviews_pull_request_idx ON pr_reviews (pull_request_id);

-- Review comments; the comment class (Standard, No label, Needs a reason, Too short) is computed.
CREATE TABLE pr_comments (
    id              uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    pull_request_id uuid        NOT NULL REFERENCES pull_requests (id) ON DELETE CASCADE,
    review_id       uuid        REFERENCES pr_reviews (id) ON DELETE SET NULL,
    author_id       uuid        REFERENCES people (id),
    body            text        NOT NULL,
    is_resolved     boolean     NOT NULL DEFAULT false,
    commented_at    timestamptz NOT NULL,
    source          text        NOT NULL DEFAULT 'seed',
    external_id     text,
    synced_at       timestamptz,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    version         integer     NOT NULL DEFAULT 1
);
CREATE UNIQUE INDEX pr_comments_source_external_uq ON pr_comments (source, external_id);
CREATE INDEX pr_comments_author_idx ON pr_comments (author_id, commented_at);
CREATE INDEX pr_comments_pull_request_idx ON pr_comments (pull_request_id);

-- ---------------------------------------------------------------- bugs and support
-- Attribution (caused-by link or blame) comes from seeded data in v1. Pulse-owned decisions
-- (disputes, take it, pick up) are added by task 12.1 in their own table, so a sync never
-- overwrites them.
CREATE TABLE bugs (
    id                 uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    key                text        NOT NULL,                 -- "BUG-4101"
    title              text        NOT NULL,
    severity           text        NOT NULL CHECK (severity IN ('Critical', 'High', 'Medium', 'Low')),
    status             text        NOT NULL CHECK (status IN ('Open', 'In Progress', 'Resolved')),
    environment        text        NOT NULL CHECK (environment IN ('Staging', 'Production')),
    origin_person_id   uuid        REFERENCES people (id),   -- code owner the bug is attributed to
    origin_ref         text,                                 -- "PR #780 · PLAT-2231"
    attribution_method text        CHECK (attribution_method IN ('caused_by_link', 'blame')),
    found_by           text,                                 -- "QA · Anil Verma", "Monitoring alert"
    assignee_id        uuid        REFERENCES people (id),   -- who is fixing it now
    fixed_by_id        uuid        REFERENCES people (id),
    proactive          boolean     NOT NULL DEFAULT false,   -- self-assigned fix of others' code
    reported_on        date        NOT NULL,
    resolved_on        date,
    source             text        NOT NULL DEFAULT 'seed',
    external_id        text,
    synced_at          timestamptz,
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now(),
    version            integer     NOT NULL DEFAULT 1,
    CHECK (resolved_on IS NULL OR resolved_on >= reported_on)
);
CREATE UNIQUE INDEX bugs_source_external_uq ON bugs (source, external_id);
CREATE INDEX bugs_origin_person_idx ON bugs (origin_person_id, reported_on);
CREATE INDEX bugs_assignee_idx ON bugs (assignee_id);
CREATE INDEX bugs_fixed_by_idx ON bugs (fixed_by_id, resolved_on);
CREATE INDEX bugs_key_idx ON bugs (key);

-- Support tickets taken beyond sprint work; person_id null means the ticket is in the queue.
CREATE TABLE support_tickets (
    id              uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    key             text         NOT NULL,                   -- "SUP-8812"
    title           text         NOT NULL,
    customer        text,
    priority        text         NOT NULL CHECK (priority IN ('High', 'Medium', 'Low')),
    person_id       uuid         REFERENCES people (id),
    how_taken       text,                                    -- "Self-picked from queue", "Assigned by ..."
    hours           numeric(6,2) CHECK (hours >= 0),
    status          text         NOT NULL CHECK (status IN ('Open', 'Resolved')),
    opened_on       date         NOT NULL,
    resolved_on     date,
    csat            smallint     CHECK (csat BETWEEN 1 AND 5),
    resolution_note text,
    source          text         NOT NULL DEFAULT 'seed',
    external_id     text,
    synced_at       timestamptz,
    created_at      timestamptz  NOT NULL DEFAULT now(),
    updated_at      timestamptz  NOT NULL DEFAULT now(),
    version         integer      NOT NULL DEFAULT 1,
    CHECK (resolved_on IS NULL OR resolved_on >= opened_on)
);
CREATE UNIQUE INDEX support_tickets_source_external_uq ON support_tickets (source, external_id);
CREATE INDEX support_tickets_person_idx ON support_tickets (person_id, opened_on);
CREATE INDEX support_tickets_queue_idx ON support_tickets (opened_on) WHERE person_id IS NULL AND status = 'Open';

-- ---------------------------------------------------------------- Pulse-owned
-- Unplanned and extra work detected by Pulse, awaiting a manager decision. Points come from Rules
-- through CalcService, so they are not stored. detection_key makes detection idempotent.
CREATE TABLE extra_items (
    id              uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    detection_key   text         NOT NULL,                   -- e.g. "midsprint:PLAT-2271:<person>"
    person_id       uuid         NOT NULL REFERENCES people (id),
    kind            text         NOT NULL
                    CHECK (kind IN ('midsprint', 'incident', 'takeover', 'help', 'untracked', 'reviews',
                                    'support', 'proactivefix', 'visit')),  -- keys of the "pts" rule
    ref             text         NOT NULL,                   -- "PLAT-2271", "PR #805", "14 PRs"
    title           text         NOT NULL,
    requested_by    text         NOT NULL,                   -- changelog author, escalation or "Self-initiated"
    detection_note  text         NOT NULL,                   -- "How it was detected"
    evidence_source text         NOT NULL,                   -- "Jira", "PagerDuty + GitHub"
    occurred_on     date         NOT NULL,
    hours           numeric(6,2) NOT NULL DEFAULT 0 CHECK (hours >= 0),
    is_done         boolean      NOT NULL DEFAULT false,     -- only done items can be confirmed
    values_credited text[]       NOT NULL DEFAULT '{}',
    state           text         NOT NULL DEFAULT 'pending'
                    CHECK (state IN ('pending', 'confirmed', 'rejected')),
    decided_by      uuid         REFERENCES users (id),
    decided_at      timestamptz,
    created_at      timestamptz  NOT NULL DEFAULT now(),
    updated_at      timestamptz  NOT NULL DEFAULT now(),
    version         integer      NOT NULL DEFAULT 1,
    CHECK ((state = 'pending') = (decided_at IS NULL))
);
CREATE UNIQUE INDEX extra_items_detection_uq ON extra_items (detection_key);
CREATE INDEX extra_items_person_idx ON extra_items (person_id, occurred_on);
CREATE INDEX extra_items_pending_idx ON extra_items (occurred_on) WHERE state = 'pending';

-- Weekly pulse: one answer per person per week (Monday). Visible only to the person, their
-- manager and all.view; the note is length-limited, never logged, and removed with the person.
CREATE TABLE weekly_pulse (
    id           uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    person_id    uuid        NOT NULL REFERENCES people (id) ON DELETE CASCADE,
    week_start   date        NOT NULL CHECK (extract(isodow FROM week_start) = 1),
    mood         smallint    NOT NULL CHECK (mood BETWEEN 1 AND 5),
    workload     smallint    NOT NULL CHECK (workload BETWEEN 1 AND 5),
    note         text        NOT NULL DEFAULT '' CHECK (char_length(note) <= 500),
    submitted_at timestamptz NOT NULL DEFAULT now(),
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),
    version      integer     NOT NULL DEFAULT 1
);
CREATE UNIQUE INDEX weekly_pulse_person_week_uq ON weekly_pulse (person_id, week_start);
