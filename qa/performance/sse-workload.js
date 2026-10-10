import http from "k6/http";
import { check, sleep } from "k6";
import { baseUrl, sseOptions } from "./lib/config.js";
import {
  createTripPayload,
  idempotencyKey,
} from "./lib/data.js";
import { jsonRequest, safeJson } from "./lib/http.js";
import {
  activeSseConnections,
  sseConnectDuration,
  sseConnectionSuccess,
  sseDuplicateEvents,
  sseLostEvents,
  sseReconnectSuccess,
} from "./lib/metrics.js";
import { summaryOutputs } from "./lib/summary.js";
import {
  createVuState,
  ensureTrip,
  verifyMockEnvironment,
} from "./lib/workflows.js";

const documentFixture = open("../fixtures/documents/low-confidence.png", "b");
let state;

export const options = sseOptions();

export function setup() {
  return verifyMockEnvironment(baseUrl);
}

function eventRecords(body) {
  if (!body) {
    return [];
  }
  return String(body)
    .split(/\r?\n\r?\n/)
    .map((block) => {
      const id = block.match(/^id:\s*(.+)$/m)?.[1]?.trim();
      const data = block.match(/^data:\s*(.+)$/m)?.[1]?.trim();
      if (!id || !data) {
        return null;
      }
      try {
        return { id, data: JSON.parse(data) };
      } catch (_error) {
        return null;
      }
    })
    .filter((item) => item !== null);
}

function duplicates(records) {
  return records.length - new Set(records.map((event) => event.id)).size;
}

function connect(taskId, lastEventId) {
  const headers = { Accept: "text/event-stream" };
  if (lastEventId) {
    headers["Last-Event-ID"] = lastEventId;
  }
  activeSseConnections.add(1);
  const startedAt = Date.now();
  const response = http.get(`${baseUrl}/api/v1/tasks/${taskId}/events`, {
    headers,
    timeout: __ENV.QA_SSE_REQUEST_TIMEOUT || "45s",
    tags: {
      group: "sse",
      operation: lastEventId ? "sse-reconnect" : "sse-connect",
      name: lastEventId ? "sse-reconnect" : "sse-connect",
    },
    responseCallback: http.expectedStatuses(200),
  });
  sseConnectDuration.add(Date.now() - startedAt, {
    reconnect: Boolean(lastEventId),
  });
  activeSseConnections.add(0);
  return response;
}

export default function () {
  if (!state) {
    state = createVuState("sse", __VU, documentFixture);
  }
  if (!ensureTrip(state, baseUrl)) {
    sseConnectionSuccess.add(false);
    return;
  }

  state.sequence += 1;
  const submitted = jsonRequest(
    "POST",
    `${baseUrl}/api/v1/plans`,
    {
      tripId: state.tripId,
      title: `合成 SSE 方案-${__VU}-${state.sequence}`,
    },
    {
      group: "async-submit",
      operation: "sse-create-plan",
      headers: {
        "Idempotency-Key": idempotencyKey(
          "sse-create-plan",
          "sse",
          __VU,
          state.sequence,
        ),
      },
      successStatuses: [202],
    },
  );
  const accepted = safeJson(submitted);
  if (submitted.status !== 202 || !accepted?.taskId) {
    sseConnectionSuccess.add(false);
    return;
  }

  const first = connect(accepted.taskId);
  const firstRecords = eventRecords(first.body);
  const firstConnected =
    first.status === 200 &&
    String(first.headers["Content-Type"] || "").includes("text/event-stream");
  sseConnectionSuccess.add(firstConnected);
  sseDuplicateEvents.add(duplicates(firstRecords));
  check(first, {
    "SSE 建连成功": () => firstConnected,
    "SSE 返回任务事件": () => firstRecords.length > 0,
  });
  if (!firstConnected || firstRecords.length === 0) {
    sseLostEvents.add(1);
    return;
  }

  const resumeAfter = firstRecords[0].id;
  const replay = connect(accepted.taskId, resumeAfter);
  const replayRecords = eventRecords(replay.body);
  const expectedReplayIds = firstRecords.slice(1).map((event) => event.id);
  const actualReplayIds = replayRecords.map((event) => event.id);
  const replayMatches =
    replay.status === 200 &&
    JSON.stringify(actualReplayIds) === JSON.stringify(expectedReplayIds);
  sseReconnectSuccess.add(replayMatches);
  sseDuplicateEvents.add(duplicates(replayRecords));
  sseLostEvents.add(replayMatches ? 0 : 1);
  check(replay, {
    "Last-Event-ID 重连成功": () => replay.status === 200,
    "补发事件无缺失且不重复最后事件": () => replayMatches,
  });
  sleep(Number(__ENV.QA_THINK_TIME_SECONDS || "0.2"));
}

export function handleSummary(data) {
  return summaryOutputs(data, "sse");
}
