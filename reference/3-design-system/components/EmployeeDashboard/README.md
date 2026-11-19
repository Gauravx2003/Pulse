# EmployeeDashboard

The employee overview screen: KPIs, overload alert, current Jira sprint work, culture values, road map, performance index and integrations.

**Layout:** 220px side nav + fluid main; `space-6` page padding, `space-4` gutters, cards `radius-lg`.

Order of content, top to bottom — keep it:
1. Identity row (avatar, name, role, manager) + period tabs + primary action *Start 1:1*.
2. Five StatTiles: Tasks assigned · Not updated 2+ days · Work assigned (days) · Utilization · Performance index.
3. One Alert only when the band is Over/Critical or > 30% of open tasks are stale, with a concrete suggestion.
4. SprintTaskTable.
5. CultureValues + RoadmapTimeline side by side.
6. Performance index breakdown (weights visible).
7. Integrations strip.

Visible to: the employee, their manager, HR. Peers never see another person's dashboard.
