import { vi } from "vitest";
import { TaskStatus } from "@api-generated/models/TaskStatus";
import { TaskType } from "@api-generated/models/TaskType";
import { makeTask } from "@/test/factories";

const mocks = vi.hoisted(() => ({
  getEventRequest: vi.fn(),
  getTask: vi.fn(),
  apiFetch: vi.fn(),
}));

vi.mock("./client", () => ({
  apiFetch: mocks.apiFetch,
  apiAdapter: {
    tasks: {
      getEventRequest: mocks.getEventRequest,
      get: mocks.getTask,
    },
  },
}));

import { subscribeToTaskProgress } from "./task-progress";

describe("subscribeToTaskProgress", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("emits parsed SSE updates before completing through polling", async () => {
    mocks.getEventRequest.mockResolvedValue({
      path: "/api/v1/tasks/task-1/events",
      method: "GET",
      headers: {},
    });
    const body = [
      "id: 018f0e31-6ec8-7a59-b871-0242ac121020",
      "event: task-progress",
      'data: {"eventId":"018f0e31-6ec8-7a59-b871-0242ac121020","taskId":"task-1","stage":"AGENTS_RUNNING","progress":45,"message":"Agent work in progress","timestamp":"2026-09-06T08:00:00Z"}',
      "",
      "",
    ].join("\n");
    mocks.apiFetch.mockResolvedValue(
      new Response(body, {
        status: 200,
        headers: { "Content-Type": "text/event-stream" },
      }),
    );
    mocks.getTask.mockResolvedValue({
      taskId: "task-1",
      type: TaskType.PlanGeneration,
      resourceId: "trip-1",
      status: TaskStatus.Succeeded,
      stage: "COMPLETED",
      progress: 100,
      traceId: "4bf92f3577b34da6a3ce929d0e0e4736",
      resultResourceIds: new Set<string>(),
      retryable: false,
      recoveryAttempts: 0,
      degradedComponents: new Set<string>(),
      createdAt: new Date("2026-09-06T08:00:00Z"),
      updatedAt: new Date("2026-09-06T08:01:00Z"),
    });
    const events: Array<{ stage: string; progress: number }> = [];
    const modes: string[] = [];

    await subscribeToTaskProgress({
      taskId: "task-1",
      onEvent: (event) =>
        events.push({ stage: event.stage, progress: event.progress }),
      onTask: () => undefined,
      onModeChange: (mode) => modes.push(mode),
    });

    expect(events).toEqual([{ stage: "AGENTS_RUNNING", progress: 45 }]);
    expect(modes).toEqual(["sse", "polling"]);
  });

  it("falls back to polling when the SSE connection fails", async () => {
    mocks.getEventRequest.mockResolvedValue({
      path: "/api/v1/tasks/task-1/events",
      method: "GET",
      headers: {},
    });
    mocks.apiFetch.mockRejectedValue(new Error("SSE unavailable"));
    mocks.getTask.mockResolvedValue({
      taskId: "task-1",
      type: TaskType.PlanGeneration,
      resourceId: "trip-1",
      status: TaskStatus.Succeeded,
      stage: "COMPLETED",
      progress: 100,
      traceId: "4bf92f3577b34da6a3ce929d0e0e4736",
      resultResourceIds: new Set<string>(),
      retryable: false,
      recoveryAttempts: 0,
      degradedComponents: new Set<string>(),
      createdAt: new Date("2026-09-06T08:00:00Z"),
      updatedAt: new Date("2026-09-06T08:01:00Z"),
    });
    const modes: string[] = [];
    const tasks: string[] = [];

    await subscribeToTaskProgress({
      taskId: "task-1",
      onEvent: () => undefined,
      onTask: (task) => tasks.push(task.status),
      onModeChange: (mode) => modes.push(mode),
    });

    expect(modes).toEqual(["polling"]);
    expect(tasks).toEqual([TaskStatus.Succeeded]);
    expect(mocks.getTask).toHaveBeenCalledWith("task-1");
  });

  it("[defect-probing] removes completed polling waits from the abort signal", async () => {
    mocks.getEventRequest.mockResolvedValue({
      path: "/api/v1/tasks/task-1/events",
      method: "GET",
      headers: {},
    });
    mocks.apiFetch.mockRejectedValue(new Error("SSE unavailable"));
    mocks.getTask
      .mockResolvedValueOnce(makeTask({ status: TaskStatus.Running }))
      .mockResolvedValueOnce(makeTask({ status: TaskStatus.Succeeded }));
    const controller = new AbortController();
    const removeEventListener = vi.spyOn(controller.signal, "removeEventListener");

    await subscribeToTaskProgress({
      taskId: "task-1",
      signal: controller.signal,
      pollIntervalMs: 0,
      onEvent: () => undefined,
      onTask: () => undefined,
    });

    expect(mocks.getTask).toHaveBeenCalledTimes(2);
    expect(removeEventListener).toHaveBeenCalledWith("abort", expect.any(Function));
  });
});
