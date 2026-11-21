-- Fix for S340 (Track 3, task 20.3): the prototype's "Approved" requests are assigned but not answered
-- (they carry no answers and its results count only "Submitted"). S340 loaded them as submitted;
-- S340 is merged, so this later seed corrects them instead of editing it. Demo data only.
UPDATE review_requests r
SET status = 'not_started', submitted_at = NULL
FROM review_cycles c
WHERE c.id = r.cycle_id AND c.source = 'seed' AND r.status = 'submitted' AND r.scores = '{}'::jsonb;
