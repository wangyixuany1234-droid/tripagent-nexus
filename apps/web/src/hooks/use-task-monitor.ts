import { useEffect, useRef, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import type { TaskEvent } from "@api-generated/models/TaskEvent";
import { apiAdapter } from "@/api/client";
import {
  subscribeToTaskProgress,
  type TaskConnectionMode,
} from "@/api/task-progress";

const terminalStatuses = new Set([
  "SUCCEEDED",
  "PARTIAL_SUCCESS",
  "FAILED",
  "CANCELLED",
]);

export function taskQueryKey(taskId: string) {
  return ["tasks", taskId] as const;
}

export function agentExecutionsQueryKey(taskId: string) {
  return ["tasks", taskId, "agents"] as const;
}

export function useTaskMonitor(
  taskId?: string,
  onEvent?: (event: TaskEvent) => void,
) {
  const queryClient = useQueryClient();
  const onEventRef = useRef(onEvent);
  const [events, setEvents] = useState<TaskEvent[]>([]);
  const [connectionMode, setConnectionMode] = useState<TaskConnectionMode>("sse");

  onEventRef.current = onEvent;

  const taskQuery = useQuery({
    queryKey: taskQueryKey(taskId ?? ""),
    queryFn: ({ signal }) => apiAdapter.tasks.get(taskId!, signal),
    enabled: Boolean(taskId),
    staleTime: 1000,
    retry: 1,
  });

  useEffect(() => {
    setEvents([]);
    setConnectionMode("sse");
    if (!taskId) {
      return;
    }
    const controller = new AbortController();
    void subscribeToTaskProgress({
      taskId,
      signal: controller.signal,
      onModeChange: setConnectionMode,
      onEvent: (event) => {
        if (controller.signal.aborted) {
          return;
        }
        setEvents((current) => {
          if (current.some((item) => item.eventId === event.eventId)) {
            return current;
          }
          return [...current, event].slice(-50);
        });
        onEventRef.current?.(event);
        void queryClient.invalidateQueries({ queryKey: taskQueryKey(taskId) });
        void queryClient.invalidateQueries({
          queryKey: agentExecutionsQueryKey(taskId),
        });
      },
      onTask: (task) => {
        if (controller.signal.aborted) {
          return;
        }
        queryClient.setQueryData(taskQueryKey(taskId), task);
        void queryClient.invalidateQueries({
          queryKey: agentExecutionsQueryKey(taskId),
        });
      },
    }).catch(() => undefined);
    return () => controller.abort();
  }, [queryClient, taskId]);

  return {
    ...taskQuery,
    events,
    connectionMode,
    isTerminal: taskQuery.data
      ? terminalStatuses.has(taskQuery.data.status)
      : false,
  };
}
