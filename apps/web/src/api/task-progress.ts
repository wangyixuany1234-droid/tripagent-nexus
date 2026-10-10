import {
  TaskEventFromJSON,
  type TaskEvent,
} from "@api-generated/models/TaskEvent";
import type { TaskRecord } from "@api-generated/models/TaskRecord";
import { environment } from "@/lib/environment";
import { apiAdapter, apiFetch } from "./client";

const terminalStatuses = new Set([
  "SUCCEEDED",
  "PARTIAL_SUCCESS",
  "FAILED",
  "CANCELLED",
]);

export type TaskConnectionMode = "sse" | "polling";

interface TaskProgressOptions {
  taskId: string;
  lastEventId?: string;
  signal?: AbortSignal;
  pollIntervalMs?: number;
  onEvent: (event: TaskEvent) => void;
  onTask: (task: TaskRecord) => void;
  onModeChange?: (mode: TaskConnectionMode) => void;
}

function wait(milliseconds: number, signal?: AbortSignal) {
  return new Promise<void>((resolve, reject) => {
    const onAbort = () => {
      window.clearTimeout(timeout);
      reject(signal?.reason);
    };
    const timeout = window.setTimeout(() => {
      signal?.removeEventListener("abort", onAbort);
      resolve();
    }, milliseconds);
    if (signal?.aborted) {
      onAbort();
      return;
    }
    signal?.addEventListener("abort", onAbort, { once: true });
  });
}

function parseEventBlock(block: string) {
  const lines = block.split(/\r?\n/);
  const data = lines
    .filter((line) => line.startsWith("data:"))
    .map((line) => line.slice(5).trimStart())
    .join("\n");
  if (!data) {
    return undefined;
  }
  return TaskEventFromJSON(JSON.parse(data));
}

async function streamEvents(options: TaskProgressOptions) {
  const request = await apiAdapter.tasks.getEventRequest(
    options.taskId,
    options.lastEventId,
  );
  const response = await apiFetch(
    new URL(request.path, `${environment.apiBaseUrl}/`),
    {
      ...request,
      signal: options.signal,
      headers: {
        ...request.headers,
        Accept: "text/event-stream",
      },
    },
  );
  if (!response.body) {
    throw new Error("SSE response body is unavailable.");
  }

  options.onModeChange?.("sse");
  const reader = response.body.pipeThrough(new TextDecoderStream()).getReader();
  let pending = "";

  while (!options.signal?.aborted) {
    const { value, done } = await reader.read();
    pending += value ?? "";
    const blocks = pending.split(/\r?\n\r?\n/);
    pending = blocks.pop() ?? "";
    for (const block of blocks) {
      const event = parseEventBlock(block);
      if (event) {
        options.onEvent(event);
      }
    }
    if (done) {
      return;
    }
  }
}

async function pollTask(options: TaskProgressOptions) {
  options.onModeChange?.("polling");
  while (!options.signal?.aborted) {
    const task = await apiAdapter.tasks.get(options.taskId);
    options.onTask(task);
    if (terminalStatuses.has(task.status)) {
      return;
    }
    await wait(options.pollIntervalMs ?? 2500, options.signal);
  }
}

export async function subscribeToTaskProgress(options: TaskProgressOptions) {
  try {
    await streamEvents(options);
  } catch (error) {
    if (options.signal?.aborted) {
      return;
    }
  }
  if (!options.signal?.aborted) {
    await pollTask(options);
  }
}
