# Spec Delta

## Purpose

Defines the shared check, commit and undo pipeline that every CSV import and export uses, including parsing, limits and safety.

## ADDED Requirements

### Requirement: Three-step import
Every import SHALL follow Choose file, Check, then Commit. Nothing SHALL be saved before Commit, every committed import SHALL be logged, and the Check result SHALL report rows read, ready (new and update), skipped, errors by line and column, warnings by line, a summary and a preview of the first 12 rows.

#### Scenario: Check does not write
- **WHEN** a file is checked and the user cancels
- **THEN** no business data has changed

#### Scenario: Rows with errors
- **WHEN** 3 of 100 rows have errors
- **THEN** the result shows 97 ready, 3 errors listed by line and column, and the commit imports only the 97

### Requirement: Parsing rules
The system SHALL accept UTF-8 with or without BOM, comma, semicolon or tab separators and quoted fields; SHALL match headers case-insensitively with aliases; SHALL accept dates as YYYY-MM-DD or DD-MM-YYYY; SHALL ignore and list unknown columns; and SHALL stop with a clear message when a required column is missing.

#### Scenario: Semicolon file with BOM
- **WHEN** a UTF-8 BOM semicolon-separated file is checked
- **THEN** it parses correctly

#### Scenario: Missing required column
- **WHEN** the file lacks a required column
- **THEN** the check stops and names the column

### Requirement: Size and type limits
The system SHALL accept only CSV content up to 5,000 rows and 5 MB, SHALL reject other file types with a "Save as CSV UTF-8" tip, and SHALL validate 5,000 rows in under 3 seconds.

#### Scenario: Oversized file
- **WHEN** a 6 MB file is uploaded
- **THEN** it is rejected while streaming, before it is fully buffered

### Requirement: Check id binding and expiry
A Check SHALL return a check id bound to the user, the import type and the file hash. Commit SHALL accept only a valid, unexpired (30 minutes) check id from the same user and type, SHALL be idempotent for repeated calls, and staged data SHALL be deleted on commit, expiry or cancel.

#### Scenario: Another user commits
- **WHEN** a different user posts a commit with someone else's check id
- **THEN** it is refused as if the id did not exist

#### Scenario: Double submit
- **WHEN** the same commit is sent twice
- **THEN** the data is imported once and the second call returns the first result

### Requirement: Spreadsheet formula safety
The system SHALL neutralize cell values that begin with `=`, `+`, `-` or `@` (and tab or carriage return) in every exported CSV so spreadsheet applications do not execute them, and SHALL treat imported values as plain text everywhere they are displayed.

#### Scenario: Export of a malicious name
- **WHEN** a user name `=HYPERLINK("http://x")` is exported
- **THEN** the cell is written so it opens as text

### Requirement: Undo
The system SHALL allow the importer or an Admin to undo an import within 24 hours, restoring the exact prior state recorded at commit through stored before-images. Undo SHALL be refused with a clear message if later changes to the same records make restoring unsafe, and SHALL be logged.

#### Scenario: Undo within window
- **WHEN** the importer undoes an attendance import 2 hours later
- **THEN** the replaced months return to their prior values and dependent scores recalculate

#### Scenario: Conflicting later change
- **WHEN** an affected record was edited after the commit
- **THEN** undo is refused listing the conflicting records

### Requirement: Concurrency
The system SHALL serialize commits that touch the same import type and records, so two concurrent commits cannot interleave.

#### Scenario: Two attendance commits
- **WHEN** two commits for the same month arrive together
- **THEN** they apply one after the other and each logs its own result

### Requirement: Templates and round-trip exports
The system SHALL provide a downloadable template and an export per import type using the same column layout so an exported file can be re-imported.

#### Scenario: Round trip
- **WHEN** OKRs are exported and the file is checked unchanged
- **THEN** every row is reported as unchanged or matching update with no errors

### Requirement: Import history
The system SHALL keep a history of imports (when, data, file, by, rows, imported, errors, mode, undo state) visible to the permitted roles, newest first.

#### Scenario: History entry
- **WHEN** an import is committed
- **THEN** the history shows one row with its counts and an Undo action while undo is allowed
