# security-baseline Specification

## Purpose

Defines the baseline protections every request and page must have: transport, browser hardening, injection and request-forgery defences, rate limiting and secure development controls.

## Requirements

### Requirement: Transport security
The system SHALL serve all traffic over TLS 1.2 or higher, SHALL send HSTS with a long max-age, and SHALL redirect plain HTTP to HTTPS.

#### Scenario: Plain HTTP request
- **WHEN** a client requests the site over HTTP
- **THEN** it is redirected to HTTPS and no data is served over HTTP

### Requirement: Browser hardening headers
The system SHALL send a Content-Security-Policy without `unsafe-inline` or `unsafe-eval` for scripts, X-Content-Type-Options nosniff, a restrictive Referrer-Policy, frame-ancestors none, and a Permissions-Policy that disables unused features.

#### Scenario: Injected inline script
- **WHEN** an attacker-controlled string containing a script tag is stored and rendered
- **THEN** it is shown as text and the browser blocks any inline execution

### Requirement: Output encoding of user content
The system SHALL render all user-supplied text (kudos, notes, comments, OKR text, CSV-imported fields) as text, never as HTML, and SHALL not use raw HTML rendering for user content.

#### Scenario: HTML in a kudos message
- **WHEN** a kudos message contains `<img src=x onerror=...>`
- **THEN** the literal text is shown and nothing executes

### Requirement: Request forgery defence
The system SHALL reject state-changing requests that lack a valid anti-CSRF token or whose Origin does not match the application origin, and SHALL restrict CORS to the application origin.

#### Scenario: Cross-site form post
- **WHEN** another origin posts a form to a state-changing endpoint using the user's cookie
- **THEN** the request is rejected with 403

### Requirement: Input validation
The system SHALL validate every request body, query and path parameter against a schema on the server, reject unknown fields on writes, bound string lengths and collection sizes, and use parameterised queries for all database access.

#### Scenario: Oversized or unknown field
- **WHEN** a write includes an unknown field or a string beyond its limit
- **THEN** the API returns 400 naming the field

### Requirement: Rate limiting and payload limits
The system SHALL rate-limit all endpoints per user and per IP with stricter limits for sign-in, password reset, imports, exports and webhooks, and SHALL enforce body size limits (5 MB for CSV uploads, smaller elsewhere) while streaming, not after buffering.

#### Scenario: Import flood
- **WHEN** a user uploads CSV files faster than the import limit
- **THEN** further uploads return 429 with a retry-after value

### Requirement: Safe errors and logging
The system SHALL return generic error messages without stack traces or SQL, SHALL redact passwords, tokens, salary values and free-text review content from logs, and SHALL tag logs with a request id.

#### Scenario: Server error
- **WHEN** an unexpected exception occurs
- **THEN** the client receives a generic 500 with a request id and the log holds the detail without redacted fields

### Requirement: Secure development controls
The build SHALL run dependency vulnerability scanning, secret scanning and static analysis on every change and SHALL fail on high-severity findings without a recorded exception.

#### Scenario: Vulnerable dependency
- **WHEN** a change adds a dependency with a known high-severity vulnerability
- **THEN** the pipeline fails
