# Attendance and leave

HR uploads one **attendance file a month** from the HR system or biometric export. Pulse turns it into a day-by-day record of office, WFH, on-duty and leave days. The record feeds capacity and two culture values. Nobody types attendance by hand.

## The record

| Status | Meaning | Counts as |
|---|---|---|
| Office | Badge-in at the office | Office day · check-in time used for punctuality |
| WFH | Worked from home | WFH day · log-in time used for punctuality |
| On duty | On a customer site (customer visits planned in Pulse are shown as On duty automatically) | Office day for the hybrid policy |
| Leave | Casual, Sick, Earned, Comp off, Emergency | Leave · planned or short notice from *applied on* |
| Half day | Half leave | 0.5 leave day |
| Absent | Not at work and no leave applied | Unplanned · absent without leave |

**Planned vs short notice.** Leave applied at least **2 days** ahead (**7** for earned leave) is planned; anything later is short notice. **Sick and emergency leave are always excused**: they never count against anyone.

## Attendance score (per person, any period)

| Part | Formula |
|---|---|
| Leave with notice | planned leave days ÷ leave days that needed notice (sick and emergency leave are left out) |
| Office policy | office + on-site days ÷ days required by the hybrid policy (3 a week, reduced in weeks with leave or holidays) |
| Punctuality | check-ins on time ÷ all check-ins, after an allowance of 3 late days a month (late = after 10:30) |

Score = the average of the parts that have data. Good ≥ 85% · Watch 70–84% · Talk < 70%.

## How it feeds culture values

- **Discipline, rule h — Attendance & leave.** Uses the Q3 attendance score: below 85% → −0.25, below 70% → −0.5, and each day absent without leave → −0.25 more. As with the other rules, Discipline never drops more than 1 below the Jira base band.
- **Collaboration — "Office and on-site presence"** data signal, weighted 15% within the data signals. Bands: 5 at ≥ 95% · 4 at ≥ 85% · 3 at ≥ 70% · 2 at ≥ 55%.
- **Capacity.** Leave days come out of capacity in Effort, Team utilization and the daily-update denominator (rule a), so leave never looks like under-utilization or a missed update.
- Moral & Ethics never uses attendance. HR can switch attendance off for culture values in *Rules & thresholds → Attendance & leave*. The same card holds the office days, notice days, late time and late allowance.

## Screen

**Attendance** (People menu). Use the period dropdown to pick a month, a sprint or a quarter.

- **Team** tab (managers, Admin): office policy, leave, unplanned leave, late check-ins, average day · people table with a Good / Watch / Talk status · a day-by-day grid · monthly upload status.
- **Person** tab (own for employees): a calendar with codes O W D L S ½ A, a late-check-in dot, and a hover tooltip showing times and leave details · how the score is built · the leave list (type, applied date, notice, how it counted) · late check-ins · a month-by-month table · how attendance feeds Discipline and Collaboration.
- A banner shows when a month in the period has not been uploaded yet.

## Upload (Admin with `attendance.upload`)

Columns: `employee_email`, `date`, `status` (required) · `leave_type`, `applied_on`, `check_in`, `check_out`. **Replace** mode clears the months in the file for the people in it, then loads the file (use this for the monthly upload). **Merge** mode updates only the listed days (use it for corrections). See *Data imports* for the checks.

**Action center:**

- HR gets *“<month> attendance not uploaded”* for missing months, and a reminder near month end.
- Managers get a low-priority item for anyone whose last month shows an absence, 2+ days of short-notice leave, office policy under 70%, or 3+ late check-ins over the allowance. Each item is framed as a 1:1 conversation, never as a rating.
