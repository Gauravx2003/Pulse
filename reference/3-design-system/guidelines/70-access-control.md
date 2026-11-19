# Sign-in, users and role-based access

## Sign-in

Every user signs in with work email and password (SSO with Google / Microsoft / Okta is the production target). Wrong credentials show "Email or password is incorrect."; deactivated accounts cannot sign in. After sign-in the user lands on the first page their role allows. *Sign out* is in the top bar and the side nav.

## Roles and permissions

The side menu, every page and every button are shown from permissions. **Reviews** is one menu item whose tabs (Performance review · 1:1s & feedback · 360° feedback) each follow their own permission. Opening a page without permission shows an "You don't have access" card.

| Permission | Admin | Manager | Employee | Unlocks |
|---|:-:|:-:|:-:|---|
| `self.view` | ✓ | ✓ | ✓ | Overview, Work items, Effort, Road map, Culture values, Reviews (own) |
| `team.view` | ✓ | ✓ | | Direct reports in every person page, Team utilization, team tab of Effort |
| `all.view` | ✓ | | | Everyone in the organisation |
| `okr.own` | ✓ | ✓ | ✓ | Create / edit / delete own OKRs |
| `okr.team` | ✓ | ✓ | | Team OKRs and OKRs of visible people (and their CSV import) |
| `attendance.upload` | ✓ | | | Upload monthly attendance and the holiday calendar |
| `oneonone` | ✓ | ✓ | ✓ | Reviews → 1:1s & feedback tab, kudos |
| `effort.confirm` | ✓ | ✓ | | Confirm / reject extra-mile points |
| `reviews.calibrate` | ✓ | ✓ | | Submit reviews, calibration table |
| `users.team` | ✓ | ✓ | | Add, edit, delete **Employees** who report to them |
| `users.all` | ✓ | | | Manage every user, assign any role and manager |
| `roles.manage` | ✓ | | | Roles & permissions matrix, custom roles |
| `integrations.manage` | ✓ | | | Integrations |
| `rules.manage` | ✓ | | | Rules & thresholds |
| `review360.give` | ✓ | ✓ | ✓ | Reviews → 360° feedback tab: answer review requests |
| `training.manage` | ✓ | ✓ | | Training plans: library CRUD, assign, request checkpoint reviews |
| `appraisal.manage` | ✓ | ✓ | | Appraisal & increments dashboard for visible people; propose increments |
| `appraisal.approve` | ✓ | | | Approve increments, release letters, set appraisal standards |
| `review360.manage` | ✓ | ✓ | | Approve 360° nominations, track progress, team results; with `users.all`, run cycles |

- Admin always keeps every permission. Built-in roles can be edited; custom roles (e.g. *Team Lead*) are created from an existing role and can be deleted when no user has them.
- A person's data is visible to: the person, their manager chain (`team.view`), and `all.view`. Peers never see each other's pages.

## Users

- **Import CSV** — bulk create and update users from a file (see *Data imports*). Managers can only add Employees to their own team; trainees and interns are Employees.

- **Add user** — name, work email (unique, validated), role, reports-to, job title, level, temporary password. New users start as *Invited* and become *Active* on first sign-in. Employees get a performance profile automatically.
- **Edit** — name, role, manager, title, level, status (*Active*, *Invited*, *Deactivated*).
- **Delete** — confirmation dialog; removes the login, profile, OKRs and extra-work records. Prefer *Deactivate* to keep history.
- Managers only see and manage Employees who report to them and cannot grant Manager or Admin. Nobody can delete themselves.

There are exactly **three roles: Admin, Manager and Employee**. Colleagues in other departments are Employees too; assigned 360° reviews reach them as in-app notifications (bell icon) — e.g. manager A assigns a 360° review of B to employee C, C signs in, opens the notification and submits. Users have a **department**, used to split internal-team and cross-department reviewers.

Demo accounts (password `demo123`): `hr@pulse.demo` Admin · `rahul@pulse.demo` Manager · `priya@pulse.demo` Employee (also `arjun@`, `neha@`, `kiran@`, `dev@`, `sara@`, new joiners on a training plan `rohit@`, `anjali@`, account manager `sana@`). The sign-in screen lists only the three roles. A **trainee is an Employee** — there is no trainee role or trainee login; having an active training plan is what makes someone a trainee.
