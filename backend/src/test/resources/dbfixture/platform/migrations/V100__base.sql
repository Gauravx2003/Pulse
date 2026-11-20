CREATE TABLE apply_order (seq serial PRIMARY KEY, kind text NOT NULL, version int NOT NULL);
INSERT INTO apply_order (kind, version) VALUES ('V', 100);
