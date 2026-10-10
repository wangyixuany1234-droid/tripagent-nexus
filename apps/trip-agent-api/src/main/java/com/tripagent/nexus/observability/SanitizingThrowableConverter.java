package com.tripagent.nexus.observability;

import ch.qos.logback.classic.pattern.ThrowableProxyConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

public class SanitizingThrowableConverter extends ThrowableProxyConverter {

  @Override
  public String convert(ILoggingEvent event) {
    return SensitiveDataSanitizer.sanitize(super.convert(event));
  }
}
