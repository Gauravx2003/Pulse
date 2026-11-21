package com.pulse.platform.logging;

import ch.qos.logback.classic.pattern.ThrowableProxyConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

/** Logback {@code %redactedEx}: the stack trace with exception messages passed through {@link Redactor}. */
public class RedactingThrowableConverter extends ThrowableProxyConverter
{
    @Override
    public String convert(ILoggingEvent event)
    {
        return Redactor.redact(super.convert(event));
    }
}
