-- V345 training checkpoint cycles (Track 3, task 21.1; spec culture/review-360 "Training checkpoints",
-- growth/training-mentoring). Checkpoint reviews use the same form and requests as 360° feedback, as
-- cycles of kind 'training' tied to one training assignment and checkpoint day. Many run at once, so
-- "only one cycle runs at a time" now applies to 360° cycles only. Checkpoint groups are named (self,
-- mentor, manager), so their anonymity threshold may be 1.
--
-- assignment_id has no foreign key: training_assignments arrives later (V350, training module).

ALTER TABLE review_cycles
    ADD COLUMN kind           text    NOT NULL DEFAULT '360' CHECK (kind IN ('360', 'training')),
    ADD COLUMN assignment_id  uuid,
    ADD COLUMN checkpoint_day integer CHECK (checkpoint_day BETWEEN 1 AND 366);

ALTER TABLE review_cycles
    ADD CONSTRAINT review_cycles_training_link_check
        CHECK ((kind = 'training') = (assignment_id IS NOT NULL AND checkpoint_day IS NOT NULL));

ALTER TABLE review_cycles DROP CONSTRAINT review_cycles_anonymity_threshold_check;

ALTER TABLE review_cycles
    ADD CONSTRAINT review_cycles_anonymity_threshold_check
        CHECK (anonymity_threshold BETWEEN 1 AND 10 AND (kind = 'training' OR anonymity_threshold >= 2));

DROP INDEX review_cycles_one_running_uq;

CREATE UNIQUE INDEX review_cycles_one_running_uq ON review_cycles ((true)) WHERE status = 'running' AND kind = '360';

CREATE UNIQUE INDEX review_cycles_checkpoint_uq ON review_cycles (assignment_id, checkpoint_day) WHERE kind = 'training';
