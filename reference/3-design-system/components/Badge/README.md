# Badge

A pill that names a status or band in words; the dot is decoration.

**Consumer provides:** text and a `tone`: `ok`, `info`, `brand`, `accent`, `warn`, `danger`, or none (neutral).

- Jira status mapping: *To Do* → neutral · *In Progress* → `info` · *In Review* → `brand` · *Done* → `ok`.
- Signals: *Stale* → `accent` · *Overdue* / *Sync failed* → `danger` · *Over* → `warn`.
- Text is ≤ 2 words, sentence case. Never an icon-only or color-only badge.
