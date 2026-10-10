package com.tripagent.nexus.controller;

import com.tripagent.nexus.application.idempotency.IdempotencyService;
import com.tripagent.nexus.application.security.ResourceAuthorizationService;
import com.tripagent.nexus.application.service.AgentExecutionService;
import com.tripagent.nexus.application.service.TaskApplicationService;
import com.tripagent.nexus.controller.dto.ApiModels.AgentExecutionList;
import com.tripagent.nexus.domain.model.TaskRecord;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1/tasks")
public class TaskController {

  private final TaskApplicationService taskService;
  private final IdempotencyService idempotencyService;
  private final AgentExecutionService agentExecutionService;
  private final ResourceAuthorizationService authorization;

  public TaskController(
      TaskApplicationService taskService,
      IdempotencyService idempotencyService,
      AgentExecutionService agentExecutionService,
      ResourceAuthorizationService authorization) {
    this.taskService = taskService;
    this.idempotencyService = idempotencyService;
    this.agentExecutionService = agentExecutionService;
    this.authorization = authorization;
  }

  @GetMapping("/{taskId}")
  public TaskRecord get(@PathVariable UUID taskId, Authentication authentication) {
    authorization.requireTask(authentication, taskId);
    return taskService.get(taskId);
  }

  @GetMapping(path = "/{taskId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter events(
      @PathVariable UUID taskId,
      Authentication authentication,
      @RequestHeader(value = "Last-Event-ID", required = false) UUID lastEventId) {
    authorization.requireTask(authentication, taskId);
    return taskService.stream(taskId, lastEventId);
  }

  @GetMapping("/{taskId}/agents")
  public AgentExecutionList agents(@PathVariable UUID taskId, Authentication authentication) {
    authorization.requireTask(authentication, taskId);
    return new AgentExecutionList(agentExecutionService.list(taskService.get(taskId)));
  }

  @PostMapping("/{taskId}/cancel")
  public TaskRecord cancel(
      @PathVariable UUID taskId,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      Authentication authentication) {
    authorization.requireTask(authentication, taskId);
    return idempotencyService.execute(
        idempotencyKey,
        "POST:/api/v1/tasks/{taskId}/cancel:" + taskId,
        new CancelFingerprint(taskId),
        TaskRecord.class,
        () -> taskService.cancel(taskId));
  }

  private record CancelFingerprint(UUID taskId) {}
}
