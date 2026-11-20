# api-conventions Specification

## Purpose

Defines conventions shared by all REST endpoints so clients behave predictably: versioning, identifiers, errors, pagination, concurrency and period parameters.

## Requirements

### Requirement: Versioned JSON API
The system SHALL expose a JSON REST API under `/api/v1` and SHALL require the period key of the period model on endpoints that depend on a period.

#### Scenario: Invalid period key
- **WHEN** a request carries `period=banana`
- **THEN** the API returns 400 naming the parameter

### Requirement: Opaque identifiers
The system SHALL identify records with random UUIDs, never sequential ids, in URLs and payloads.

#### Scenario: Enumeration
- **WHEN** a caller increments an id in a URL
- **THEN** no other record is addressed and the response is 403 or 404

### Requirement: Error contract
The system SHALL return 400 for validation (field and message), 401 unauthenticated, 403 permission, 404 not found, 409 version conflict, 422 business-rule violations and 429 rate limit, each with a stable machine code and a human message.

#### Scenario: Business rule
- **WHEN** a failed assessment is marked Completed
- **THEN** the API returns 422 with a code naming the rule

### Requirement: Optimistic concurrency
The system SHALL give editable records a version and SHALL reject a write based on a stale version with 409 and the current version, without overwriting.

#### Scenario: Two editors
- **WHEN** two managers edit the same proposal from the same version
- **THEN** the second save returns 409

### Requirement: Pagination, sorting and filtering
The system SHALL paginate every list endpoint with a default and maximum page size, SHALL accept documented sort and filter parameters only, and SHALL cap export size.

#### Scenario: Page size above maximum
- **WHEN** a client requests 10,000 rows per page
- **THEN** the maximum page size is applied and indicated in the response

### Requirement: Session and menu bootstrap
The system SHALL provide `GET /me` returning the user, role, permissions and the menu generated from them, and the UI SHALL derive navigation only from this response.

#### Scenario: Menu by role
- **WHEN** an Employee signs in
- **THEN** the menu excludes Manager and Administration groups
