package com.pulse.platform.logging;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Masks secrets and personal data in log text (task 3.6, spec security-baseline "Safe errors and
 * logging"): passwords, tokens, session cookies, salary (CTC) values and free-text review content are
 * replaced by {@code ***}, whether they appear as JSON members ({@code "password":"x"}), key-value
 * pairs ({@code token=x}, {@code ctc: 12.5}), cookies or bearer headers. Wired into logback by
 * {@link RedactingMessageConverter} and {@link RedactingThrowableConverter}, so it covers every logger.
 */
public final class Redactor
{
    public static final String MASK = "***";

    /** Keys whose value is masked, matched case-insensitively and inside longer names (newPassword, csrfToken). */
    private static final String SECRET_KEYS = "[A-Za-z_]*(?:password|passwd|token|secret|api[_-]?key|authorization|"
            + "credential|cookie|ctc|salary|increment_amount)[A-Za-z_]*";

    /** Free-text members (review, 1:1, kudos and pulse content) masked by exact name. */
    private static final String TEXT_KEYS = "comment|comments|note|notes|text|feedback|kudos|message_body|answer|"
            + "answers|reflection|summary_text|body";

    private static final Pattern JSON_MEMBER = Pattern.compile(
            "(\"(?:" + SECRET_KEYS + "|" + TEXT_KEYS + ")\"\\s*:\\s*)(\"(?:[^\"\\\\]|\\\\.)*\"|[^,}\\]\\s]+)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern KEY_VALUE = Pattern.compile(
            "(\\b(?:" + SECRET_KEYS + ")\\s*[=:]\\s*)(\"[^\"]*\"|'[^']*'|[^\\s,;&}\\]]+)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern SESSION_COOKIE = Pattern.compile("(pulse_session=)[^;\\s,]+");

    private static final Pattern BEARER = Pattern.compile("(?i)(bearer\\s+)[A-Za-z0-9._~+/=-]+");

    private Redactor()
    {
    }

    public static String redact(String text)
    {
        if (text == null || text.isEmpty())
        {
            return text;
        }

        String out = BEARER.matcher(text).replaceAll("$1" + MASK);

        out = SESSION_COOKIE.matcher(out).replaceAll("$1" + MASK);
        out = replace(JSON_MEMBER, out, true);
        out = replace(KEY_VALUE, out, false);

        return out;
    }

    private static String replace(Pattern pattern, String text, boolean json)
    {
        Matcher m = pattern.matcher(text);
        StringBuilder sb = new StringBuilder();

        while (m.find())
        {
            String value = m.group(2);
            String mask = json && value.startsWith("\"") ? "\"" + MASK + "\"" : MASK;

            if (value.equals(MASK) || value.equals("\"" + MASK + "\""))
            {
                mask = value;
            }

            m.appendReplacement(sb, Matcher.quoteReplacement(m.group(1) + mask));
        }

        m.appendTail(sb);

        return sb.toString();
    }
}
