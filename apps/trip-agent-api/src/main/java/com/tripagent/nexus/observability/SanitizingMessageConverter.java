package com.tripagent.nexus.observability;

import ch.qos.logback.classic.pattern.ClassicConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

public class SanitizingMessageConverter extends ClassicConverter {

  @Override
  public String convert(ILoggingEvent event) {
    return SensitiveDataSanitizer.sanitize(event.getFormattedMessage());
  }
}
