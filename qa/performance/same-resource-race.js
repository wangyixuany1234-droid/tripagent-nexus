import { check, fail } from "k6";
import { baseUrl, oneShotOptions } from "./lib/config.js";
import { idempotencyKey } from "./lib/data.js";
import { getJson, jsonRequest, safeJson } from "./lib/http.js";
import {
  optimisticLockConflicts,
  raceRejectedWrites,
  raceSuccessfulWrites,
} from "./lib/metrics.js";
import { summaryOutputs } from "./lib/summary.js";
import {
  createVuState,
  createPlan,
  ensureTrip,
  verifyMockEnvironment,
} from "./lib/workflows.js";

const baseOptions = oneShotOptions("race");
const expectedConflicts = Number(__ENV.QA_VUS || "20") - 1;

export const options = {
  ...baseOptions,
  thresholds: {
    ...baseOptions.thresholds,
    race_successful_writes: ["count==1"],
    race_rejected_writes: [`count==${expectedConflicts}`],
  },
};

export function setup() {
  verifyMockEnvironment(baseUrl);
  const setupState = createVuState("race", 9999, null);
  if (!ensureTrip(setupState, baseUrl)) {
    fail("无法创建竞态测试的合成旅行");
  }
  createPlan(setupState, baseUrl);
  if (!setupState.planId) {
    fail("无法创建竞态测试的合成计划");
  }
  const planResponse = getJson(
    `${baseUrl}/api/v1/plans/${setupState.planId}`,
    {
      group: "sync",
      operation: "race-get-plan",
      successStatuses: [200],
    },
  );
  const plan = safeJson(planResponse);
  if (
    planResponse.status !== 200 ||
    !["READY", "PARTIAL"].includes(plan?.status)
  ) {
    fail("竞态测试计划未进入可确认状态");
  }
  return {
    planId: setupState.planId,
    expectedVersion: plan.version,
  };
}

export default function (setupData) {
  const response = jsonRequest(
    "POST",
    `${baseUrl}/api/v1/plans/${setupData.planId}/confirm`,
    { expectedVersion: setupData.expectedVersion },
    {
      group: "write",
      operation: "same-plan-version-race",
      headers: {
        "Idempotency-Key": idempotencyKey(
          "same-plan-version-race",
          "race",
          __VU,
          1,
        ),
      },
      successStatuses: [200, 409],
    },
  );
  const conflictCode = safeJson(response)?.code;
  const explicitConflict =
    response.status === 409 &&
    [
      "RESOURCE_CONFLICT",
      "PLAN_VERSION_CONFLICT",
      "PLAN_NOT_CONFIRMABLE",
    ].includes(conflictCode);
  const accepted = response.status === 200 || explicitConflict;
  check(response, {
    "并发写入成功或返回明确版本冲突": () => accepted,
  });
  if (response.status === 200) {
    raceSuccessfulWrites.add(1);
  } else if (explicitConflict) {
    raceRejectedWrites.add(1);
    if (conflictCode === "RESOURCE_CONFLICT") {
      optimisticLockConflicts.add(1);
    }
  }
}

export function handleSummary(data) {
  return summaryOutputs(data, "race");
}
