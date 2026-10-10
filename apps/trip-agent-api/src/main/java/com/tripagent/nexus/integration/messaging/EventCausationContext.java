package com.tripagent.nexus.integration.messaging;

import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class EventCausationContext {

  private final ThreadLocal<SourceEvent> current = new ThreadLocal<>();

  public void run(EventEnvelope event, Runnable operation) {
    SourceEvent previous = current.get();
    current.set(new SourceEvent(event.eventId(), event.eventType(), event.traceId()));
    try {
      operation.run();
    } finally {
      if (previous == null) {
        current.remove();
      } else {
        current.set(previous);
      }
    }
  }

  public Optional<SourceEvent> current() {
    return Optional.ofNullable(current.get());
  }

  public record SourceEvent(UUID eventId, String eventType, String traceId) {}
}
