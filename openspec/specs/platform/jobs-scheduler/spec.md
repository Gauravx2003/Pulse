# jobs-scheduler Specification

## Purpose

Defines the scheduled background jobs of v1: what runs and when, how runs are recorded and how failures surface. v1 runs as a single instance with no inbound webhooks.

## Requirements

### Requirement: Scheduled jobs
The system SHALL run these jobs in-process: daily stale-signal refresh, Friday weekly-pulse prompt, Monday weekly digest, month-end attendance reminder, expiring-token check and raw-event retention purge. Each module MAY register jobs through its descriptor.

#### Scenario: Friday pulse
- **WHEN** it is Friday at the configured time
- **THEN** every active Employee without an answer for the week gets a weekly-pulse prompt

### Requirement: Manual trigger
The system SHALL let an Admin trigger any registered job on demand for demos and tests, SHALL audit the trigger, and SHALL refuse non-Admins.

#### Scenario: Trigger digest
- **WHEN** an Admin triggers the weekly digest job
- **THEN** it runs once and writes a run record

### Requirement: Run records and failure visibility
The system SHALL record each run's start, end, outcome and counts, SHALL retry a failed job with backoff up to a limit, and SHALL show a failed job and a failed sync in the Integrations screen and as an Admin item in the Action center.

#### Scenario: Failed job
- **WHEN** a job fails after its retries
- **THEN** the run record shows the failure and an Admin Do today item appears

### Requirement: No overlapping runs
The system SHALL NOT start a job while a previous run of the same job is still running.

#### Scenario: Overlap
- **WHEN** a job tick arrives while the previous run is unfinished
- **THEN** the new run is skipped and logged
