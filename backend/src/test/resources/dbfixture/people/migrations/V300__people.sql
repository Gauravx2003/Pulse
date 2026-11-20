-- depends on V200: fails if run before it
INSERT INTO work_items (id) VALUES (300);
INSERT INTO apply_order (kind, version) VALUES ('V', 300);
