-- Demo: the last 1:1 of every other seeded person (Track 3, for Track 2's "1:1 overdue" signal), from the
-- prototype's LAST11 (app.js ~3930). Priya's 1:1s are in S330. The other participant is the person's
-- current manager, else Rahul. Loaded only with PULSE_DEMO_SEED=true.
INSERT INTO one_on_ones (person_id, manager_id, held_on, notes, created_by)
SELECT p.id,
       coalesce((SELECT rl.manager_id FROM reporting_lines rl WHERE rl.user_id = p.user_id AND rl.valid_to IS NULL LIMIT 1),
                (SELECT id FROM users WHERE lower(email) = 'rahul@pulse.demo')),
       v.held_on::date, '',
       (SELECT id FROM users WHERE lower(email) = 'rahul@pulse.demo')
FROM (VALUES ('am', '2026-09-10'), ('nj', '2026-08-28'), ('kr', '2026-09-22'), ('dp', '2026-09-03'),
             ('sk', '2026-09-17'), ('rk', '2026-09-21'), ('an', '2026-09-08')) AS v(person_key, held_on)
JOIN people p ON p.source = 'seed' AND p.external_id = v.person_key
WHERE NOT EXISTS (SELECT 1 FROM one_on_ones o WHERE o.person_id = p.id AND o.held_on = v.held_on::date);
