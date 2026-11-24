-- Performance review submissions for calibration (task 20.1, spec culture/reviews-performance).
-- One row per person and quarter; the index and pillars are kept as they were when submitted.
CREATE TABLE performance_reviews (
    person_id    uuid         NOT NULL REFERENCES people (id) ON DELETE CASCADE,
    quarter      text         NOT NULL CHECK (quarter ~ '^[0-9]{4}-Q[1-4]$'),   -- e.g. '2026-Q3'
    index_score  numeric(4,2) NOT NULL CHECK (index_score BETWEEN 1 AND 5),
    pillars      jsonb        NOT NULL,                                       -- {delivery, quality, culture, okr, growth}
    proposed     text         NOT NULL CHECK (proposed IN ('Exceeds', 'Meets', 'Developing')),
    submitted_by uuid         NOT NULL REFERENCES users (id),
    submitted_at timestamptz  NOT NULL DEFAULT now(),
    version      integer      NOT NULL DEFAULT 1,
    PRIMARY KEY (person_id, quarter)
);
