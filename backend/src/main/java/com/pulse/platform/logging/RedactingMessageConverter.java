package com.pulse.platform.logging;

import ch.qos.logback.classic.pattern.ClassicConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

/** Logback {@code %redactedMsg}: the formatted message passed through {@link Redactor}. */
public class RedactingMessageConverter extends ClassicConverter
{
    @Override
    public String convert(ILoggingEvent event)
    {
        return Redactor.redact(event.getFormattedMessage());
    }
}
