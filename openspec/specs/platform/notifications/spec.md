# notifications Specification

## Purpose

Defines in-app notifications, menu badges and optional external copies, so people see what needs them and can act in one click.

## Requirements

### Requirement: In-app notifications
The system SHALL show a bell with an unread count; each notification SHALL open the right screen, form or tab and be marked read, and a Mark all read action SHALL clear the count.

#### Scenario: Open a 360° request
- **WHEN** a user clicks "Share 360° feedback on …"
- **THEN** the review form opens and the notification is marked read

### Requirement: Notification triggers
The system SHALL notify on: 360° review assigned or reminded; training assigned, check-in submitted, verified or needs work, checkpoint requested, assessment and final reviews, sign-off due, disagreement; mentor session logged, requested or blocked; visit planned, report due and feedback requested; low weekly pulse to the manager; attendance uploaded to managers; and stale-work nudges.

#### Scenario: Low pulse
- **WHEN** an Employee answers mood 2 or workload 5
- **THEN** their manager gets a notification and a Do today item

### Requirement: Notification privacy
Notification text SHALL NOT include salary values, 360° comment text or reviewer identity, and a user SHALL receive only notifications about data they may see.

#### Scenario: 360° reminder text
- **WHEN** a reviewer is reminded
- **THEN** the text names the subject and due date only

### Requirement: Menu badges
The system SHALL show counts on menu items for stale work items, pending effort items, visits needing action, training actions, 360° reviews waiting and Action center items, computed from the same rules as the Action center.

#### Scenario: Count matches list
- **WHEN** the Reviews badge shows 2
- **THEN** the To give list shows 2 pending reviews

### Requirement: Optional external copies
The system SHALL send email, Slack or Teams copies only when the integration is connected and the user's preference allows, SHALL link back to Pulse instead of embedding sensitive content, and SHALL send the weekly digest on Monday.

#### Scenario: Slack not connected
- **WHEN** Slack is disconnected
- **THEN** only in-app notifications are produced
