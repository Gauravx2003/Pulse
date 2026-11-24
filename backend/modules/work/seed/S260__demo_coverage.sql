-- S260 demo skills coverage (Track 2, task 13.2), from seed/extracted/areas.json and coverage.json
-- (prototype AREAS and COVER). Applied seeds are forward-only: change them in a new S2xx file.

INSERT INTO coverage_areas (area, sort_order) VALUES
    ('ingestion', 1),
    ('alerts', 2),
    ('quota', 3),
    ('topology', 4),
    ('netflow', 5),
    ('collector', 6),
    ('ui', 7),
    ('ci', 8);

INSERT INTO coverage_counts (area, person_id, touches) VALUES
    ('ingestion', (SELECT id FROM people WHERE source = 'seed' AND external_id = 'ps'), 9),
    ('ingestion', (SELECT id FROM people WHERE source = 'seed' AND external_id = 'am'), 3),
    ('ingestion', (SELECT id FROM people WHERE source = 'seed' AND external_id = 'sk'), 2),
    ('alerts', (SELECT id FROM people WHERE source = 'seed' AND external_id = 'ps'), 6),
    ('alerts', (SELECT id FROM people WHERE source = 'seed' AND external_id = 'nj'), 2),
    ('quota', (SELECT id FROM people WHERE source = 'seed' AND external_id = 'am'), 11),
    ('topology', (SELECT id FROM people WHERE source = 'seed' AND external_id = 'kr'), 8),
    ('topology', (SELECT id FROM people WHERE source = 'seed' AND external_id = 'dp'), 1),
    ('netflow', (SELECT id FROM people WHERE source = 'seed' AND external_id = 'dp'), 10),
    ('collector', (SELECT id FROM people WHERE source = 'seed' AND external_id = 'sk'), 7),
    ('collector', (SELECT id FROM people WHERE source = 'seed' AND external_id = 'kr'), 3),
    ('collector', (SELECT id FROM people WHERE source = 'seed' AND external_id = 'dp'), 2),
    ('ui', (SELECT id FROM people WHERE source = 'seed' AND external_id = 'nj'), 5),
    ('ui', (SELECT id FROM people WHERE source = 'seed' AND external_id = 'ps'), 2),
    ('ci', (SELECT id FROM people WHERE source = 'seed' AND external_id = 'kr'), 3),
    ('ci', (SELECT id FROM people WHERE source = 'seed' AND external_id = 'sk'), 4),
    ('ci', (SELECT id FROM people WHERE source = 'seed' AND external_id = 'am'), 2);
