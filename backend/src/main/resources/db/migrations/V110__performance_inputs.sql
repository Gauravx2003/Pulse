-- Performance-index inputs that v1 reads from seed (tasks 4.3 and 4.4, design.md "Deviations":
-- Delivery and Quality pillars are seeded, as in the prototype).
--
--   pillars          the seeded Delivery, Quality, OKR and Growth pillar values per person and quarter.
--                    Culture is always calculated; OKR is the fallback when a person has no OKRs in
--                    the quarter; Growth is blended with the final training rating by CalcService.
--   culture_ratings  the published 360° rating per person, quarter and culture value: the combined
--                    rating (rater_group 'Combined') and the per-group averages shown in the detail view.
--
-- Quarters use the culture_signals form, '2026-Q3'.

CREATE TABLE pillars (
    id          uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    person_id   uuid         NOT NULL REFERENCES people (id) ON DELETE CASCADE,
    quarter     text         NOT NULL CHECK (quarter ~ '^\d{4}-Q[1-4]$'),
    delivery    numeric(4,2) NOT NULL CHECK (delivery BETWEEN 1 AND 5),
    quality     numeric(4,2) NOT NULL CHECK (quality BETWEEN 1 AND 5),
    okr         numeric(4,2) NOT NULL CHECK (okr BETWEEN 1 AND 5),
    growth      numeric(4,2) NOT NULL CHECK (growth BETWEEN 1 AND 5),
    source      text         NOT NULL DEFAULT 'seed',
    external_id text,
    synced_at   timestamptz,
    created_at  timestamptz  NOT NULL DEFAULT now(),
    updated_at  timestamptz  NOT NULL DEFAULT now(),
    version     integer      NOT NULL DEFAULT 1,
    UNIQUE (person_id, quarter)
);
CREATE UNIQUE INDEX pillars_source_external_uq ON pillars (source, external_id);

CREATE TABLE culture_ratings (
    id          uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    person_id   uuid         NOT NULL REFERENCES people (id) ON DELETE CASCADE,
    quarter     text         NOT NULL CHECK (quarter ~ '^\d{4}-Q[1-4]$'),
    value_name  text         NOT NULL,                  -- one of the 11 culture values
    rater_group text         NOT NULL,                  -- 'Combined', 'Manager', 'Peers', 'Self', ...
    rating      numeric(4,2) NOT NULL CHECK (rating BETWEEN 1 AND 5),
    source      text         NOT NULL DEFAULT 'seed',
    external_id text,
    synced_at   timestamptz,
    created_at  timestamptz  NOT NULL DEFAULT now(),
    updated_at  timestamptz  NOT NULL DEFAULT now(),
    version     integer      NOT NULL DEFAULT 1,
    UNIQUE (person_id, quarter, value_name, rater_group)
);
CREATE UNIQUE INDEX culture_ratings_source_external_uq ON culture_ratings (source, external_id);
CREATE INDEX culture_ratings_person_idx ON culture_ratings (person_id, quarter);
