-- S210 demo OKRs (Track 2, task 14.1), from seed/extracted/okrs.json (prototype OKRS).
-- Owners resolve through people.external_id; quarters use the key '2026-Q3'. Applied seeds are
-- forward-only: change demo OKRs in a new S2xx file. OkrSeedTest checks this file against the JSON.

-- o1
WITH o AS (INSERT INTO objectives (quarter, level, owner_id, team, title, epic_key, culture_value) VALUES ('2026-Q3', 'Individual', (SELECT id FROM people WHERE source = 'seed' AND external_id = 'ps'), NULL, 'Make ingestion safe for noisy tenants', 'PLAT-2231', 'Outcome Ownership') RETURNING id)
INSERT INTO key_results (objective_id, position, description, start_value, target_value, current_value, unit) VALUES
    ((SELECT id FROM o), 1, 'Per-tenant rate limiting live for all tenants', 0, 100, 70, '%'),
    ((SELECT id FROM o), 2, 'Ingestion p95 latency under load', 900, 400, 520, 'ms'),
    ((SELECT id FROM o), 3, 'Tenant-caused incidents', 4, 0, 1, 'incidents');

-- o2
WITH o AS (INSERT INTO objectives (quarter, level, owner_id, team, title, epic_key, culture_value) VALUES ('2026-Q3', 'Individual', (SELECT id FROM people WHERE source = 'seed' AND external_id = 'ps'), NULL, 'Grow as a technical lead', NULL, 'Decision Making') RETURNING id)
INSERT INTO key_results (objective_id, position, description, start_value, target_value, current_value, unit) VALUES
    ((SELECT id FROM o), 1, 'ADRs authored and accepted', 0, 2, 1, 'ADRs'),
    ((SELECT id FROM o), 2, 'Design reviews run', 0, 3, 2, 'reviews');

-- o3
WITH o AS (INSERT INTO objectives (quarter, level, owner_id, team, title, epic_key, culture_value) VALUES ('2026-Q3', 'Team', NULL, 'Platform', 'Reliable, predictable Platform delivery', NULL, 'Agility in Execution') RETURNING id)
INSERT INTO key_results (objective_id, position, description, start_value, target_value, current_value, unit) VALUES
    ((SELECT id FROM o), 1, 'Sprint commitment met', 70, 90, 84, '%'),
    ((SELECT id FROM o), 2, 'Stale issues share', 35, 15, 22, '%'),
    ((SELECT id FROM o), 3, 'Change failure rate', 18, 8, 11, '%');

-- o4
WITH o AS (INSERT INTO objectives (quarter, level, owner_id, team, title, epic_key, culture_value) VALUES ('2026-Q3', 'Individual', (SELECT id FROM people WHERE source = 'seed' AND external_id = 'am'), NULL, 'Ship tenant quota enforcement', 'PLAT-2201', 'Outcome Ownership') RETURNING id)
INSERT INTO key_results (objective_id, position, description, start_value, target_value, current_value, unit) VALUES
    ((SELECT id FROM o), 1, 'Quota service in production', 0, 1, 0, 'launch'),
    ((SELECT id FROM o), 2, 'Quota breaches auto-throttled', 0, 95, 60, '%');

-- o5
WITH o AS (INSERT INTO objectives (quarter, level, owner_id, team, title, epic_key, culture_value) VALUES ('2026-Q3', 'Individual', (SELECT id FROM people WHERE source = 'seed' AND external_id = 'nj'), NULL, 'Own the trap-filter module', NULL, 'Problem Solving') RETURNING id)
INSERT INTO key_results (objective_id, position, description, start_value, target_value, current_value, unit) VALUES
    ((SELECT id FROM o), 1, 'Unit test coverage', 35, 80, 62, '%'),
    ((SELECT id FROM o), 2, 'Bugs fixed without reopen', 0, 6, 4, 'bugs');

-- o6
WITH o AS (INSERT INTO objectives (quarter, level, owner_id, team, title, epic_key, culture_value) VALUES ('2026-Q4', 'Individual', (SELECT id FROM people WHERE source = 'seed' AND external_id = 'ps'), NULL, 'Mentor two engineers', NULL, 'Collaboration') RETURNING id)
INSERT INTO key_results (objective_id, position, description, start_value, target_value, current_value, unit) VALUES
    ((SELECT id FROM o), 1, 'Mentees with agreed growth plan', 0, 2, 0, 'people');

-- o7
WITH o AS (INSERT INTO objectives (quarter, level, owner_id, team, title, epic_key, culture_value) VALUES ('2026-Q1', 'Individual', (SELECT id FROM people WHERE source = 'seed' AND external_id = 'ps'), NULL, 'Stabilise the alerting service', 'PLAT-1880', 'Outcome Ownership') RETURNING id)
INSERT INTO key_results (objective_id, position, description, start_value, target_value, current_value, unit) VALUES
    ((SELECT id FROM o), 1, 'Alert false positives', 30, 10, 12, '%'),
    ((SELECT id FROM o), 2, 'Alerting incidents', 5, 1, 1, 'incidents');

-- o8
WITH o AS (INSERT INTO objectives (quarter, level, owner_id, team, title, epic_key, culture_value) VALUES ('2026-Q2', 'Individual', (SELECT id FROM people WHERE source = 'seed' AND external_id = 'ps'), NULL, 'Own alerting end-to-end', 'PLAT-1990', 'Outcome Ownership') RETURNING id)
INSERT INTO key_results (objective_id, position, description, start_value, target_value, current_value, unit) VALUES
    ((SELECT id FROM o), 1, 'On-call runbooks written', 0, 4, 4, 'runbooks'),
    ((SELECT id FROM o), 2, 'Median alert latency', 90, 30, 38, 's');

-- o9
WITH o AS (INSERT INTO objectives (quarter, level, owner_id, team, title, epic_key, culture_value) VALUES ('2026-Q2', 'Team', NULL, 'Platform', 'Cut sprint carry-over', NULL, 'Discipline') RETURNING id)
INSERT INTO key_results (objective_id, position, description, start_value, target_value, current_value, unit) VALUES
    ((SELECT id FROM o), 1, 'Carry-over issues per sprint', 9, 3, 4, 'issues');
