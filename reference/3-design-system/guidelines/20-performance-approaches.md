# Measuring overall performance

Utilization says how loaded someone is, not how well they perform. Pulse combines several lenses into a **Performance index (1–5)** with weights set by HR and shown on every dashboard.

## Default index

| Pillar | Weight | Inputs |
|---|---|---|
| Delivery | 35% | Sprint commitment met %, median cycle time vs team, carry-over, predictability (planned vs done across 3 sprints) |
| Quality | 15% | Reopen rate, escaped bugs linked to the person's issues, PR review findings, incident count caused |
| Culture values | 25% | Weighted average of the 11 DO values (360 rubric + Discipline from Jira) |
| Goals / OKRs | 15% | Key-result attainment for the quarter |
| Growth | 10% | Road-map milestone progress, skills added, mentoring done |

Each pillar is normalised to 1–5 against the person's **own role level and team**, never against the whole company.

## Other approaches worth adding

1. **OKRs / goal attainment** — quarterly objectives with 2–4 measurable key results; score 0.0–1.0, 0.7 = good. Links Jira epics to KRs so progress updates itself.
2. **360° feedback** — self, manager, 3–5 peers, and reports for leads. Runs quarterly on the culture rubric plus two open questions ("What should they keep doing / start doing?").
3. **Continuous feedback & kudos** — Slack/Teams `/kudos @name #value` messages tagged with one of the 11 values; becomes evidence, not a score.
4. **Structured 1:1s** — fortnightly template (wins, blockers, workload check, growth), notes stored against the road map.
5. **Engineering flow (DORA / SPACE)** — for teams: deployment frequency, lead time for changes, change failure rate, time to restore; plus satisfaction and collaboration from pulse surveys. Use at team level only.
6. **Code-review contribution** — reviews given, review turnaround time, and depth (comments that led to changes). Counts helping others, which pure task counts miss.
7. **Customer / stakeholder impact** — support tickets resolved, CSAT on delivered features, internal stakeholder rating per quarter.
8. **Competency matrix by level** — role-specific skills (technical, communication, ownership) rated per level; drives the road map and promotion readiness.
9. **Calibration** — managers review ratings together each half-year to remove bias before final ratings.
10. **Engagement & wellbeing pulse** — 5-question monthly survey; sustained Over/Critical utilization plus low pulse score triggers a workload conversation.

## Guardrails

- Never rank people on raw activity (commits, hours logged, tickets closed). These are context for conversation, not scores.
- Show every input to the employee; allow them to add context to any flagged item.
- Minimum data rule: a pillar with too little data (< 2 sprints, < 3 raters) shows "Not enough data" instead of a number.
- Utilization above 100% for 2+ sprints is a **manager** action item, not a performance problem of the employee.
