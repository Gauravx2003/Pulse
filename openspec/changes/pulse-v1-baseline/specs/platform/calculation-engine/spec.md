# Spec Delta

## Purpose

Defines the single calculation service that owns every formula, so a number is identical on every screen, traceable to its inputs and reproducible later.

## ADDED Requirements

### Requirement: One source of numbers
The system SHALL compute every metric, score and rating in one calculation service and SHALL serve the same value to every screen and API. No client or other service SHALL recompute a formula.

#### Scenario: Cross-screen consistency
- **WHEN** a person's utilization, stale count and Discipline are viewed on Overview, Work items, Team utilization and Culture values
- **THEN** each value is identical on all four

### Requirement: Formulas as defined in the reference
The service SHALL implement the formulas of reference spec section 12: performance index, utilization, stale and not-started logic, sprint metrics, culture value score, data parameter bands, extra-mile bonus, OKR pillar, PR standard, training rating, attendance score, office policy, punctuality, visit points, appraisal score and recommended increment, with the stated defaults.

#### Scenario: Performance index
- **WHEN** D=4, Q=3, C=4, O=3 and G=4 under default weights
- **THEN** the index is (35×4 + 15×3 + 25×4 + 15×3 + 10×4) ÷ 100 = 3.7

#### Scenario: Reference implementation as test oracle
- **WHEN** the prototype's functions for a formula and the service are given the same fixture
- **THEN** results match to the displayed precision

### Requirement: Pillar inputs in v1
In v1 the Delivery and Quality pillar scores SHALL be read from seeded per-person pillar values, the Growth pillar SHALL be the seeded value averaged with the training score once a final training rating exists, and the Culture and OKR pillars SHALL be computed by the service. The performance index SHALL combine these five values with the rule weights, normalized when the weights do not total 100.

#### Scenario: Seeded Delivery and Quality
- **WHEN** the index is calculated for a seeded person
- **THEN** Delivery and Quality are the seeded values and Culture and OKR are the calculated pillars

#### Scenario: Growth with a final training rating
- **WHEN** a person has a final training rating of 4.0 and a seeded Growth value of 3.8
- **THEN** the Growth pillar is their average, 3.9

#### Scenario: Weights not totalling 100
- **WHEN** the index weights total 110
- **THEN** the index is divided by 110 so it stays on the 1–5 scale

### Requirement: Traceable calculation payload
The service SHALL return for each culture value a payload with the value, score, formula parts (360° rating, data score, data share, bonus), rater groups, parameters (name, source, how measured, raw, display, bands, score, weight, not-applicable) and bonus items. The UI SHALL render the detail view from this payload only.

#### Scenario: Reproduce a score
- **WHEN** a user follows the numbers in the detail view
- **THEN** applying the shown formula to the shown inputs yields the shown score

### Requirement: No opportunity, no penalty
The service SHALL leave out any parameter with no data or no opportunity and re-normalize the remaining weights, SHALL never emit NaN, Infinity, null or negative zero, and SHALL return an explicit not-applicable state instead.

#### Scenario: No data for a parameter
- **WHEN** a person has no on-call shifts in the period
- **THEN** the incidents parameter is marked not applicable and the other weights are re-normalized

### Requirement: Snapshots and rule versioning
The service SHALL snapshot period metrics when a period closes and SHALL version Rules & thresholds so a snapshot, a published 360° cycle and a submitted appraisal keep the rule version they were computed with. A rule change SHALL affect live and future periods only, unless an Admin triggers a recalculation of a named closed period with a written reason.

#### Scenario: Rule edited after close
- **WHEN** an Admin changes the stale threshold after sprint S42 closed
- **THEN** S42 numbers do not change

#### Scenario: Submitted appraisal is frozen
- **WHEN** a proposal is submitted and rules change afterwards
- **THEN** its score and recommended increment stay as submitted

### Requirement: Performance and caching
The service SHALL serve a period switch from cached aggregates in under 500 ms at the 95th percentile and SHALL invalidate affected aggregates when inputs change (import, confirmation, override, rule change).

#### Scenario: Attendance upload invalidates
- **WHEN** an attendance month is committed
- **THEN** capacity, Discipline rule h and the Collaboration presence signal reflect it on the next request

### Requirement: Execution isolation
The service SHALL run heavy calculations off the request event loop so API latency of other requests is not blocked.

#### Scenario: Recalculation during traffic
- **WHEN** a full recalculation runs
- **THEN** unrelated read requests keep their latency target
