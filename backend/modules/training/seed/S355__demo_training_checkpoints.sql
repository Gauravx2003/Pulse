-- Demo training checkpoint cycles (Track 3, task 21.1), from seed/extracted/review_cycles.json and
-- review_requests.json: the prototype's 'ct-<assignment>-<day>' cycles and their reviews (self, manager,
-- and the mentor as the internal group). Needs S340 (reviews) and S350 (training). Demo data only.

INSERT INTO review_cycles (name, quarter, due_on, internal_min, internal_max, cross_min, cross_max, anonymity_threshold, weights, status, created_by, kind, assignment_id, checkpoint_day, source, external_id)
VALUES ('Anjali · Software Engineer onboarding · Day 14', '2026-Q3', '2026-08-17', 1, 3, 0, 2, 1, '{"manager": 50, "internal": 30, "cross": 0, "self": 20}'::jsonb, 'closed', (SELECT id FROM users WHERE lower(email) = 'rahul@pulse.demo'), 'training',
        (SELECT id FROM training_assignments WHERE source = 'seed' AND external_id = 'ta2x9h'), 14, 'seed', 'ct-ta2x9h-14');

INSERT INTO review_cycles (name, quarter, due_on, internal_min, internal_max, cross_min, cross_max, anonymity_threshold, weights, status, created_by, kind, assignment_id, checkpoint_day, source, external_id)
VALUES ('Anjali · Software Engineer onboarding · Day 30', '2026-Q3', '2026-09-02', 1, 3, 0, 2, 1, '{"manager": 50, "internal": 30, "cross": 0, "self": 20}'::jsonb, 'closed', (SELECT id FROM users WHERE lower(email) = 'rahul@pulse.demo'), 'training',
        (SELECT id FROM training_assignments WHERE source = 'seed' AND external_id = 'ta2x9h'), 30, 'seed', 'ct-ta2x9h-30');

INSERT INTO review_cycles (name, quarter, due_on, internal_min, internal_max, cross_min, cross_max, anonymity_threshold, weights, status, created_by, kind, assignment_id, checkpoint_day, source, external_id)
VALUES ('Anjali · Software Engineer onboarding · Day 60', '2026-Q4', '2026-10-02', 1, 3, 0, 2, 1, '{"manager": 50, "internal": 30, "cross": 0, "self": 20}'::jsonb, 'running', (SELECT id FROM users WHERE lower(email) = 'rahul@pulse.demo'), 'training',
        (SELECT id FROM training_assignments WHERE source = 'seed' AND external_id = 'ta2x9h'), 60, 'seed', 'ct-ta2x9h-60');

INSERT INTO review_cycles (name, quarter, due_on, internal_min, internal_max, cross_min, cross_max, anonymity_threshold, weights, status, created_by, kind, assignment_id, checkpoint_day, source, external_id)
VALUES ('Rohit · Software Engineer onboarding · Day 14', '2026-Q3', '2026-09-28', 1, 3, 0, 2, 1, '{"manager": 50, "internal": 30, "cross": 0, "self": 20}'::jsonb, 'running', (SELECT id FROM users WHERE lower(email) = 'rahul@pulse.demo'), 'training',
        (SELECT id FROM training_assignments WHERE source = 'seed' AND external_id = 'ta1kk9'), 14, 'seed', 'ct-ta1kk9-14');

INSERT INTO review_cycles (name, quarter, due_on, internal_min, internal_max, cross_min, cross_max, anonymity_threshold, weights, status, created_by, kind, assignment_id, checkpoint_day, source, external_id)
VALUES ('Anjali · Code review & PR standard · Day 7', '2026-Q3', '2026-08-24', 1, 3, 0, 2, 1, '{"manager": 50, "internal": 30, "cross": 0, "self": 20}'::jsonb, 'closed', (SELECT id FROM users WHERE lower(email) = 'rahul@pulse.demo'), 'training',
        (SELECT id FROM training_assignments WHERE source = 'seed' AND external_id = 'ta4ewq'), 7, 'seed', 'ct-ta4ewq-7');

INSERT INTO review_cycles (name, quarter, due_on, internal_min, internal_max, cross_min, cross_max, anonymity_threshold, weights, status, created_by, kind, assignment_id, checkpoint_day, source, external_id)
VALUES ('Kiran · Ownership & decision making · Day 14', '2026-Q3', '2026-08-31', 1, 3, 0, 2, 1, '{"manager": 50, "internal": 30, "cross": 0, "self": 20}'::jsonb, 'closed', (SELECT id FROM users WHERE lower(email) = 'rahul@pulse.demo'), 'training',
        (SELECT id FROM training_assignments WHERE source = 'seed' AND external_id = 'ta69fv'), 14, 'seed', 'ct-ta69fv-14');

INSERT INTO review_cycles (name, quarter, due_on, internal_min, internal_max, cross_min, cross_max, anonymity_threshold, weights, status, created_by, kind, assignment_id, checkpoint_day, source, external_id)
VALUES ('Kiran · Ownership & decision making · Day 28', '2026-Q3', '2026-09-14', 1, 3, 0, 2, 1, '{"manager": 50, "internal": 30, "cross": 0, "self": 20}'::jsonb, 'closed', (SELECT id FROM users WHERE lower(email) = 'rahul@pulse.demo'), 'training',
        (SELECT id FROM training_assignments WHERE source = 'seed' AND external_id = 'ta69fv'), 28, 'seed', 'ct-ta69fv-28');

INSERT INTO review_cycles (name, quarter, due_on, internal_min, internal_max, cross_min, cross_max, anonymity_threshold, weights, status, created_by, kind, assignment_id, checkpoint_day, source, external_id)
VALUES ('Dev · Code review & PR standard · Day 7', '2026-Q3', '2026-09-14', 1, 3, 0, 2, 1, '{"manager": 50, "internal": 30, "cross": 0, "self": 20}'::jsonb, 'closed', (SELECT id FROM users WHERE lower(email) = 'rahul@pulse.demo'), 'training',
        (SELECT id FROM training_assignments WHERE source = 'seed' AND external_id = 'ta7ib8'), 7, 'seed', 'ct-ta7ib8-7');

INSERT INTO review_reviewer_keys (cycle_id, user_id) VALUES
    ((SELECT id FROM review_cycles WHERE source = 'seed' AND external_id = 'ct-ta1kk9-14'), (SELECT id FROM users WHERE lower(email) = 'priya@pulse.demo')),
    ((SELECT id FROM review_cycles WHERE source = 'seed' AND external_id = 'ct-ta1kk9-14'), (SELECT id FROM users WHERE lower(email) = 'rahul@pulse.demo')),
    ((SELECT id FROM review_cycles WHERE source = 'seed' AND external_id = 'ct-ta1kk9-14'), (SELECT id FROM users WHERE lower(email) = 'rohit@pulse.demo')),
    ((SELECT id FROM review_cycles WHERE source = 'seed' AND external_id = 'ct-ta2x9h-14'), (SELECT id FROM users WHERE lower(email) = 'anjali@pulse.demo')),
    ((SELECT id FROM review_cycles WHERE source = 'seed' AND external_id = 'ct-ta2x9h-14'), (SELECT id FROM users WHERE lower(email) = 'rahul@pulse.demo')),
    ((SELECT id FROM review_cycles WHERE source = 'seed' AND external_id = 'ct-ta2x9h-14'), (SELECT id FROM users WHERE lower(email) = 'sara@pulse.demo')),
    ((SELECT id FROM review_cycles WHERE source = 'seed' AND external_id = 'ct-ta2x9h-30'), (SELECT id FROM users WHERE lower(email) = 'anjali@pulse.demo')),
    ((SELECT id FROM review_cycles WHERE source = 'seed' AND external_id = 'ct-ta2x9h-30'), (SELECT id FROM users WHERE lower(email) = 'rahul@pulse.demo')),
    ((SELECT id FROM review_cycles WHERE source = 'seed' AND external_id = 'ct-ta2x9h-30'), (SELECT id FROM users WHERE lower(email) = 'sara@pulse.demo')),
    ((SELECT id FROM review_cycles WHERE source = 'seed' AND external_id = 'ct-ta2x9h-60'), (SELECT id FROM users WHERE lower(email) = 'anjali@pulse.demo')),
    ((SELECT id FROM review_cycles WHERE source = 'seed' AND external_id = 'ct-ta2x9h-60'), (SELECT id FROM users WHERE lower(email) = 'rahul@pulse.demo')),
    ((SELECT id FROM review_cycles WHERE source = 'seed' AND external_id = 'ct-ta2x9h-60'), (SELECT id FROM users WHERE lower(email) = 'sara@pulse.demo')),
    ((SELECT id FROM review_cycles WHERE source = 'seed' AND external_id = 'ct-ta4ewq-7'), (SELECT id FROM users WHERE lower(email) = 'anjali@pulse.demo')),
    ((SELECT id FROM review_cycles WHERE source = 'seed' AND external_id = 'ct-ta4ewq-7'), (SELECT id FROM users WHERE lower(email) = 'rahul@pulse.demo')),
    ((SELECT id FROM review_cycles WHERE source = 'seed' AND external_id = 'ct-ta4ewq-7'), (SELECT id FROM users WHERE lower(email) = 'sara@pulse.demo')),
    ((SELECT id FROM review_cycles WHERE source = 'seed' AND external_id = 'ct-ta69fv-14'), (SELECT id FROM users WHERE lower(email) = 'kiran@pulse.demo')),
    ((SELECT id FROM review_cycles WHERE source = 'seed' AND external_id = 'ct-ta69fv-14'), (SELECT id FROM users WHERE lower(email) = 'rahul@pulse.demo')),
    ((SELECT id FROM review_cycles WHERE source = 'seed' AND external_id = 'ct-ta69fv-14'), (SELECT id FROM users WHERE lower(email) = 'sara@pulse.demo')),
    ((SELECT id FROM review_cycles WHERE source = 'seed' AND external_id = 'ct-ta69fv-28'), (SELECT id FROM users WHERE lower(email) = 'kiran@pulse.demo')),
    ((SELECT id FROM review_cycles WHERE source = 'seed' AND external_id = 'ct-ta69fv-28'), (SELECT id FROM users WHERE lower(email) = 'rahul@pulse.demo')),
    ((SELECT id FROM review_cycles WHERE source = 'seed' AND external_id = 'ct-ta69fv-28'), (SELECT id FROM users WHERE lower(email) = 'sara@pulse.demo')),
    ((SELECT id FROM review_cycles WHERE source = 'seed' AND external_id = 'ct-ta7ib8-7'), (SELECT id FROM users WHERE lower(email) = 'arjun@pulse.demo')),
    ((SELECT id FROM review_cycles WHERE source = 'seed' AND external_id = 'ct-ta7ib8-7'), (SELECT id FROM users WHERE lower(email) = 'dev@pulse.demo')),
    ((SELECT id FROM review_cycles WHERE source = 'seed' AND external_id = 'ct-ta7ib8-7'), (SELECT id FROM users WHERE lower(email) = 'rahul@pulse.demo'));

INSERT INTO review_requests (cycle_id, subject_id, reviewer_key, rater_group, status, scores, keep_doing, start_doing, submitted_at) VALUES
    ((SELECT id FROM review_cycles WHERE source = 'seed' AND external_id = 'ct-ta2x9h-14'), (SELECT id FROM people WHERE source = 'seed' AND external_id = 'an'),
     (SELECT pseudonym FROM review_reviewer_keys WHERE cycle_id = (SELECT id FROM review_cycles WHERE source = 'seed' AND external_id = 'ct-ta2x9h-14') AND user_id = (SELECT id FROM users WHERE lower(email) = 'anjali@pulse.demo')),
     'self', 'submitted', '{"Outcome Ownership": 3, "Moral & Ethics": 4, "Decision Making": 2, "Problem Solving": 3, "Risk Appetite": 4, "Adaptability": 2, "Agility in Execution": 3, "Collaboration": 4, "Resilience": 2, "Resourcefulness": 3}'::jsonb, 'Asks good questions and picks up the codebase fast.', 'Update Jira every day, not only when done.', '2026-08-19T18:00:00+05:30'),
    ((SELECT id FROM review_cycles WHERE source = 'seed' AND external_id = 'ct-ta2x9h-14'), (SELECT id FROM people WHERE source = 'seed' AND external_id = 'an'),
     (SELECT pseudonym FROM review_reviewer_keys WHERE cycle_id = (SELECT id FROM review_cycles WHERE source = 'seed' AND external_id = 'ct-ta2x9h-14') AND user_id = (SELECT id FROM users WHERE lower(email) = 'rahul@pulse.demo')),
     'manager', 'submitted', '{"Outcome Ownership": 4, "Moral & Ethics": 2, "Decision Making": 3, "Problem Solving": 4, "Risk Appetite": 2, "Adaptability": 3, "Agility in Execution": 4, "Collaboration": 2, "Resilience": 3, "Resourcefulness": 4}'::jsonb, 'Asks good questions and picks up the codebase fast.', 'Update Jira every day, not only when done.', '2026-08-19T18:00:00+05:30'),
    ((SELECT id FROM review_cycles WHERE source = 'seed' AND external_id = 'ct-ta2x9h-14'), (SELECT id FROM people WHERE source = 'seed' AND external_id = 'an'),
     (SELECT pseudonym FROM review_reviewer_keys WHERE cycle_id = (SELECT id FROM review_cycles WHERE source = 'seed' AND external_id = 'ct-ta2x9h-14') AND user_id = (SELECT id FROM users WHERE lower(email) = 'sara@pulse.demo')),
     'internal', 'submitted', '{"Outcome Ownership": 2, "Moral & Ethics": 3, "Decision Making": 4, "Problem Solving": 2, "Risk Appetite": 3, "Adaptability": 4, "Agility in Execution": 2, "Collaboration": 3, "Resilience": 4, "Resourcefulness": 2}'::jsonb, 'Asks good questions and picks up the codebase fast.', 'Update Jira every day, not only when done.', '2026-08-19T18:00:00+05:30'),
    ((SELECT id FROM review_cycles WHERE source = 'seed' AND external_id = 'ct-ta2x9h-30'), (SELECT id FROM people WHERE source = 'seed' AND external_id = 'an'),
     (SELECT pseudonym FROM review_reviewer_keys WHERE cycle_id = (SELECT id FROM review_cycles WHERE source = 'seed' AND external_id = 'ct-ta2x9h-30') AND user_id = (SELECT id FROM users WHERE lower(email) = 'anjali@pulse.demo')),
     'self', 'submitted', '{"Outcome Ownership": 3, "Moral & Ethics": 4, "Decision Making": 2, "Problem Solving": 3, "Risk Appetite": 4, "Adaptability": 2, "Agility in Execution": 3, "Collaboration": 4, "Resilience": 2, "Resourcefulness": 3}'::jsonb, 'Owns small fixes end-to-end now.', 'Speak up earlier when stuck for more than half a day.', '2026-09-04T18:00:00+05:30'),
    ((SELECT id FROM review_cycles WHERE source = 'seed' AND external_id = 'ct-ta2x9h-30'), (SELECT id FROM people WHERE source = 'seed' AND external_id = 'an'),
     (SELECT pseudonym FROM review_reviewer_keys WHERE cycle_id = (SELECT id FROM review_cycles WHERE source = 'seed' AND external_id = 'ct-ta2x9h-30') AND user_id = (SELECT id FROM users WHERE lower(email) = 'rahul@pulse.demo')),
     'manager', 'submitted', '{"Outcome Ownership": 4, "Moral & Ethics": 2, "Decision Making": 3, "Problem Solving": 4, "Risk Appetite": 2, "Adaptability": 3, "Agility in Execution": 4, "Collaboration": 2, "Resilience": 3, "Resourcefulness": 4}'::jsonb, 'Owns small fixes end-to-end now.', 'Speak up earlier when stuck for more than half a day.', '2026-09-04T18:00:00+05:30'),
    ((SELECT id FROM review_cycles WHERE source = 'seed' AND external_id = 'ct-ta2x9h-30'), (SELECT id FROM people WHERE source = 'seed' AND external_id = 'an'),
     (SELECT pseudonym FROM review_reviewer_keys WHERE cycle_id = (SELECT id FROM review_cycles WHERE source = 'seed' AND external_id = 'ct-ta2x9h-30') AND user_id = (SELECT id FROM users WHERE lower(email) = 'sara@pulse.demo')),
     'internal', 'submitted', '{"Outcome Ownership": 2, "Moral & Ethics": 3, "Decision Making": 4, "Problem Solving": 2, "Risk Appetite": 3, "Adaptability": 4, "Agility in Execution": 2, "Collaboration": 3, "Resilience": 4, "Resourcefulness": 2}'::jsonb, 'Owns small fixes end-to-end now.', 'Speak up earlier when stuck for more than half a day.', '2026-09-04T18:00:00+05:30'),
    ((SELECT id FROM review_cycles WHERE source = 'seed' AND external_id = 'ct-ta2x9h-60'), (SELECT id FROM people WHERE source = 'seed' AND external_id = 'an'),
     (SELECT pseudonym FROM review_reviewer_keys WHERE cycle_id = (SELECT id FROM review_cycles WHERE source = 'seed' AND external_id = 'ct-ta2x9h-60') AND user_id = (SELECT id FROM users WHERE lower(email) = 'anjali@pulse.demo')),
     'self', 'not_started', '{}'::jsonb, '', '', NULL),
    ((SELECT id FROM review_cycles WHERE source = 'seed' AND external_id = 'ct-ta2x9h-60'), (SELECT id FROM people WHERE source = 'seed' AND external_id = 'an'),
     (SELECT pseudonym FROM review_reviewer_keys WHERE cycle_id = (SELECT id FROM review_cycles WHERE source = 'seed' AND external_id = 'ct-ta2x9h-60') AND user_id = (SELECT id FROM users WHERE lower(email) = 'rahul@pulse.demo')),
     'manager', 'not_started', '{}'::jsonb, '', '', NULL),
    ((SELECT id FROM review_cycles WHERE source = 'seed' AND external_id = 'ct-ta2x9h-60'), (SELECT id FROM people WHERE source = 'seed' AND external_id = 'an'),
     (SELECT pseudonym FROM review_reviewer_keys WHERE cycle_id = (SELECT id FROM review_cycles WHERE source = 'seed' AND external_id = 'ct-ta2x9h-60') AND user_id = (SELECT id FROM users WHERE lower(email) = 'sara@pulse.demo')),
     'internal', 'not_started', '{}'::jsonb, '', '', NULL),
    ((SELECT id FROM review_cycles WHERE source = 'seed' AND external_id = 'ct-ta1kk9-14'), (SELECT id FROM people WHERE source = 'seed' AND external_id = 'rk'),
     (SELECT pseudonym FROM review_reviewer_keys WHERE cycle_id = (SELECT id FROM review_cycles WHERE source = 'seed' AND external_id = 'ct-ta1kk9-14') AND user_id = (SELECT id FROM users WHERE lower(email) = 'rohit@pulse.demo')),
     'self', 'not_started', '{}'::jsonb, '', '', NULL),
    ((SELECT id FROM review_cycles WHERE source = 'seed' AND external_id = 'ct-ta1kk9-14'), (SELECT id FROM people WHERE source = 'seed' AND external_id = 'rk'),
     (SELECT pseudonym FROM review_reviewer_keys WHERE cycle_id = (SELECT id FROM review_cycles WHERE source = 'seed' AND external_id = 'ct-ta1kk9-14') AND user_id = (SELECT id FROM users WHERE lower(email) = 'rahul@pulse.demo')),
     'manager', 'not_started', '{}'::jsonb, '', '', NULL),
    ((SELECT id FROM review_cycles WHERE source = 'seed' AND external_id = 'ct-ta1kk9-14'), (SELECT id FROM people WHERE source = 'seed' AND external_id = 'rk'),
     (SELECT pseudonym FROM review_reviewer_keys WHERE cycle_id = (SELECT id FROM review_cycles WHERE source = 'seed' AND external_id = 'ct-ta1kk9-14') AND user_id = (SELECT id FROM users WHERE lower(email) = 'priya@pulse.demo')),
     'internal', 'not_started', '{}'::jsonb, '', '', NULL),
    ((SELECT id FROM review_cycles WHERE source = 'seed' AND external_id = 'ct-ta4ewq-7'), (SELECT id FROM people WHERE source = 'seed' AND external_id = 'an'),
     (SELECT pseudonym FROM review_reviewer_keys WHERE cycle_id = (SELECT id FROM review_cycles WHERE source = 'seed' AND external_id = 'ct-ta4ewq-7') AND user_id = (SELECT id FROM users WHERE lower(email) = 'anjali@pulse.demo')),
     'self', 'submitted', '{"Outcome Ownership": 4, "Moral & Ethics": 5, "Decision Making": 3, "Problem Solving": 4, "Risk Appetite": 5, "Adaptability": 3, "Agility in Execution": 4, "Collaboration": 5, "Resilience": 3, "Resourcefulness": 4}'::jsonb, 'Labels every comment and is polite.', 'Always add the “because”.', '2026-08-25T18:00:00+05:30'),
    ((SELECT id FROM review_cycles WHERE source = 'seed' AND external_id = 'ct-ta4ewq-7'), (SELECT id FROM people WHERE source = 'seed' AND external_id = 'an'),
     (SELECT pseudonym FROM review_reviewer_keys WHERE cycle_id = (SELECT id FROM review_cycles WHERE source = 'seed' AND external_id = 'ct-ta4ewq-7') AND user_id = (SELECT id FROM users WHERE lower(email) = 'rahul@pulse.demo')),
     'manager', 'submitted', '{"Outcome Ownership": 5, "Moral & Ethics": 3, "Decision Making": 4, "Problem Solving": 5, "Risk Appetite": 3, "Adaptability": 4, "Agility in Execution": 5, "Collaboration": 3, "Resilience": 4, "Resourcefulness": 5}'::jsonb, 'Labels every comment and is polite.', 'Always add the “because”.', '2026-08-25T18:00:00+05:30'),
    ((SELECT id FROM review_cycles WHERE source = 'seed' AND external_id = 'ct-ta4ewq-7'), (SELECT id FROM people WHERE source = 'seed' AND external_id = 'an'),
     (SELECT pseudonym FROM review_reviewer_keys WHERE cycle_id = (SELECT id FROM review_cycles WHERE source = 'seed' AND external_id = 'ct-ta4ewq-7') AND user_id = (SELECT id FROM users WHERE lower(email) = 'sara@pulse.demo')),
     'internal', 'submitted', '{"Outcome Ownership": 4, "Moral & Ethics": 5, "Decision Making": 3, "Problem Solving": 4, "Risk Appetite": 5, "Adaptability": 3, "Agility in Execution": 4, "Collaboration": 5, "Resilience": 3, "Resourcefulness": 4}'::jsonb, 'Labels every comment and is polite.', 'Always add the “because”.', '2026-08-25T18:00:00+05:30'),
    ((SELECT id FROM review_cycles WHERE source = 'seed' AND external_id = 'ct-ta69fv-14'), (SELECT id FROM people WHERE source = 'seed' AND external_id = 'kr'),
     (SELECT pseudonym FROM review_reviewer_keys WHERE cycle_id = (SELECT id FROM review_cycles WHERE source = 'seed' AND external_id = 'ct-ta69fv-14') AND user_id = (SELECT id FROM users WHERE lower(email) = 'kiran@pulse.demo')),
     'self', 'submitted', '{"Outcome Ownership": 5, "Moral & Ethics": 3, "Decision Making": 4, "Problem Solving": 5, "Risk Appetite": 3, "Adaptability": 4, "Agility in Execution": 5, "Collaboration": 3, "Resilience": 4, "Resourcefulness": 5}'::jsonb, 'Frames options clearly.', 'Decide faster once data is in.', '2026-09-01T18:00:00+05:30'),
    ((SELECT id FROM review_cycles WHERE source = 'seed' AND external_id = 'ct-ta69fv-14'), (SELECT id FROM people WHERE source = 'seed' AND external_id = 'kr'),
     (SELECT pseudonym FROM review_reviewer_keys WHERE cycle_id = (SELECT id FROM review_cycles WHERE source = 'seed' AND external_id = 'ct-ta69fv-14') AND user_id = (SELECT id FROM users WHERE lower(email) = 'rahul@pulse.demo')),
     'manager', 'submitted', '{"Outcome Ownership": 3, "Moral & Ethics": 4, "Decision Making": 5, "Problem Solving": 3, "Risk Appetite": 4, "Adaptability": 5, "Agility in Execution": 3, "Collaboration": 4, "Resilience": 5, "Resourcefulness": 3}'::jsonb, 'Frames options clearly.', 'Decide faster once data is in.', '2026-09-01T18:00:00+05:30'),
    ((SELECT id FROM review_cycles WHERE source = 'seed' AND external_id = 'ct-ta69fv-14'), (SELECT id FROM people WHERE source = 'seed' AND external_id = 'kr'),
     (SELECT pseudonym FROM review_reviewer_keys WHERE cycle_id = (SELECT id FROM review_cycles WHERE source = 'seed' AND external_id = 'ct-ta69fv-14') AND user_id = (SELECT id FROM users WHERE lower(email) = 'sara@pulse.demo')),
     'internal', 'submitted', '{"Outcome Ownership": 4, "Moral & Ethics": 5, "Decision Making": 3, "Problem Solving": 4, "Risk Appetite": 5, "Adaptability": 3, "Agility in Execution": 4, "Collaboration": 5, "Resilience": 3, "Resourcefulness": 4}'::jsonb, 'Frames options clearly.', 'Decide faster once data is in.', '2026-09-01T18:00:00+05:30'),
    ((SELECT id FROM review_cycles WHERE source = 'seed' AND external_id = 'ct-ta69fv-28'), (SELECT id FROM people WHERE source = 'seed' AND external_id = 'kr'),
     (SELECT pseudonym FROM review_reviewer_keys WHERE cycle_id = (SELECT id FROM review_cycles WHERE source = 'seed' AND external_id = 'ct-ta69fv-28') AND user_id = (SELECT id FROM users WHERE lower(email) = 'kiran@pulse.demo')),
     'self', 'submitted', '{"Outcome Ownership": 5, "Moral & Ethics": 3, "Decision Making": 4, "Problem Solving": 5, "Risk Appetite": 3, "Adaptability": 4, "Agility in Execution": 5, "Collaboration": 3, "Resilience": 4, "Resourcefulness": 5}'::jsonb, 'Ran a calm, well-timed design review.', 'Share the ADR earlier with product.', '2026-09-15T18:00:00+05:30'),
    ((SELECT id FROM review_cycles WHERE source = 'seed' AND external_id = 'ct-ta69fv-28'), (SELECT id FROM people WHERE source = 'seed' AND external_id = 'kr'),
     (SELECT pseudonym FROM review_reviewer_keys WHERE cycle_id = (SELECT id FROM review_cycles WHERE source = 'seed' AND external_id = 'ct-ta69fv-28') AND user_id = (SELECT id FROM users WHERE lower(email) = 'rahul@pulse.demo')),
     'manager', 'submitted', '{"Outcome Ownership": 3, "Moral & Ethics": 4, "Decision Making": 5, "Problem Solving": 3, "Risk Appetite": 4, "Adaptability": 5, "Agility in Execution": 3, "Collaboration": 4, "Resilience": 5, "Resourcefulness": 3}'::jsonb, 'Ran a calm, well-timed design review.', 'Share the ADR earlier with product.', '2026-09-15T18:00:00+05:30'),
    ((SELECT id FROM review_cycles WHERE source = 'seed' AND external_id = 'ct-ta69fv-28'), (SELECT id FROM people WHERE source = 'seed' AND external_id = 'kr'),
     (SELECT pseudonym FROM review_reviewer_keys WHERE cycle_id = (SELECT id FROM review_cycles WHERE source = 'seed' AND external_id = 'ct-ta69fv-28') AND user_id = (SELECT id FROM users WHERE lower(email) = 'sara@pulse.demo')),
     'internal', 'submitted', '{"Outcome Ownership": 4, "Moral & Ethics": 5, "Decision Making": 3, "Problem Solving": 4, "Risk Appetite": 5, "Adaptability": 3, "Agility in Execution": 4, "Collaboration": 5, "Resilience": 3, "Resourcefulness": 4}'::jsonb, 'Ran a calm, well-timed design review.', 'Share the ADR earlier with product.', '2026-09-15T18:00:00+05:30'),
    ((SELECT id FROM review_cycles WHERE source = 'seed' AND external_id = 'ct-ta7ib8-7'), (SELECT id FROM people WHERE source = 'seed' AND external_id = 'dp'),
     (SELECT pseudonym FROM review_reviewer_keys WHERE cycle_id = (SELECT id FROM review_cycles WHERE source = 'seed' AND external_id = 'ct-ta7ib8-7') AND user_id = (SELECT id FROM users WHERE lower(email) = 'dev@pulse.demo')),
     'self', 'submitted', '{"Outcome Ownership": 5, "Moral & Ethics": 3, "Decision Making": 4, "Problem Solving": 5, "Risk Appetite": 3, "Adaptability": 4, "Agility in Execution": 5, "Collaboration": 3, "Resilience": 4, "Resourcefulness": 5}'::jsonb, 'Clear, kind review comments.', 'Review within 24 h.', '2026-09-15T18:00:00+05:30'),
    ((SELECT id FROM review_cycles WHERE source = 'seed' AND external_id = 'ct-ta7ib8-7'), (SELECT id FROM people WHERE source = 'seed' AND external_id = 'dp'),
     (SELECT pseudonym FROM review_reviewer_keys WHERE cycle_id = (SELECT id FROM review_cycles WHERE source = 'seed' AND external_id = 'ct-ta7ib8-7') AND user_id = (SELECT id FROM users WHERE lower(email) = 'rahul@pulse.demo')),
     'manager', 'submitted', '{"Outcome Ownership": 3, "Moral & Ethics": 4, "Decision Making": 5, "Problem Solving": 3, "Risk Appetite": 4, "Adaptability": 5, "Agility in Execution": 3, "Collaboration": 4, "Resilience": 5, "Resourcefulness": 3}'::jsonb, 'Clear, kind review comments.', 'Review within 24 h.', '2026-09-15T18:00:00+05:30'),
    ((SELECT id FROM review_cycles WHERE source = 'seed' AND external_id = 'ct-ta7ib8-7'), (SELECT id FROM people WHERE source = 'seed' AND external_id = 'dp'),
     (SELECT pseudonym FROM review_reviewer_keys WHERE cycle_id = (SELECT id FROM review_cycles WHERE source = 'seed' AND external_id = 'ct-ta7ib8-7') AND user_id = (SELECT id FROM users WHERE lower(email) = 'arjun@pulse.demo')),
     'internal', 'submitted', '{"Outcome Ownership": 4, "Moral & Ethics": 5, "Decision Making": 3, "Problem Solving": 4, "Risk Appetite": 5, "Adaptability": 3, "Agility in Execution": 4, "Collaboration": 5, "Resilience": 3, "Resourcefulness": 4}'::jsonb, 'Clear, kind review comments.', 'Review within 24 h.', '2026-09-15T18:00:00+05:30');
