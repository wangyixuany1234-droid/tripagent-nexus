import { check } from "k6";
import { baseUrl, oneShotOptions } from "./lib/config.js";
import { createTripPayload, idempotencyKey } from "./lib/data.js";
import { jsonRequest, safeJson } from "./lib/http.js";
import {
  idempotencyResourceFingerprint,
  idempotencyResponses,
} from "./lib/metrics.js";
import { summaryOutputs } from "./lib/summary.js";
import { verifyMockEnvironment } from "./lib/workflows.js";

export const options = oneShotOptions("idempotency");

export function setup() {
  verifyMockEnvironment(baseUrl);
  return {
    idempotencyKey: idempotencyKey(
      "concurrent-create",
      "idempotency",
      0,
      0,
    ),
    payload: createTripPayload("idempotency", 1),
  };
}

function fingerprint(resourceId) {
  const hexadecimal = resourceId.replaceAll("-", "").slice(-8);
  return Number.parseInt(hexadecimal, 16);
}

export default function (setupData) {
  const response = jsonRequest(
    "POST",
    `${baseUrl}/api/v1/trips`,
    setupData.payload,
    {
      group: "write",
      operation: "idempotency-concurrent-create",
      headers: { "Idempotency-Key": setupData.idempotencyKey },
      successStatuses: [201, 409],
    },
  );
  const body = safeJson(response);
  const expectedConflict =
    response.status === 409 &&
    ["IDEMPOTENCY_KEY_REUSED", "IDEMPOTENCY_REQUEST_IN_PROGRESS"].includes(
      body?.code,
    );
  const valid =
    (response.status === 201 && Boolean(body?.tripId)) ||
    expectedConflict;
  check(response, {
    "并发幂等请求返回同一资源或明确冲突": () => valid,
  });
  idempotencyResponses.add(1, {
    outcome: response.status === 201 ? "resource" : "conflict",
  });
  if (response.status === 201 && body?.tripId) {
    idempotencyResourceFingerprint.add(fingerprint(body.tripId));
  }
}

export function handleSummary(data) {
  return summaryOutputs(data, "idempotency");
}
