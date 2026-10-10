import http from "k6/http";
import { check, fail } from "k6";
import {
  baseUrl,
  numberFromEnv,
  performanceConfig,
  realModelConfig,
} from "./lib/config.js";
import { createVuState, createPlan, ensureTrip } from "./lib/workflows.js";
import { safeJson } from "./lib/http.js";
import { summaryOutputs } from "./lib/summary.js";

const maxCalls = numberFromEnv(
  "QA_REAL_MODEL_MAX_CALLS",
  performanceConfig.canary.maximumCalls,
);
const concurrency = numberFromEnv(
  "QA_REAL_MODEL_MAX_CONCURRENCY",
  performanceConfig.canary.virtualUsers,
);
if (concurrency < 1 || concurrency > realModelConfig.maxConcurrency) {
  throw new Error(
    `Canary concurrency must be between 1 and ${realModelConfig.maxConcurrency}`,
  );
}
if (maxCalls < 1 || maxCalls > realModelConfig.maxCalls) {
  throw new Error(`Canary calls must be between 1 and ${realModelConfig.maxCalls}`);
}
let state;

export const options = {
  scenarios: {
    canary: {
      executor: "shared-iterations",
      vus: concurrency,
      iterations: maxCalls,
      maxDuration: __ENV.QA_DURATION || "10m",
    },
  },
  thresholds: {
    async_submit_errors: ["rate<0.01"],
    task_permanent_stuck: ["rate==0"],
  },
  summaryTrendStats: ["avg", "med", "p(90)", "p(95)", "p(99)", "max"],
};

export function setup() {
  const response = http.get(`${baseUrl}/api/v1/health`, {
    responseCallback: http.expectedStatuses(200),
    tags: { group: "sync", operation: "canary-environment-gate" },
  });
  const live = response.status === 200 && safeJson(response)?.providerMode === "LIVE";
  check(response, {
    "Canary 连接真实模型环境": () => live,
  });
  if (!live) {
    fail("真实模型 Canary 只允许连接显式启用 LIVE Provider 的隔离环境");
  }
}

export default function () {
  if (!state) {
    state = createVuState("canary", __VU, null);
  }
  if (!ensureTrip(state, baseUrl)) {
    fail("真实模型 Canary 无法创建合成旅行");
  }
  createPlan(state, baseUrl);
}

export function handleSummary(data) {
  return summaryOutputs(data, "canary");
}
