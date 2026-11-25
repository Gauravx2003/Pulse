package com.pulse.calc.work;

import com.pulse.calc.core.CoreThresholds;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The Action center's calculations (task 13.2, spec insight/action-center), ported from the prototype's
 * {@code forecastRows}, {@code velocity}, {@code capRow}, {@code signals}, {@code riskLevel},
 * {@code coverage} and {@code pulseTrend}. Pure functions over inputs that {@link ActionCenterFormulas}
 * gathers; thresholds come from the rules.
 */
public final class ActionCenter
{

    /**
     * Mentoring time a mentor gives each active mentee, in hours a week (prototype {@code capRow}).
     */
    public static final double MENTOR_HOURS_PER_WEEK = 1.5;

    /**
     * A training plan with at least this many overdue modules raises "Training behind".
     */
    public static final int TRAINING_BEHIND_MODULES = 2;

    /**
     * A 1:1 older than this many working days raises "1:1 overdue".
     */
    public static final int ONE_ON_ONE_DAYS = 10;

    /**
     * A weekly pulse answer is low with mood at or below this, or workload at or above
     * {@link #HIGH_WORKLOAD}.
     */
    public static final int LOW_MOOD = 2;

    public static final int HIGH_WORKLOAD = 5;

    private static final String NO_DUE = "9";

    private ActionCenter()
    {
    }

    // ------------------------------------------------------------------ sprint forecast

    /**
     * One person's sprint forecast from {@code work.liveSprint}: open issues fill the focus hours left in
     * due-date order (no due date last); what does not fit is likely to carry over.
     * {@code {remaining, capacity, fits, pct, tone, carry: [{key, rem}]}}; {@code tone} is the meter's
     * ({@link WorkThresholds#FORECAST_FIT_TONES}).
     */
    public static JsonObject forecast(JsonObject live, double focus)
    {
        double capacity = live.getInteger("cap", 0) * focus;

        double remaining = live.getDouble("rem", 0.0);

        List<JsonObject> open = new ArrayList<>();

        live.getJsonArray("openIssues", new JsonArray()).forEach(o -> open.add((JsonObject) o));

        open.sort(Comparator.comparing(o -> o.getString("due") == null ? NO_DUE : o.getString("due")));

        JsonArray carry = new JsonArray();

        double filled = 0;

        for (JsonObject issue : open)
        {
            double rem = issue.getDouble("rem", 0.0);

            filled += rem;

            if (filled > capacity)
            {
                carry.add(new JsonObject().put("key", issue.getString("key")).put("rem", rem));
            }
        }

        int pct = remaining > 0 ? (int) Math.min(100, Math.round(capacity / remaining * 100)) : 100;

        return new JsonObject()
                .put("remaining", remaining)
                .put("capacity", capacity)
                .put("fits", Math.min(remaining, capacity))
                .put("pct", pct)
                .put("tone", CoreThresholds.tone(pct, WorkThresholds.FORECAST_FIT_TONES))
                .put("carry", carry);
    }

    // ------------------------------------------------------------------ next-sprint capacity

    /**
     * Velocity: done story points per working day, averaged over the given sprints
     * ({@code [donePoints, workingDays]} each; a sprint with no working day counts one day).
     */
    public static double velocity(List<double[]> sprints)
    {
        if (sprints.isEmpty())
        {
            return 0;
        }

        double sum = 0;

        for (double[] sprint : sprints)
        {
            sum += sprint[0] / Math.max(1, sprint[1]);
        }

        return sum / sprints.size();
    }

    /**
     * One person's next-sprint capacity (prototype {@code capRow}): working days less leave and planned
     * visits, less training and mentoring hours as days of focus, times velocity.
     *
     * @param company      the company working days (weekends and holidays off)
     * @param leave        the person's full leave days
     * @param visits       the person's visits {@code [{from, to}]}
     * @param training     {@code [{hoursPerWeek, end}]} of the person's active training plans
     * @param mentees      active mentees the person mentors
     * @param newJoiner    a new joiner gets no SP suggestion
     */
    public static JsonObject capacity(LocalDate from, LocalDate end, Predicate<LocalDate> company, Set<LocalDate> leave,
                                      JsonArray visits, JsonArray training, int mentees, double velocity, double focus,
                                      boolean newJoiner)
    {
        int working = 0;

        int holidays = 0;

        int weekdays = 0;

        int leaveDays = 0;

        int visitDays = 0;

        for (LocalDate day = from; !day.isAfter(end); day = day.plusDays(1))
        {
            if (day.getDayOfWeek() == DayOfWeek.SATURDAY || day.getDayOfWeek() == DayOfWeek.SUNDAY)
            {
                continue;
            }

            weekdays++;

            if (!company.test(day))
            {
                holidays++;

                continue;
            }

            working++;

            // counted separately, as the prototype does: a day on leave and on a visit counts in both
            if (leave.contains(day))
            {
                leaveDays++;
            }

            if (onVisit(visits, day))
            {
                visitDays++;
            }
        }

        double weeks = weekdays / 5.0;

        double trainingHours = 0;

        for (Object o : training == null ? new JsonArray() : training)
        {
            JsonObject plan = (JsonObject) o;

            String planEnd = plan.getString("end");

            if (planEnd == null || !LocalDate.parse(planEnd).isBefore(from))
            {
                trainingHours += plan.getDouble("hoursPerWeek", 0.0) * weeks;
            }
        }

        double mentoringHours = mentees * MENTOR_HOURS_PER_WEEK * weeks;

        double focusDays = Math.max(0, working - leaveDays - visitDays - (trainingHours + mentoringHours) / focus);

        return new JsonObject()
                .put("days", working + holidays)
                .put("holidays", holidays)
                .put("leave", leaveDays)
                .put("visits", visitDays)
                .put("trainingHours", Math.round(trainingHours))
                .put("mentoringHours", mentoringHours)
                .put("focusDays", Math.round(focusDays * 10) / 10.0)
                .put("velocity", Math.round(velocity * 100) / 100.0)
                .put("suggestedPoints", newJoiner ? null : (Object) Math.round(focusDays * velocity))
                .put("newJoiner", newJoiner);
    }

    private static boolean onVisit(JsonArray visits, LocalDate day)
    {
        for (Object o : visits == null ? new JsonArray() : visits)
        {
            JsonObject v = (JsonObject) o;

            if (!day.isBefore(LocalDate.parse(v.getString("from"))) && !day.isAfter(LocalDate.parse(v.getString("to"))))
            {
                return true;
            }
        }

        return false;
    }

    // ------------------------------------------------------------------ people signals

    /**
     * The people signals of one person (spec "Signal set"), each {@code {tone, label, reason, next}}:
     * conversation prompts, never a rating. Absent inputs (a port not registered, no pulse, no index
     * history) raise nothing. New joiners are never flagged for utilization or recognition.
     *
     * <p>{@code in}: {@code util} (live sprint), {@code closed} (utilization of the last 3 closed sprints,
     * newest first), {@code newJoiner}, {@code onboarding}, {@code weekend} and {@code share} (this month),
     * {@code discipline}, {@code lastOneOnOne} with {@code oneOnOneDays}, {@code pulses} (oldest first),
     * {@code indexPrevious} and {@code indexNow}, {@code kudos}, {@code trainingBehind}
     * ({@code [{plan, overdue}]}) and {@code nextSprint} (its name).
     */
    public static JsonArray signals(JsonObject in, JsonObject rules)
    {
        JsonArray out = new JsonArray();

        boolean newJoiner = in.getBoolean("newJoiner", false);

        Integer util = in.getInteger("util");

        JsonArray closed = in.getJsonArray("closed", new JsonArray());

        int overRule = rules.getInteger("over", 120);

        int underRule = rules.getInteger("under", 60);

        int over = (int) closed.stream().filter(u -> u instanceof Number n && n.doubleValue() > 100).count()
                + (util != null && util > overRule ? 1 : 0);

        if (over >= 2 && !newJoiner)
        {
            out.add(signal("danger", "Sustained overload",
                    "Over 100% in " + over + " of the last 4 sprints (now " + util + "%)",
                    "Move work or push scope to " + in.getString("nextSprint", "the next sprint")));
        }

        int under = (int) closed.stream().filter(u -> u instanceof Number n && n.doubleValue() < underRule).count();

        if (under >= 2 && !newJoiner && !in.getBoolean("onboarding", false))
        {
            out.add(signal("info", "Under-used", "Below " + underRule + "% in " + under + " of the last 3 sprints",
                    "Pull backlog items or pair on a critical area"));
        }

        int weekend = in.getInteger("weekend", 0);

        if (weekend >= 1 && util != null && util > 100)
        {
            out.add(signal("warn", "Weekend work while overloaded", weekend + " weekend day(s) this month",
                    "Give time back; check on-call load"));
        }

        Integer share = in.getInteger("share");

        if (share != null && share >= rules.getInteger("unplannedAlert", 30))
        {
            out.add(signal("accent", "High unplanned work", share + "% of logged time was unplanned",
                    "Protect focus: route requests through you"));
        }

        Number discipline = in.getNumber("discipline");

        if (discipline != null && discipline.doubleValue() < 3)
        {
            out.add(signal("accent", "Jira updates lagging", "Discipline " + one(discipline.doubleValue()) + " / 5",
                    "Suggest the Jira discipline refresher"));
        }

        Integer oneOnOneDays = in.getInteger("oneOnOneDays");

        if (in.getString("lastOneOnOne") != null && oneOnOneDays != null && oneOnOneDays > ONE_ON_ONE_DAYS)
        {
            out.add(signal("accent", "1:1 overdue", "Last 1:1 " + in.getString("lastOneOnOneLabel", in.getString("lastOneOnOne"))
                    + " (" + oneOnOneDays + " working days ago)", "Book a 1:1 this week"));
        }

        JsonObject pulse = pulseSignal(in.getJsonArray("pulses", new JsonArray()));

        if (pulse != null)
        {
            out.add(pulse);
        }

        Number previous = in.getNumber("indexPrevious");

        Number now = in.getNumber("indexNow");

        if (previous != null && now != null && now.doubleValue() < previous.doubleValue() - 0.1)
        {
            out.add(signal("accent", "Performance index falling", one(previous.doubleValue()) + " → " + one(now.doubleValue()),
                    "Look at the breakdown together"));
        }

        Integer kudos = in.getInteger("kudos");

        if (kudos != null && kudos == 0 && !newJoiner)
        {
            out.add(signal("", "No recognition recently", "No kudos this quarter", "Recognise a recent win"));
        }

        for (Object o : in.getJsonArray("trainingBehind", new JsonArray()))
        {
            JsonObject plan = (JsonObject) o;

            int overdue = plan.getInteger("overdue", 0);

            if (overdue >= TRAINING_BEHIND_MODULES)
            {
                out.add(signal("accent", "Training behind", plan.getString("plan") + ": " + overdue + " modules overdue",
                        "Agree time for training this sprint"));
            }
        }

        return out;
    }

    /**
     * "Pulse check-in low" for a last answer with mood 2 or lower or workload 5, else "Mood falling" when
     * mood dropped over the last 3 answers; null otherwise. {@code pulses} oldest first.
     */
    static JsonObject pulseSignal(JsonArray pulses)
    {
        if (pulses.isEmpty())
        {
            return null;
        }

        JsonObject last = pulses.getJsonObject(pulses.size() - 1);

        int mood = last.getInteger("mood");

        int workload = last.getInteger("workload");

        if (low(last))
        {
            String note = last.getString("note", "");

            return signal("danger", "Pulse check-in low",
                    "Mood " + mood + "/5 · workload " + workload + "/5" + (note.isEmpty() ? "" : " — “" + note + "”"),
                    "Talk this week; ask what to take off their plate");
        }

        if (pulses.size() >= 3)
        {
            int before = pulses.getJsonObject(pulses.size() - 3).getInteger("mood");

            if (mood < before)
            {
                return signal("accent", "Mood falling", "Mood " + before + " → " + mood + " over 3 weeks", "Check in at the next 1:1");
            }
        }

        return null;
    }

    /**
     * The risk of a signal set: High with any danger signal or 4 or more signals, Watch with 2 or 3, Low
     * with 1, else OK. {@code {risk, tone}}.
     */
    public static JsonObject risk(JsonArray signals)
    {
        long danger = signals.stream().filter(s -> "danger".equals(((JsonObject) s).getString("tone"))).count();

        if (danger >= 1 || signals.size() >= 4)
        {
            return new JsonObject().put("risk", "High").put("tone", "danger");
        }

        if (signals.size() >= 2)
        {
            return new JsonObject().put("risk", "Watch").put("tone", "accent");
        }

        return signals.isEmpty() ? new JsonObject().put("risk", "OK").put("tone", "ok") : new JsonObject().put("risk", "Low").put("tone", "");
    }

    private static JsonObject signal(String tone, String label, String reason, String next)
    {
        return new JsonObject().put("tone", tone).put("label", label).put("reason", reason).put("next", next);
    }

    // ------------------------------------------------------------------ skills coverage

    /**
     * Skills coverage (prototype {@code coverage} and the Action center table): per area, the people who
     * worked in it and how often ({@code counts}, with each cell's {@code heat} tone from
     * {@link WorkThresholds#COVERAGE_TONES}); one person is a Single point of failure, two Thin, more Covered.
     *
     * @param areas  area names in display order
     * @param counts area to person id to count (only the visible people)
     * @param people the visible people {@code [{id, name, newJoiner}]} in display order
     */
    public static JsonArray coverage(List<String> areas, Map<String, Map<String, Integer>> counts, List<JsonObject> people)
    {
        JsonArray out = new JsonArray();

        for (String area : areas)
        {
            Map<String, Integer> who = counts.getOrDefault(area, new LinkedHashMap<>());

            int n = who.size();

            String mark;

            String tone;

            String suggestion;

            if (n <= 1)
            {
                mark = "Single point of failure";

                tone = "danger";

                String partner = people.stream()
                        .filter(p -> !p.getBoolean("newJoiner", false) && !who.containsKey(p.getString("id")))
                        .map(p -> firstName(p.getString("name")))
                        .findFirst().orElse("someone");

                String owner = who.isEmpty() ? "—" : people.stream()
                        .filter(p -> who.containsKey(p.getString("id")))
                        .map(p -> firstName(p.getString("name")))
                        .findFirst().orElse("—");

                suggestion = "Pair " + partner + " with " + owner + " on the next " + area + " issue";
            }
            else if (n == 2)
            {
                mark = "Thin";

                tone = "accent";

                suggestion = "Add a reviewer from another area";
            }
            else
            {
                mark = "Covered";

                tone = "ok";

                suggestion = "—";
            }

            JsonObject byPerson = new JsonObject();

            JsonObject heat = new JsonObject();

            who.forEach((person, count) ->
            {
                byPerson.put(person, count);

                heat.put(person, CoreThresholds.tone(count, WorkThresholds.COVERAGE_TONES));
            });

            out.add(new JsonObject()
                    .put("area", area)
                    .put("counts", byPerson)
                    .put("heat", heat)
                    .put("people", n)
                    .put("coverage", mark)
                    .put("tone", tone)
                    .put("suggestion", suggestion));
        }

        return out;
    }

    // ------------------------------------------------------------------ team pulse

    /**
     * The team's pulse trend (prototype {@code pulseTrend}): per week, the average mood and workload of
     * the answers with their meter tones ({@link WorkThresholds#TEAM_MOOD_TONES},
     * {@link WorkThresholds#TEAM_WORKLOAD_TONES}), oldest week first. {@code pulses}:
     * {@code [{weekStart, mood, workload}]}.
     */
    public static JsonArray pulseTrend(List<JsonObject> pulses)
    {
        Map<String, double[]> weeks = new java.util.TreeMap<>();

        for (JsonObject p : pulses)
        {
            double[] sum = weeks.computeIfAbsent(p.getString("weekStart"), k -> new double[3]);

            sum[0] += p.getInteger("mood");

            sum[1] += p.getInteger("workload");

            sum[2]++;
        }

        JsonArray out = new JsonArray();

        weeks.forEach((week, sum) ->
        {
            double mood = Math.round(sum[0] / sum[2] * 10) / 10.0;

            double workload = Math.round(sum[1] / sum[2] * 10) / 10.0;

            out.add(new JsonObject()
                    .put("week", week)
                    .put("mood", mood)
                    .put("workload", workload)
                    .put("moodTone", CoreThresholds.tone(mood, WorkThresholds.TEAM_MOOD_TONES))
                    .put("workloadTone", CoreThresholds.tone(workload, WorkThresholds.TEAM_WORKLOAD_TONES))
                    .put("answers", (int) sum[2]));
        });

        return out;
    }

    /**
     * True for a low answer: mood {@value #LOW_MOOD} or lower, or workload {@value #HIGH_WORKLOAD}.
     */
    public static boolean low(JsonObject pulse)
    {
        return low(pulse.getInteger("mood"), pulse.getInteger("workload"));
    }

    /**
     * True for a low answer: mood {@value #LOW_MOOD} or lower, or workload {@value #HIGH_WORKLOAD}.
     */
    public static boolean low(int mood, int workload)
    {
        return mood <= LOW_MOOD || workload >= HIGH_WORKLOAD;
    }

    // ------------------------------------------------------------------ helpers

    /**
     * The first word of a name ("Priya Shah" → "Priya").
     */
    public static String firstName(String name)
    {
        if (name == null)
        {
            return "";
        }

        int space = name.indexOf(' ');

        return space < 0 ? name : name.substring(0, space);
    }

    private static String one(double value)
    {
        return String.format(Locale.ROOT, "%.1f", value);
    }
}
