package com.tripagent.nexus.skill;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

public final class McpInvocationContext {

  private static final ThreadLocal<UUID> OWNER_TASK = new ThreadLocal<>();

  private McpInvocationContext() {}

  public static <T> T withOwnerTask(UUID ownerTaskId, Supplier<T> action) {
    UUID previous = OWNER_TASK.get();
    OWNER_TASK.set(ownerTaskId);
    try {
      return action.get();
    } finally {
      if (previous == null) {
        OWNER_TASK.remove();
      } else {
        OWNER_TASK.set(previous);
      }
    }
  }

  public static Optional<UUID> ownerTaskId() {
    return Optional.ofNullable(OWNER_TASK.get());
  }
}
