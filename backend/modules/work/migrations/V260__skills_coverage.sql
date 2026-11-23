-- V260 Skills coverage (Track 2, task 13.2, spec insight/action-center "Skills coverage"): the areas of
-- the product and how often each person worked in them over the last 6 months (PRs merged and Jira
-- components). In v1 both tables are seeded from the prototype (AREAS and COVER); a live connector would
-- count PR paths and issue components per area. work.coverage marks each area Single point of failure,
-- Thin or Covered from these counts.
-- Grants: V100 set default privileges, so pulse_app gets DML on these tables automatically.

CREATE TABLE coverage_areas (
    area       text        PRIMARY KEY CHECK (char_length(area) BETWEEN 1 AND 60),
    sort_order integer     NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE coverage_counts (
    area       text        NOT NULL REFERENCES coverage_areas (area) ON DELETE CASCADE,
    person_id  uuid        NOT NULL REFERENCES people (id) ON DELETE CASCADE,
    touches    integer     NOT NULL CHECK (touches > 0),  -- PRs merged and issues worked in the area, 6 months
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (area, person_id)
);

CREATE INDEX coverage_counts_person_idx ON coverage_counts (person_id);
