-- Demo integrations state (Track 1, task 5.4), from seed/extracted/integration_credentials.json and
-- credential_log.json. Secret fields hold a masked placeholder only ("••••" + last 4), never a secret.
-- Times are Asia/Kolkata wall-clock times of the prototype's demo week.

INSERT INTO integration_settings (tool, method, credentials, credentials_saved_at, credentials_saved_by, tested_at, test_ok) VALUES ('jira', 'API token', '{"site": "https://motadata-demo.atlassian.net", "email": "pulse-bot@company.com", "token": "••••9Qk2"}'::jsonb, '2026-07-02 09:00 Asia/Kolkata'::timestamptz, (SELECT id FROM users WHERE lower(email) = 'hr@pulse.demo'), '2026-09-28 15:20 Asia/Kolkata'::timestamptz, true);
INSERT INTO integration_settings (tool, method, credentials, credentials_saved_at, credentials_saved_by, tested_at, test_ok) VALUES ('gh', 'GitHub App', '{"org": "motadata-demo", "appId": "412903", "installId": "51833021", "key": "••••RSAQ"}'::jsonb, '2026-06-18 09:00 Asia/Kolkata'::timestamptz, (SELECT id FROM users WHERE lower(email) = 'hr@pulse.demo'), '2026-09-28 15:14 Asia/Kolkata'::timestamptz, true);
INSERT INTO integration_settings (tool, method, credentials, credentials_saved_at, credentials_saved_by, tested_at, test_ok) VALUES ('cf', 'API token', '{"site": "https://motadata-demo.atlassian.net/wiki", "email": "pulse-bot@company.com", "token": "••••7Hs1"}'::jsonb, '2026-07-02 09:00 Asia/Kolkata'::timestamptz, (SELECT id FROM users WHERE lower(email) = 'hr@pulse.demo'), '2026-09-28 09:00 Asia/Kolkata'::timestamptz, true);
INSERT INTO integration_settings (tool, method, credentials, credentials_saved_at, credentials_saved_by, tested_at, test_ok) VALUES ('sl', 'Slack app (bot token)', '{"workspace": "motadata-demo.slack.com", "token": "••••a8Pz", "signing": "••••b21e"}'::jsonb, '2026-07-04 09:00 Asia/Kolkata'::timestamptz, (SELECT id FROM users WHERE lower(email) = 'hr@pulse.demo'), '2026-09-28 09:00 Asia/Kolkata'::timestamptz, true);
INSERT INTO integration_settings (tool, method, credentials, credentials_saved_at, credentials_saved_by, tested_at, test_ok) VALUES ('gc', 'Service account', '{"domain": "company.com", "sa": "••••ount"}'::jsonb, '2026-05-20 09:00 Asia/Kolkata'::timestamptz, (SELECT id FROM users WHERE lower(email) = 'hr@pulse.demo'), '2026-09-28 06:00 Asia/Kolkata'::timestamptz, true);
INSERT INTO integration_settings (tool, method, credentials, credentials_saved_at, credentials_saved_by, tested_at, test_ok) VALUES ('hr', 'API client', '{"url": "https://motadata-demo.keka.com", "clientId": "keka-pulse-01", "clientSecret": "••••c9x1", "apiKey": "••••4f2a"}'::jsonb, '2026-03-11 09:00 Asia/Kolkata'::timestamptz, (SELECT id FROM users WHERE lower(email) = 'hr@pulse.demo'), '2026-09-28 15:02 Asia/Kolkata'::timestamptz, false);
INSERT INTO integration_settings (tool, method, credentials, credentials_saved_at, credentials_saved_by, tested_at, test_ok) VALUES ('tfs', 'Personal access token', '{"url": "https://tfs.motadata.local/tfs/DefaultCollection", "project": "NMS", "token": "••••7Kx2"}'::jsonb, '2026-09-01 09:00 Asia/Kolkata'::timestamptz, (SELECT id FROM users WHERE lower(email) = 'hr@pulse.demo'), '2026-09-28 15:21 Asia/Kolkata'::timestamptz, true);

-- Slack's bot token expires on 1 Oct (credential_log.json: "Warning sent: bot token expires 1 Oct").
UPDATE integration_settings SET token_expires_on = '2026-10-01' WHERE tool = 'sl';

UPDATE integration_settings SET config = '{"boards": ["PLAT"], "storyPointsField": "customfield_10016", "ignoreBotEdits": true}'::jsonb WHERE tool = 'jira';

INSERT INTO credential_log (tool, action, message, actor_id, at) VALUES ('jira', 'added', 'Credentials added (API token)', (SELECT id FROM users WHERE lower(email) = 'hr@pulse.demo'), '2026-07-02 16:05 Asia/Kolkata'::timestamptz);
INSERT INTO credential_log (tool, action, message, actor_id, at) VALUES ('sl', 'added', 'Credentials added', (SELECT id FROM users WHERE lower(email) = 'hr@pulse.demo'), '2026-07-04 11:30 Asia/Kolkata'::timestamptz);
INSERT INTO credential_log (tool, action, message, actor_id, at) VALUES ('tfs', 'added', 'Credentials added (Personal access token)', (SELECT id FROM users WHERE lower(email) = 'hr@pulse.demo'), '2026-09-01 12:10 Asia/Kolkata'::timestamptz);
INSERT INTO credential_log (tool, action, message, actor_id, at) VALUES ('sl', 'warning', 'Warning sent: bot token expires 1 Oct', NULL, '2026-09-24 10:11 Asia/Kolkata'::timestamptz);
INSERT INTO credential_log (tool, action, message, actor_id, at) VALUES ('hr', 'sync_failed', 'Scheduled sync failed: 401 Unauthorized', NULL, '2026-09-28 15:02 Asia/Kolkata'::timestamptz);

-- HRMS starts in the failed state of the prototype ("Sync failed · 401 Unauthorized").
INSERT INTO sync_log (tool, started_at, finished_at, outcome, counts, message, simulated) VALUES ('hr', now() - interval '20 minutes', now() - interval '20 minutes', 'failed', '{}'::jsonb, '401 Unauthorized', true);
