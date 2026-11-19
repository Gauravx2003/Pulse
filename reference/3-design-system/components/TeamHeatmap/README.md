# TeamHeatmap

A person × working-day grid of utilization for managers, showing who is overloaded, idle or on leave.

**Consumer provides:** people with a value per day (percent, `0` = leave), day labels.

- Cells use the band grounds (`info-soft`, `ok-soft`, `accent-soft`, `warn-soft`, `danger`) **and print the value** — color alone never carries meaning.
- Leave cells read "Leave" on `surface-sunken`.
- The Avg column averages working days only.
- Clicking a row opens that person's SprintTaskTable.
