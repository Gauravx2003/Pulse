# Spec Delta

## ADDED Requirements

### Requirement: Hours from work intervals
The system SHALL count a work interval as hours logged for its person, split by day: only weekdays that are not company holidays, the person's leave or customer-visit days; on each day only the time inside the working-day window; and at most `dayHours` per day. An open interval SHALL end at the issue's resolution time when it is resolved, else count up to now, and never after today. Every figure that reads hours logged SHALL read these hours like worklog hours.

#### Scenario: Interval over two working days
- **GIVEN** the window 10:00–18:00 and `dayHours` 8
- **WHEN** a Dev interval runs from Monday 14:00 to Tuesday 12:00
- **THEN** the person has 4 hours logged on Monday and 2 hours on Tuesday

#### Scenario: Weekend and holiday skipped
- **WHEN** an interval runs from Friday 10:00 to Monday 18:00 and Monday is a company holiday
- **THEN** only Friday counts, with 8 hours

#### Scenario: Open interval
- **WHEN** a Dev interval started yesterday at 10:00 and has no end yet, and today's window has 3 hours behind it
- **THEN** the person has 8 hours yesterday and 3 hours today, and nothing after today

#### Scenario: Open interval of a resolved issue
- **WHEN** a Dev interval has no end and the issue was resolved yesterday at 12:00
- **THEN** the interval counts until yesterday 12:00 and adds no hours today

#### Scenario: Dev and QA overlap for one person
- **WHEN** the same person has a Dev and a QA interval covering the same hours of a day
- **THEN** each hour counts once, and that day is still capped at `dayHours`

#### Scenario: Unmatched account
- **WHEN** an interval's Jira account is not linked to a person
- **THEN** its hours count for no person until the account is linked, and then from the next read with no re-sync

#### Scenario: Invalid interval
- **WHEN** an interval's end is before its start
- **THEN** it counts no hours
