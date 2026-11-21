-- Demo 1:1s and kudos (Track 3, task 20.2), from seed/extracted/one_on_ones.json and kudos.json
-- (the prototype's sample data). Loaded only with PULSE_DEMO_SEED=true.

-- Priya's two past 1:1s with Rahul and their action items.
WITH m AS (
    INSERT INTO one_on_ones (person_id, manager_id, held_on, notes, created_by)
    VALUES ((SELECT id FROM people WHERE source = 'seed' AND external_id = 'ps'),
            (SELECT id FROM users WHERE lower(email) = 'rahul@pulse.demo'),
            '2026-09-15',
            'Discussed epic scope; agreed Priya drives the rate-limit design. Action: split PLAT-2217 into smaller issues.',
            (SELECT id FROM users WHERE lower(email) = 'rahul@pulse.demo'))
    RETURNING id, person_id)
INSERT INTO one_on_one_actions (one_on_one_id, person_id, text, owner_id, done)
SELECT m.id, m.person_id, a.text, (SELECT id FROM users WHERE lower(email) = 'priya@pulse.demo'), a.done
FROM m, (VALUES ('Split PLAT-2217 into 2–3 issues', false), ('Draft ADR for per-tenant limits', true)) AS a (text, done);

WITH m AS (
    INSERT INTO one_on_ones (person_id, manager_id, held_on, notes, created_by)
    VALUES ((SELECT id FROM people WHERE source = 'seed' AND external_id = 'ps'),
            (SELECT id FROM users WHERE lower(email) = 'rahul@pulse.demo'),
            '2026-09-01',
            'Growth: mentoring goal for Q1. Workload felt high after incident.',
            (SELECT id FROM users WHERE lower(email) = 'rahul@pulse.demo'))
    RETURNING id, person_id)
INSERT INTO one_on_one_actions (one_on_one_id, person_id, text, owner_id, done)
SELECT m.id, m.person_id, 'Pick a mentee with Rahul', (SELECT id FROM users WHERE lower(email) = 'priya@pulse.demo'), true
FROM m;

-- Kudos (seed rows use their source and position as external_id).
INSERT INTO kudos (person_id, from_user_id, culture_value, message, source, external_id, given_at)
VALUES
    ((SELECT id FROM people WHERE source = 'seed' AND external_id = 'ps'),
     (SELECT id FROM users WHERE lower(email) = 'arjun@pulse.demo'),
     'Collaboration', 'Reviewed my quota service PR the same day and caught a race condition.', 'seed', 'kudos-1',
     '2026-09-18 10:00+05:30'),
    ((SELECT id FROM people WHERE source = 'seed' AND external_id = 'ps'),
     (SELECT id FROM users WHERE lower(email) = 'sara@pulse.demo'),
     'Resilience', 'Stayed calm through the Saturday ingestion incident.', 'seed', 'kudos-2',
     '2026-09-08 10:00+05:30'),
    ((SELECT id FROM people WHERE source = 'seed' AND external_id = 'nj'),
     (SELECT id FROM users WHERE lower(email) = 'priya@pulse.demo'),
     'Adaptability', 'Picked up the trap filter work mid-sprint without missing a beat.', 'seed', 'kudos-3',
     '2026-09-24 10:00+05:30');
