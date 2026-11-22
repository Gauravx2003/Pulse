package com.pulse.modules.rules;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Checks that a PR title pattern is a safe regular expression (task 5.2, spec platform/rules-thresholds
 * "Value validation"): it must compile, and it must not backtrack catastrophically. Two checks:
 *
 * <ol>
 *   <li><b>Static.</b> A repeated group that itself contains an unbounded repetition, such as
 *       {@code (a+)+} or {@code (\w*\s?)*}, is refused: it is the shape behind exponential backtracking.</li>
 *   <li><b>Probe.</b> The pattern is run against adversarial titles (long runs of one or two characters,
 *       including the pattern's own literals, followed by a character that makes the match fail). Each
 *       run has a fixed budget of character reads, so a slow pattern is stopped deterministically,
 *       without threads or timers. Exceeding the budget refuses the pattern.</li>
 * </ol>
 *
 * <p>Pure and CPU-bound: callers run it inside {@code vertx.executeBlocking(...)}.
 */
final class SafeRegex
{
    /** Character reads allowed for one probe; a linear or mildly polynomial pattern needs far fewer. */
    static final long BUDGET = 2_000_000;

    private static final int PUMP_LENGTH = 40;

    private static final String KILLERS = "!\n\u0000.";

    static final String NESTED_MESSAGE = "This pattern repeats a group that itself repeats, or that offers choices "
            + "starting with the same text (like (a+)+ or (a|aa)+). That can take exponentially long on some titles. "
            + "Rewrite the repeated group so each repetition can match in only one way.";

    static final String SLOW_MESSAGE = "This pattern takes too long on some titles (catastrophic backtracking). "
            + "Simplify the repeated parts.";

    private SafeRegex()
    {
    }

    /** Null when the pattern is safe; otherwise the explanation shown to the Admin. */
    static String problem(String regex)
    {
        Pattern pattern;

        try
        {
            pattern = Pattern.compile(regex);
        }
        catch (PatternSyntaxException e)
        {
            return "This is not a valid regular expression: " + e.getDescription() + ".";
        }

        if (hasNestedRepetition(regex))
        {
            return NESTED_MESSAGE;
        }

        for (String probe : probes(regex))
        {
            try
            {
                Matcher m = pattern.matcher(new Budgeted(probe, BUDGET));

                m.find();
            }
            catch (BudgetExceeded e)
            {
                return SLOW_MESSAGE;
            }
            catch (StackOverflowError e)
            {
                return SLOW_MESSAGE;
            }
        }

        return null;
    }

    // ------------------------------------------------------------------ static check

    /**
     * True when a group quantified with {@code *}, {@code +} or an open {@code {n,}} contains an
     * unbounded repetition, or offers alternatives that can start with the same character
     * ({@code (a|aa)+}, {@code (\\w|\\d)*}). Both shapes backtrack exponentially in common regex engines
     * (browsers included), even where Java's engine happens to cope.
     */
    static boolean hasNestedRepetition(String regex)
    {
        Deque<Frame> groups = new ArrayDeque<>();

        groups.push(new Frame());

        int i = 0;
        int n = regex.length();

        while (i < n)
        {
            char c = regex.charAt(i);

            if (c == '\\')
            {
                groups.peek().atom(i + 1 < n && Character.isLetterOrDigit(regex.charAt(i + 1))
                        ? ANY : String.valueOf(i + 1 < n ? regex.charAt(i + 1) : c));
                i = quantified(regex, i + 2, groups.peek());

                continue;
            }

            if (c == '[' || c == '.')
            {
                groups.peek().atom(ANY);
                i = quantified(regex, c == '[' ? endOfClass(regex, i) : i + 1, groups.peek());

                continue;
            }

            if (c == '(')
            {
                groups.peek().atom(ANY);
                groups.push(new Frame());
                i = skipGroupPrefix(regex, i + 1);

                continue;
            }

            if (c == '|')
            {
                groups.peek().altStart = true;
                i++;

                continue;
            }

            if (c == ')')
            {
                Frame inner = groups.size() > 1 ? groups.pop() : new Frame();
                int after = i + 1;
                int q = quantifierEnd(regex, after);
                boolean outerUnbounded = q > after && unbounded(regex.substring(after, q));

                if (outerUnbounded && (inner.unbounded || inner.overlapping()))
                {
                    return true;
                }

                if (inner.unbounded || outerUnbounded)
                {
                    groups.peek().unbounded = true;
                }

                i = q;

                continue;
            }

            if (c != '^' && c != '$')
            {
                groups.peek().atom(String.valueOf(c));
            }

            i = quantified(regex, i + 1, groups.peek());
        }

        return false;
    }

    /** First-atom marker for a class, an escape class, a dot or a nested group: may match anything. */
    private static final String ANY = "\u0000any";

    /** Scanner state of one group: whether it repeats without bound, and how each alternative starts. */
    private static final class Frame
    {
        boolean unbounded;

        boolean altStart = true;

        final List<String> firsts = new ArrayList<>();

        void atom(String first)
        {
            if (altStart)
            {
                firsts.add(first);
                altStart = false;
            }
        }

        boolean overlapping()
        {
            for (int a = 0; a < firsts.size(); a++)
            {
                for (int b = a + 1; b < firsts.size(); b++)
                {
                    if (firsts.get(a).equals(firsts.get(b)) || ANY.equals(firsts.get(a)) || ANY.equals(firsts.get(b)))
                    {
                        return true;
                    }
                }
            }

            return false;
        }
    }

    /** Skips {@code ?:}, {@code ?=}, {@code ?!}, {@code ?<=}, {@code ?<!} and {@code ?<name>} after an opening parenthesis. */
    private static int skipGroupPrefix(String regex, int at)
    {
        if (at >= regex.length() || regex.charAt(at) != '?')
        {
            return at;
        }

        if (regex.startsWith("?<", at) && !regex.startsWith("?<=", at) && !regex.startsWith("?<!", at))
        {
            int close = regex.indexOf('>', at);

            return close < 0 ? regex.length() : close + 1;
        }

        for (String prefix : new String[] {"?<=", "?<!", "?:", "?=", "?!", "?>"})
        {
            if (regex.startsWith(prefix, at))
            {
                return at + prefix.length();
            }
        }

        return at + 1;
    }

    /** Skips the quantifier after an atom ending at {@code at}, marking the frame when it is unbounded. */
    private static int quantified(String regex, int at, Frame frame)
    {
        int q = quantifierEnd(regex, Math.min(at, regex.length()));

        if (q > at && unbounded(regex.substring(at, q)))
        {
            frame.unbounded = true;
        }

        return Math.max(q, Math.min(at, regex.length()));
    }

    /** End of the quantifier starting at {@code at} ({@code at} itself when there is none). */
    private static int quantifierEnd(String regex, int at)
    {
        if (at >= regex.length())
        {
            return at;
        }

        char c = regex.charAt(at);
        int end = at;

        if (c == '*' || c == '+' || c == '?')
        {
            end = at + 1;
        }
        else if (c == '{')
        {
            int close = regex.indexOf('}', at);

            if (close > at && regex.substring(at + 1, close).matches("\\d+(,\\d*)?"))
            {
                end = close + 1;
            }
        }

        if (end > at && end < regex.length() && (regex.charAt(end) == '?' || regex.charAt(end) == '+'))
        {
            end++;                                   // lazy or possessive suffix
        }

        return end;
    }

    private static boolean unbounded(String quantifier)
    {
        char first = quantifier.charAt(0);

        if (first == '*' || first == '+')
        {
            return true;
        }

        if (first == '{')
        {
            String body = quantifier.substring(1, quantifier.indexOf('}'));

            if (body.endsWith(","))
            {
                return true;
            }

            if (body.contains(","))
            {
                String[] parts = body.split(",");

                return Integer.parseInt(parts[1]) > 16;
            }
        }

        return false;
    }

    private static int endOfClass(String regex, int open)
    {
        int i = open + 1;

        if (i < regex.length() && regex.charAt(i) == '^')
        {
            i++;
        }

        if (i < regex.length() && regex.charAt(i) == ']')
        {
            i++;
        }

        while (i < regex.length() && regex.charAt(i) != ']')
        {
            i += regex.charAt(i) == '\\' ? 2 : 1;
        }

        return Math.min(i + 1, regex.length());
    }

    // ------------------------------------------------------------------ probes

    private static List<String> probes(String regex)
    {
        Set<Character> pumps = new LinkedHashSet<>(List.of('a', 'A', '0', ' ', '-', '_', 'x'));

        for (char c : regex.toCharArray())
        {
            if (Character.isLetterOrDigit(c) || " -_:/#".indexOf(c) >= 0)
            {
                pumps.add(c);
            }

            if (pumps.size() >= 24)
            {
                break;
            }
        }

        List<Character> chars = new ArrayList<>(pumps);
        List<String> out = new ArrayList<>();

        for (char p : chars)
        {
            for (char k : KILLERS.toCharArray())
            {
                out.add(String.valueOf(p).repeat(PUMP_LENGTH) + k);
            }
        }

        for (int i = 0; i + 1 < chars.size() && i < 8; i++)
        {
            out.add((String.valueOf(chars.get(i)) + chars.get(i + 1)).repeat(PUMP_LENGTH / 2) + "!");
        }

        return out;
    }

    /** Thrown when a probe used up its character-read budget. */
    private static final class BudgetExceeded extends RuntimeException
    {
        BudgetExceeded()
        {
            super(null, null, false, false);
        }
    }

    /** A string that counts the characters the matcher reads and stops it past a budget. */
    private static final class Budgeted implements CharSequence
    {
        private final String text;

        private final long[] reads;

        private final long budget;

        Budgeted(String text, long budget)
        {
            this(text, new long[1], budget);
        }

        private Budgeted(String text, long[] reads, long budget)
        {
            this.text = text;
            this.reads = reads;
            this.budget = budget;
        }

        @Override
        public int length()
        {
            return text.length();
        }

        @Override
        public char charAt(int index)
        {
            if (++reads[0] > budget)
            {
                throw new BudgetExceeded();
            }

            return text.charAt(index);
        }

        @Override
        public CharSequence subSequence(int start, int end)
        {
            return new Budgeted(text.substring(start, end), reads, budget);
        }

        @Override
        public String toString()
        {
            return text;
        }
    }
}
