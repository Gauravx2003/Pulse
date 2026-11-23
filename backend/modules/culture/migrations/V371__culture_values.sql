-- V371 culture value definitions (Track 3, task 19.1; spec culture/culture-values "Traceable detail view").
-- The 11 values with their description, the signals they read and the behaviour anchors 1–5 shown in the
-- detail view. Reference data, loaded by S370 (not demo data); weights stay in rule "vw".

CREATE TABLE culture_values (
    name        text    PRIMARY KEY,
    ord         integer NOT NULL UNIQUE,
    description text    NOT NULL,
    signals     jsonb   NOT NULL DEFAULT '[]'::jsonb,
    anchors     jsonb   NOT NULL CHECK (jsonb_array_length(anchors) = 5)   -- score 1 to 5
);
