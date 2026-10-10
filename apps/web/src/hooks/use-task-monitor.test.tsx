import type { PropsWithChildren } from "react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, renderHook, waitFor } from "@testing-library/react";
import type { TaskEvent } from "@api-generated/models/TaskEvent";
import type { TaskRecord } from "@api-generated/models/TaskRecord";
import { makeTask } from "@/test/factories";

const mocks = vi.hoisted(() => ({
  getTask: vi.fn<(taskId: string) => Promise<TaskRecord>>(),
  subscribe: vi.fn(),
}));

vi.mock("@/api/client", () => ({
  apiAdapter: {
    tasks: {
      get: mocks.getTask,
    },
  },
}));

vi.mock("@/api/task-progress", () => ({
  subscribeToTaskProgress: mocks.subscribe,
}));

import { useTaskMonitor } from "./use-task-monitor";

function event(taskId: string, eventId: string): TaskEvent {
  return {
    eventId,
    taskId,
    stage: "AGENTS_RUNNING",
    progress: 50,
    message: "处理中",
    timestamp: new Date("2026-09-16T08:00:00Z"),
  };
}

describe("useTaskMonitor", () => {
  beforeEach(() => {
    mocks.getTask.mockImplementation(async (taskId) => makeTask({ taskId }));
    mocks.subscribe.mockResolvedValue(undefined);
  });

  it("[defect-probing] aborts the old subscription and resets state when taskId changes", async () => {
    const queryClient = new QueryClient({
      defaultOptions: {
        queries: { retry: false },
      },
    });
    const wrapper = ({ children }: PropsWithChildren) => (
      <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
    );
    const { result, rerender, unmount } = renderHook(
      ({ taskId }) => useTaskMonitor(taskId),
      {
        initialProps: { taskId: "task-1" },
        wrapper,
      },
    );

    await waitFor(() => expect(mocks.subscribe).toHaveBeenCalledTimes(1));
    const firstSubscription = mocks.subscribe.mock.calls[0][0];
    act(() => {
      firstSubscription.onEvent(event("task-1", "event-1"));
      firstSubscription.onModeChange?.("polling");
    });
    expect(result.current.events.map((item) => item.eventId)).toEqual(["event-1"]);
    expect(result.current.connectionMode).toBe("polling");

    rerender({ taskId: "task-2" });

    await waitFor(() => expect(mocks.subscribe).toHaveBeenCalledTimes(2));
    expect(firstSubscription.signal.aborted).toBe(true);
    expect(result.current.events).toEqual([]);
    expect(result.current.connectionMode).toBe("sse");

    const secondSubscription = mocks.subscribe.mock.calls[1][0];
    unmount();
    expect(secondSubscription.signal.aborted).toBe(true);
  });
});
