# StatTile

One headline number with its label, unit and a one-line footnote that explains it.

**Consumer provides:** `eyebrow` label (uppercase, ≤ 28 chars), `value` (number, already rounded), optional `unit` (`%`, `d`, `h`, `/ N`), a `foot` sentence, and optionally a band badge.

- Use four across on the employee overview: *Tasks assigned*, *Not updated 2+ days*, *Work assigned (days)*, *Utilization*.
- Value is set in `metric` (IBM Plex Mono); unit in `ink-muted` at half size.
- Put the band in a `Badge` with its word (`Under`, `Healthy`, `At capacity`, `Over`, `Critical`) — never color the number itself.
- Footnote says **what the number is compared against** ("vs 9.0 d capacity left"). A number without a reference point is not allowed.
- Don't show deltas as green/red arrows alone; write "+12 pts vs last sprint".
