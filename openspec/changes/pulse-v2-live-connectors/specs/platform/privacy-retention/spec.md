# Spec Delta

## Purpose

Phase 2 addition to user deletion: what happens to a deleted person's linked tool accounts and the live rows that point to them.

## ADDED Requirements

### Requirement: External accounts on deletion
The system SHALL, when a user is deleted, unlink every tool account linked to them, clear the stored login and email of those accounts, and keep their live rows with no person so team-level figures are unchanged. The deletion dialog SHALL list this.

#### Scenario: Deleted developer with GitHub activity
- **WHEN** an Admin deletes a person whose GitHub account was linked
- **THEN** no table resolves that GitHub account to the person, their pull requests remain in team totals without a name, and they no longer appear in per-person figures
