# 360° reviews

Lives in **Reviews → 360° feedback** (next to *Performance review* and *1:1s & feedback*). Every reviewer is an existing Pulse user: requests arrive as **in-app notifications** and are answered inside Pulse — there is no separate login or external link.

A 360° cycle collects structured feedback on the 10 behaviour-rated DO values (Discipline is measured from Jira) from **self, manager, the internal team and other departments**. Results are grouped and anonymous, and on publish they become the rubric part of each person's culture values.

## Reviewer groups

| Group | Who | Default count | Named in results? | Weight |
|---|---|---|---|---|
| Self | The person | 1 | Yes (only to self and manager) | 20% |
| Manager | Reporting manager | 1 | Yes | 40% |
| Internal team | Same department (e.g. Engineering · Platform) | 3–5 | No — grouped | 25% |
| Cross-department | Product, QA, Customer Support, DevOps, Sales Engineering… | 2–4 | No — grouped | 15% |
| Direct reports | For people managers (optional) | 3+ | No — grouped | — |

**Anonymity rule:** an internal-team, cross-department or reports group with fewer than 3 responses (editable per cycle) is merged into *Peers (combined)*; if the merged group is still below 3 it is hidden. Weights are renormalised over the groups that responded.

## Cycle lifecycle

1. **Create** (Admin): name, quarter (required dropdown), due date, participating department, reviewer counts per group, anonymity threshold. Self and manager requests are created automatically. Only one cycle can run at a time.
2. **Assignment**: the manager assigns reviewers for one or many people at once (see below); each reviewer is notified in Pulse.
3. **In progress**: approved reviewers get a request (email + Slack), with reminders from the manager or Admin. Reviewers can decline a peer or cross-department request.
4. **Close & publish** (Admin): open reviews close, each person sees their grouped results, and the weighted 360 averages replace the rubric part of their 10 culture values (the culture page then lists the groups as raters).

## The review form

- Each value: its description, a rating **1–5** or **Not observed**, and the behaviour anchor for the chosen rating shown underneath.
- An example (10+ characters) is **required** for ratings of 1, 2 or 5.
- Two required open questions (15+ characters): *What should they keep doing?* and *What should they start or do differently?*
- **Save draft** and **Submit**; submitted reviews are read-only.

## Results

- Response count per group, and whether each group is shown or merged.
- **Hidden strengths** (others ≥ self + 0.75) and **blind spots** (self ≥ others + 0.75).
- Per value: bars for each group plus the weighted score.
- Comments grouped by reviewer group, never attributed to internal-team or cross-department reviewers.
- Managers see preliminary results for their reports while the cycle runs; employees see theirs only after publish.

## Access

| Permission | Admin | Manager | Employee |
|---|:-:|:-:|:-:|
| `review360.give` — answer review requests in Pulse | ✓ | ✓ | ✓ |
| `review360.manage` — approve nominations, track progress, see team results | ✓ | ✓ | |
| Run cycles (`review360.manage` + `users.all`) | ✓ | | |


## Assigning reviews and notifications

- The **manager** (or HR) clicks **Assign 360° review** (Reviews → 360° feedback → Team), picks **one or many people to be reviewed**, and **any number of reviewers** from the user list (searchable, grouped by department). Self-review and manager review are included by default.
- The reviewer group is set automatically: same department → *Internal team*, other department → *Cross-department*, the person's manager → *Manager*. Nobody reviews themselves; duplicates are skipped.
- Each reviewer immediately gets an **in-app notification** (bell icon in the top bar with an unread count). Clicking it opens the review form directly. The *Reviews* menu item and the *360° feedback* tab also show how many reviews are waiting.
- The manager sees every assigned reviewer's state (*Not started*, *In progress*, *Submitted*) and can **Remind** (new notification) or **Remove** a reviewer. *Send reminders* notifies everyone with an open review.
- Colleagues from other departments are ordinary Pulse users with the **Employee** role. Without a performance profile of their own they only see **Reviews → 360° feedback**.
- Email / Slack copies of notifications are optional and can be added through the Slack / Teams integration.
