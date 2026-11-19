# Data imports (CSV)

When a module has no live integration, its data comes in through a CSV file. **Nothing is saved until the person confirms**, every upload is logged, and imports made in the session can be undone.

## Flow

1. **Choose file.** Drop or choose a `.csv` file, paste the CSV text, or load the sample file. The modal lists every column with its required / optional status, format and example, and offers a downloadable template (or the template shown as text).
2. **Check.** Rows read, ready (new / update), errors, warnings and skipped rows · a summary (for example, which months a file replaces) · mode choice · errors with line, column and problem · warnings · a preview of the first 12 rows with the action for each.
3. **Done.** Result, *Undo this import* and *Open <page>*.

Parsing is forgiving:

- UTF-8 with or without BOM; comma, semicolon or tab separators; quoted fields.
- Header names are case-insensitive and accept common aliases (`email`, `work_email`, `reports_to` …).
- Dates can be YYYY-MM-DD or DD-MM-YYYY.
- Up to 5,000 rows and 5 MB per file. Excel files are rejected with a "Save as CSV UTF-8" tip.

## Importers

| Data | Who | Columns (required in bold) | Key checks |
|---|---|---|---|
| OKRs | `okr.own` (own rows) · `okr.team` | **quarter, owner_email, objective, key_result, start, target**, level, current, unit, value, epic | Quarter must exist · owner must be visible to you · target ≠ start · rows with the same quarter + owner + objective become one objective · a matching objective is updated, and a new current value counts as a bulk check-in |
| Attendance | `attendance.upload` | **employee_email, date, status**, leave_type, applied_on, check_in, check_out | Known user with a profile · no future or pre-joining dates · one row per person per day · leave needs a type · HH:MM times · leave that clashes with a customer visit is an error · warns on weekend work, missing days and holiday rows |
| Users | `users.all` · `users.team` | **name, email**, role, manager_email, title, department, level, status | Valid, unique email · role Admin / Manager / Employee only (an intern or trainee is an Employee on a training plan) · a manager can be added in the same file · managers can only add Employees to their own team · an existing email updates that user · invites go out with temporary passwords |
| Customer visits | Managers, Admin | **employee_email, customer, city, purpose, from, to, requested_by, owner_email**, reference | Person in your team · purpose from the list · max 14 days · no overlap with other visits or leave · added as planned visits, and the engineer is notified |
| Holiday calendar | `attendance.upload` or `rules.manage` | **date, name** | Valid date · weekend holidays kept with a note · past dates recalculate capacity and attendance |

**Export current** writes each data set in the same column layout, so a file can be exported, edited and re-imported. There is also an **appraisal proposals** export for payroll: index, recommended and proposed increment, and status.

## Where each module gets its data

| Data | Source | Upload |
|---|---|---|
| Work items, effort worklogs, pull requests, bugs and support | Jira · GitHub / Microsoft TFS · support desk | No upload — data comes from the live integration |
| Customer visits | Entered in Pulse, or a CSV | Yes |
| OKRs | Entered in Pulse, or a CSV | Yes |
| Users | Entered in Pulse, or a CSV | Yes |
| Attendance & leave | Monthly CSV | Yes — this is the only source |
| Holiday calendar | Yearly CSV | Yes |
| Training plans, 1:1s, 360°, extra-mile points | Pulse workflows | No upload — each item needs a reviewer, rating or sign-off |
| Culture values | Calculated in Pulse | No upload |
| Appraisal | Calculated in Pulse | Export only |

The **Data imports** screen (Administration) shows:

- a card for each import you are allowed to run, with the date of its last upload
- the exports
- the table above
- the upload history: when, data, file, by, rows, result, and Undo for imports made in this session

OKRs, Users and Customer visits also have **Import CSV / Export CSV** buttons on their own screens. Attendance has an **Upload monthly CSV** button.
