# Spec Delta

## Purpose

Defines the application shell and cross-screen UI behaviour: navigation, theming with design tokens, responsive layout, accessibility, empty states and feedback patterns.

## ADDED Requirements

### Requirement: Shell and navigation
The system SHALL provide a side menu with groups My work / People, Manager and Administration, showing only items the user's permissions allow, and a top bar with breadcrumb, person selector (Managers and Admin on person pages), sync status, bell, theme toggle and Sign out.

#### Scenario: Manager menu
- **WHEN** a Manager signs in
- **THEN** the menu shows Action center, Team utilization and Appraisal under Manager and omits Administration items

### Requirement: No-access page
The system SHALL show a "You don't have access" card, never an error screen, when a route is opened without permission.

#### Scenario: Employee opens Appraisal
- **WHEN** an Employee navigates directly to the Appraisal route
- **THEN** the card is shown and no appraisal request succeeds

### Requirement: Design tokens and theming
The UI SHALL use the design-system tokens for color, spacing, radius and type, support light and dark themes with no hard-coded colors, and remember the theme per browser.

#### Scenario: Dark theme
- **WHEN** the user toggles the theme
- **THEN** every screen renders with dark tokens and keeps text contrast of at least 4.5:1

### Requirement: Responsive layout
The UI SHALL render at 390, 1024 and 1440 px without horizontal page scroll, collapse the menu behind a button on phones, wrap KPI tiles two per row and scroll tables inside their card.

#### Scenario: Phone width
- **WHEN** a screen is opened at 390 px
- **THEN** the page does not scroll horizontally

### Requirement: Accessibility
The UI SHALL meet WCAG 2.2 AA: a 2 px focus ring on controls, status shown as a word with color second, keyboard-operable modals with focus trap and Esc to close, labelled icons, and chart values available as text.

#### Scenario: Modal keyboard use
- **WHEN** a modal is open
- **THEN** focus stays inside it, Esc closes it and focus returns to the opener

### Requirement: Empty and loading states
Every list, card and chart SHALL have an empty state, SHALL show a loading state while fetching, and SHALL NOT display NaN, undefined, null, Infinity or blank cards.

#### Scenario: Person without data
- **WHEN** a new joiner has no sprint history
- **THEN** cards show a clear empty message and days before joining show "—"

### Requirement: Feedback patterns
The UI SHALL confirm every save with a toast, show inline form errors, require confirmation for destructive actions and show a clear message on 401, 403, 409 and 429 responses.

#### Scenario: Session expired
- **WHEN** a request returns 401
- **THEN** the user is taken to sign in and returns to the page after authenticating

### Requirement: Sign-in screen
The system SHALL show the sign-in card with Pulse logo, email and password fields, the exact error lines of the auth spec, and demo-account cards (Admin, Manager, Employee) only when `PULSE_DEMO_SEED` is on.

#### Scenario: Enter submits
- **WHEN** the user presses Enter in the password field
- **THEN** the form submits
