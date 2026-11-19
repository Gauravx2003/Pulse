# OKRs

The **OKRs** screen lets people create, update and delete objectives with measurable key results, per quarter, for themselves or for the team.

## Structure

- **Objective** — qualitative outcome, one sentence. Level: *Individual* or *Team*. Optional linked Jira epic and a culture value it grows.
- **Key results** — 1 to 5 per objective, each with `start`, `target`, `current` and a unit. Targets can go down (e.g. latency 900 → 400 ms).
- **KR progress** = (current − start) ÷ (target − start), clamped 0–1. **Objective progress** = average of its KRs.
- **Status**: ≥ 70% *On track* (`ok`), 40–69% *At risk* (`accent`), < 40% *Off track* (`danger`).
- **Goals / OKRs pillar** = 1 + progress ÷ 0.7 × 3, capped at 5 (0.7 progress ≈ 4.0). Uses the current quarter's individual OKRs; falls back to the manager's rating when a person has none.

## Quarter

- The OKR screen has a **Quarter** dropdown (Q1 2026 – Q1 2027); each option shows how many objectives it holds.
- **Quarter is required** when creating or editing an objective — the form starts on *Select quarter…* and will not save without one.

## Actions

| Action | Where | Who |
|---|---|---|
| Create objective | *New objective* button | Own: `okr.own` · for someone else or Team: `okr.team` |
| Edit objective and KRs | *Edit* on the card (add/remove KRs in the form) | Same as create |
| Check-in (update current value) | *Check-in* on a KR row | Same as create |
| Delete objective | *Delete* → confirmation dialog | Same as create |

Validation: objective text and quarter required, every KR needs a description, target must differ from start.

## Import and export

**Import CSV** on the OKRs screen adds objectives in bulk or updates matching ones; a new *current* value counts as a check-in for the whole team at once. Use one row per key result. **Export CSV** writes the quarter in the same layout. See *Data imports* for columns and checks.
