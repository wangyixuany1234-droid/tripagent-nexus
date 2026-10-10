package com.tripagent.nexus.agent.a2a;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.a2aproject.sdk.server.ServerCallContext;
import org.a2aproject.sdk.server.auth.TaskAuthorizationProvider;
import org.a2aproject.sdk.server.auth.TaskOperation;
import org.a2aproject.sdk.spec.A2AError;

public class TenantTaskAuthorizationProvider implements TaskAuthorizationProvider {

  private final Map<String, String> owners = new ConcurrentHashMap<>();

  @Override
  public boolean checkRead(ServerCallContext context, String taskId, TaskOperation operation) {
    return authenticated(context) && owner(context).equals(owners.get(taskId));
  }

  @Override
  public boolean checkWrite(ServerCallContext context, String taskId, TaskOperation operation) {
    return authenticated(context) && owner(context).equals(owners.get(taskId));
  }

  @Override
  public boolean checkCreate(ServerCallContext context, TaskOperation operation) {
    return context != null
        && context.getUser() != null
        && context.getUser().isAuthenticated()
        && !owner(context).isBlank();
  }

  @Override
  public boolean isTaskRecorded(String taskId) {
    return owners.containsKey(taskId);
  }

  @Override
  public void recordOwnership(ServerCallContext context, String taskId, TaskOperation operation)
      throws A2AError {
    String owner = owner(context);
    String previous = owners.putIfAbsent(taskId, owner);
    if (previous != null && !previous.equals(owner)) {
      throw new org.a2aproject.sdk.spec.TaskNotFoundError();
    }
  }

  private static String owner(ServerCallContext context) {
    if (context == null || context.getUser() == null) {
      return "";
    }
    return context.getUser().getUsername();
  }

  private static boolean authenticated(ServerCallContext context) {
    return context != null && context.getUser() != null && context.getUser().isAuthenticated();
  }
}
