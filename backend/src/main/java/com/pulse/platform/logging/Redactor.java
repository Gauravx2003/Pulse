package com.pulse.platform.logging;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Masks secrets and personal data in log text (task 3.6, spec security-baseline "Safe errors and
 * logging"): passwords, tokens, session cookies, salary (CTC) values and free-text review content are
 * replaced by {@code ***}, whether they appear as JSON members ({@code "password":"x"}), key-value
 * pairs ({@code token=x}, {@code ctc: 12.5}), cookies, bearer tokens, Basic credentials and whole
 * {@code Authorization} header values (task 24.2). Wired into logback by
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

    /**
     * An {@code Authorization} header or member: the whole value, scheme included, is masked
     * ({@code Authorization: Basic dXNlcjpwdw==} becomes {@code Authorization: ***}). Task 24.2.
     */
    private static final Pattern AUTHORIZATION = Pattern.compile(
            "(?i)(\\bauthorization\\b\"?\\s*[:=]\\s*\"?)(?:(?:basic|bearer|token|digest)\\s+)?[^\\s\",;&}\\]]+");

    /** Basic credentials anywhere: masked when the base64 decodes to {@code user:password}. Task 24.2. */
    private static final Pattern BASIC = Pattern.compile("(?i)(\\bbasic\\s+)([A-Za-z0-9+/]{4,}={0,2})");

    private Redactor()
    {
    }

    public static String redact(String text)
    {
        if (text == null || text.isEmpty())
        {
            return text;
        }

        String out = AUTHORIZATION.matcher(text).replaceAll("$1" + MASK);

        out = BEARER.matcher(out).replaceAll("$1" + MASK);
        out = maskBasic(out);

        out = SESSION_COOKIE.matcher(out).replaceAll("$1" + MASK);
        out = replace(JSON_MEMBER, out, true);
        out = replace(KEY_VALUE, out, false);

        return out;
    }

    private static String maskBasic(String text)
    {
        Matcher m = BASIC.matcher(text);
        StringBuilder sb = new StringBuilder();

        while (m.find())
        {
            String replacement = m.group(0);

            try
            {
                String decoded = new String(Base64.getDecoder().decode(m.group(2)), StandardCharsets.UTF_8);

                if (decoded.indexOf(':') > 0)
                {
                    replacement = m.group(1) + MASK;
                }
            }
            catch (IllegalArgumentException e)
            {
                // not base64: ordinary text such as "basic setup"
            }

            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }

        m.appendTail(sb);

        return sb.toString();
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
