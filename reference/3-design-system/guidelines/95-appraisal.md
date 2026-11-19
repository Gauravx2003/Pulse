# Appraisal & increments

A yearly dashboard that turns the year's reviews and data into an increment proposal per person. **Visible to managers (their team) and HR/Admin (everyone) only** — employees and contributors never see it. Salary figures are confidential.

## Appraisal score (1–5)

| Part | Weight | Source |
|---|---|---|
| Performance index | 50% | Average of the last 4 quarters (delivery, quality, culture, OKRs, growth) |
| 360° feedback | 20% | Average of internal-team and cross-department ratings in the latest cycle with ≥ 2 responses |
| Manager review | 20% | Manager's ratings in the latest 360° cycle |
| Discipline | 10% | Discipline score from Jira (a–g) |

Missing parts are left out and the rest re-weighted. Weights are set by HR and must total 100%.

## Standard: score band → increment (max 20%)

| Appraisal score | Band | Increment |
|---|---|---|
| 4.5 – 5.0 | Outstanding | 15% – 20% |
| 4.0 – 4.49 | Exceeds | 10% – 15% |
| 3.5 – 3.99 | Strong | 7% – 10% |
| 3.0 – 3.49 | Meets | 4% – 7% |
| 2.5 – 2.99 | Developing | 0% – 3% |
| below 2.5 | Below expectations | 0% · improvement plan |

- **Recommended %** sits inside the band in proportion to the score (e.g. 3.58 in Strong → 7.5%), rounded to 0.5%.
- **Hard cap 20%** per person — nobody can enter more.
- **Eligibility:** 6+ months in the company; 6–11 months is pro-rated (e.g. 8 months → 8/12 of the recommendation).
- **Budget:** 12% of the team's annual payroll by default; proposals over budget cannot be submitted.
- **Distribution guide:** about 20% of a team at most in Outstanding; calibrate across managers.

## Workflow

1. **Draft** — the manager clicks *Apply recommendations*, then adjusts any proposal (inline or in the person's evidence panel). A proposal outside the band is an **exception** and needs a written reason. The manager can flag a **promotion**.
2. **Submit to HR** — blocked while any exception lacks a reason or spend exceeds budget.
3. **HR review** — HR/Admin **approves** or **sends back** each proposal (or *Approve all submitted*), and can edit standards (weights, bands, cap, budget, eligibility).
4. **Release letters** — approved increments become *Released*, effective 1 October.

The evidence panel shows the score breakdown, quarterly index trend, OKR pillar, culture values, confirmed extra-mile points, bugs in own code, proactive fixes and support tickets, and the year's 360° comments.

## Access

| Permission | Manager | Admin |
|---|:-:|:-:|
| `appraisal.manage` — see dashboard, propose increments for visible people | ✓ | ✓ |
| `appraisal.approve` — approve / send back, release letters, set standards | | ✓ |
