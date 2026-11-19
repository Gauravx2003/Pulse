# Spec Delta

## Purpose

Defines the training plan library, assignments to trainees with mentors, the lifecycle from check-ins to sign-off, mentoring tools and the training rating. Trainees are Employees on a plan; there is no trainee role.

## ADDED Requirements

### Requirement: Plan library
Holders of training.manage SHALL create, edit, duplicate and delete plans with name, audience, duration in weeks, description, values built, checkpoint days (14/30/60/90), check-in cadence, assessment type, pass mark (default 70%) and modules (title, type, week, hours, link). Existing assignments SHALL keep their plan version, and deletion SHALL leave assignments running.

#### Scenario: Edit plan
- **WHEN** a plan is edited
- **THEN** existing assignments continue on their old version

### Requirement: Assignment
The system SHALL assign one or more plans to one or more people with a mentor, meeting frequency, start date, end of training period (default plan length), assessment window (default 3 working days) and check-in cadence, SHALL skip people already on the plan, warn when load exceeds about 16 hours a week, and notify trainee and mentor.

#### Scenario: Already assigned
- **WHEN** a person is already on a selected plan
- **THEN** they are skipped and the result says so

### Requirement: Lifecycle order
Each assignment SHALL progress Not started, In training, Assessment, Final review, Trainee sign-off, then Completed or Not completed. A failed assessment SHALL NOT be marked Completed (422). Extend SHALL set a new end date and assessment and keep history.

#### Scenario: Failed assessment
- **WHEN** the score is below the pass mark and a manager picks Completed
- **THEN** the call returns 422

### Requirement: Check-ins
The trainee SHALL submit completed work (required), hours (required), confidence 1–5, blockers and next plan per cadence; the mentor or manager SHALL Verify or mark Needs work (comment required). States SHALL be Due, Submitted, Verified, Needs work and Missed, and late submission and resend SHALL be allowed.

#### Scenario: Needs work without comment
- **WHEN** a reviewer marks Needs work with no comment
- **THEN** the form requires a comment

### Requirement: Assessment and reviews
The mentor or manager SHALL record an assessment (date, type, score 0–100, evidence). Mentor then manager SHALL each give a 1–5 rating with strengths and improvements; the manager SHALL decide Completed, Extend or Not completed with a reason for the last two.

#### Scenario: Reason required
- **WHEN** a manager chooses Not completed without a reason
- **THEN** the save is rejected

### Requirement: Sign-off and acknowledgement
The trainee SHALL agree or disagree (comment required on disagreement), rate the training and mentor 1–5, and acknowledge each feedback item or disagree with a comment; a disagreement SHALL flag the manager; the mentor MAY reply; unacknowledged items SHALL count in the menu badge.

#### Scenario: Disagree
- **WHEN** a trainee disagrees with a final review
- **THEN** the manager is flagged and a comment is required

### Requirement: Training rating
The rating SHALL be 0.4 × assessment ÷ 20 + 0.2 × mentor + 0.2 × manager + 0.1 × verified check-ins + 0.1 × checkpoints, re-weighting missing parts and staying Provisional until assessment and both reviews exist. Bands: Outstanding 4.5 or more, Strong 3.75, Meets 3, Needs support 2.25, else Not yet. The overall rating SHALL be the plan-length-weighted average of completed plans.

#### Scenario: Provisional
- **WHEN** no manager review exists
- **THEN** the rating shows Provisional

### Requirement: Mentoring
The system SHALL give mentors KPIs, a Pending-for-you list (oldest first), trainee cards, an activity timeline with filters, Log session (date, duration, mode, topics, next steps, optional blocker notifying the manager) and trainees Ask for a session. Mentors SHALL see only their mentees' training data.

#### Scenario: Blocker raised
- **WHEN** a mentor logs a session with a blocker
- **THEN** the manager is notified and a High Do today item appears for 5 days

### Requirement: Manager views and suggestions
The system SHALL show managers a Trainees & assignments tab and a Mentors table with Send reminder, offer onboarding to joiners under 3 months without a plan, and offer plans for the three lowest culture values; employees SHALL see "Suggested from your reviews".

#### Scenario: New joiner suggestion
- **WHEN** a person joined 2 months ago and has no plan
- **THEN** onboarding is suggested with an Assign action

### Requirement: Onboarding effect
A new joiner on an active onboarding plan SHALL show Onboarding or Training in Overview and Team utilization and SHALL be excluded from utilization signals and rebalancing.

#### Scenario: Excluded from rebalance
- **WHEN** rebalance runs
- **THEN** onboarding joiners are never suggested as recipients
