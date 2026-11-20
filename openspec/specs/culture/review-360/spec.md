# review-360 Specification

## Purpose

Defines 360° feedback cycles, reviewer assignment, the review form, results with anonymity guarantees and publishing.

## Requirements

### Requirement: Cycle
An Admin holding users.all and review360.manage SHALL create a cycle with name, quarter (required), due date, participants, internal reviewers (3–5), cross-department reviewers (2–4) and anonymity threshold (default 3). Only one cycle SHALL run at a time.

#### Scenario: Second cycle
- **WHEN** a cycle is created while another runs
- **THEN** the call is refused

### Requirement: Assignment
A holder of review360.manage SHALL assign reviewers to one or many people from a searchable list grouped by department; the group SHALL be derived (same department internal, other department cross-department, their manager manager); self and manager reviews are included by default; self-review SHALL never be assigned to others; duplicates SHALL be skipped.

#### Scenario: Group derivation
- **WHEN** a reviewer from another department is added
- **THEN** their group is cross-department

### Requirement: Notifications and states
Every reviewer SHALL get an in-app notification opening the form; the manager SHALL see each reviewer's state (Not started, In progress, Submitted) and MAY Remind, Remove or Send reminders to all; reviewers MAY decline peer requests.

#### Scenario: Remind all
- **WHEN** the manager sends reminders
- **THEN** only reviewers not yet submitted are notified

### Requirement: Review form
Each value SHALL be rated 1–5 or Not observed with its anchor shown; an example of 10 or more characters SHALL be required for 1, 2 and 5; Keep doing and Start doing SHALL each need 15 or more characters; Discipline SHALL not be rated; drafts SHALL be saved and submitted reviews SHALL be read-only.

#### Scenario: Rating 5 without example
- **WHEN** a value is rated 5 with no example
- **THEN** submit is blocked with the requirement shown

### Requirement: Anonymity of results
The system SHALL never return peer, internal or cross-department answers with reviewer identity. A rater group with fewer than the threshold of responses SHALL be merged into Peers (combined) or hidden, enforced server-side. Comments SHALL be grouped and never attributed. Who responded SHALL stay private to subjects until publish.

#### Scenario: Group under threshold
- **WHEN** the cross-department group has 2 responses and the threshold is 3
- **THEN** its answers are merged or hidden in every API response

### Requirement: Differencing protection
Preliminary results visible to managers while a cycle runs SHALL show aggregates only for groups at or above the threshold, SHALL NOT show per-response changes, and SHALL NOT reveal which individual reviewers have submitted a given subject's review to anyone but the manager role in the assignment view.

#### Scenario: Response arrives
- **WHEN** one more response arrives for a group at threshold
- **THEN** the manager's preliminary view shows the updated aggregate without response-level detail

### Requirement: Pseudonymous reviewer storage
The system SHALL store the reviewer of each response as a pseudonymous id, with the mapping accessible only to the review service and never to reports, exports or logs.

#### Scenario: Database read by admin
- **WHEN** an Admin queries answers through any API
- **THEN** no reviewer identity is returned for anonymous groups

### Requirement: Results
The system SHALL show response counts per group, hidden strengths (others at least self + 0.75) and blind spots (self at least others + 0.75), bars per group with the weighted score and grouped comments. Managers see preliminary results while running; employees see theirs after publish.

#### Scenario: Employee before publish
- **WHEN** an Employee requests their results before publish
- **THEN** results are refused and only response progress counts are shown

### Requirement: Close and publish
An Admin SHALL close and publish a cycle, which replaces the 360° part of culture values with the published ratings, records the action in the audit log and freezes the results at that rule version.

#### Scenario: Publish
- **WHEN** a cycle is published
- **THEN** employees can view results and culture values use them

### Requirement: Training checkpoints
Training checkpoint reviews SHALL use the same form and appear in To give tagged "Training checkpoint · Day N".

#### Scenario: Checkpoint
- **WHEN** a checkpoint is requested
- **THEN** it appears in the reviewer's To give list with its tag
