-- Demo fix (Track 3): the outcome is set when the trainee signs off (spec growth/training-mentoring
-- "Sign-off"), so a seeded assignment whose trainee has not signed off yet stays in "Trainee sign-off",
-- as in the prototype (Dev's Code review & PR standard, ta7ib8). S350 stored the manager's decision as the
-- outcome. Loaded only with PULSE_DEMO_SEED=true.
UPDATE training_assignments SET outcome = NULL
WHERE source = 'seed' AND sign_off IS NULL AND outcome IS NOT NULL;
