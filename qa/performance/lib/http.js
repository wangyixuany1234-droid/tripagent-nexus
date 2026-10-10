import http from "k6/http";
import {
  asyncSubmitDuration,
  asyncSubmitErrors,
  businessErrors,
  degradedResponses,
  recordCapacityLatency,
  syncDuration,
  syncErrors,
  writeDuration,
  writeErrors,
} from "./metrics.js";

function metricForGroup(group) {
  if (group === "sync") {
    return [syncDuration, syncErrors];
  }
  if (group === "write") {
    return [writeDuration, writeErrors];
  }
  return [asyncSubmitDuration, asyncSubmitErrors];
}

export function safeJson(response) {
  try {
    return response.json();
  } catch (_error) {
    return null;
  }
}

export function request(
  method,
  url,
  body,
  {
    group,
    operation,
    headers = {},
    successStatuses = [200],
    acceptedDegradationStatuses = [],
  },
) {
  const expectedStatuses = [
    ...successStatuses,
    ...acceptedDegradationStatuses,
  ];
  const response = http.request(method, url, body, {
    headers,
    tags: {
      group,
      operation,
      name: operation,
    },
    responseCallback: http.expectedStatuses(...expectedStatuses),
  });
  const success = successStatuses.includes(response.status);
  const degraded = acceptedDegradationStatuses.includes(response.status);
  const [durationMetric, errorMetric] = metricForGroup(group);
  durationMetric.add(response.timings.duration, { operation });
  recordCapacityLatency(response.timings.duration, { group, operation });
  errorMetric.add(!success && !degraded, { operation });
  businessErrors.add(!success && !degraded, { group, operation });
  if (!success && !degraded) {
    console.warn(
      JSON.stringify({
        event: "qa_request_failed",
        group,
        operation,
        status: response.status,
        traceId: response.headers["X-Trace-Id"] || null,
      }),
    );
  }
  if (degraded) {
    degradedResponses.add(1, { group, operation, status: response.status });
  }
  return response;
}

export function jsonRequest(method, url, payload, options) {
  return request(method, url, JSON.stringify(payload), {
    ...options,
    headers: {
      "Content-Type": "application/json",
      ...(options.headers || {}),
    },
  });
}

export function getJson(url, options) {
  return request("GET", url, null, options);
}
