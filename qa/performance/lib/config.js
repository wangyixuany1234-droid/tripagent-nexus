const thresholdsConfig = JSON.parse(open("../../config/thresholds.json"));

export const performanceConfig = thresholdsConfig.performance;
export const realModelConfig = thresholdsConfig.realModel;
export const profileName = __ENV.QA_PROFILE || "smoke";
export const baseUrl = __ENV.BASE_URL || "http://127.0.0.1:8080";

export function numberFromEnv(name, fallback) {
  const raw = __ENV[name];
  if (raw === undefined || raw === "") {
    return Number(fallback);
  }
  const value = Number(raw);
  if (!Number.isFinite(value)) {
    throw new Error(`${name} must be numeric`);
  }
  return value;
}

export function booleanFromEnv(name, fallback = false) {
  const raw = __ENV[name];
  if (raw === undefined || raw === "") {
    return fallback;
  }
  return raw.toLowerCase() === "true";
}

export function thresholds() {
  const synchronous = performanceConfig.synchronous;
  const write = performanceConfig.write;
  const asyncTask = performanceConfig.async;
  const sse = performanceConfig.sseConnection;

  return {
    http_req_failed: [
      `rate<${numberFromEnv(
        "QA_HTTP_ERROR_RATE",
        Math.max(synchronous.errorRate, write.errorRate, asyncTask.errorRate),
      )}`,
    ],
    business_errors: [
      `rate<${numberFromEnv(
        "QA_BUSINESS_ERROR_RATE",
        Math.max(synchronous.errorRate, write.errorRate, asyncTask.errorRate),
      )}`,
    ],
    data_consistency_failures: ["count==0"],
    "http_req_duration{group:sync}": [
      `p(95)<${numberFromEnv("QA_SYNC_P95_MS", synchronous.p95Milliseconds)}`,
      `p(99)<${numberFromEnv("QA_SYNC_P99_MS", synchronous.p99Milliseconds)}`,
    ],
    sync_errors: [
      `rate<${numberFromEnv("QA_SYNC_ERROR_RATE", synchronous.errorRate)}`,
    ],
    "http_req_duration{group:write}": [
      `p(95)<${numberFromEnv("QA_WRITE_P95_MS", write.p95Milliseconds)}`,
      `p(99)<${numberFromEnv("QA_WRITE_P99_MS", write.p99Milliseconds)}`,
    ],
    write_errors: [
      `rate<${numberFromEnv("QA_WRITE_ERROR_RATE", write.errorRate)}`,
    ],
    "http_req_duration{group:async-submit}": [
      `p(95)<${numberFromEnv(
        "QA_ASYNC_SUBMIT_P95_MS",
        asyncTask.submitP95Milliseconds,
      )}`,
      `p(99)<${numberFromEnv(
        "QA_ASYNC_SUBMIT_P99_MS",
        asyncTask.submitP99Milliseconds,
      )}`,
    ],
    async_submit_errors: [
      `rate<${numberFromEnv(
        "QA_ASYNC_ERROR_RATE",
        asyncTask.errorRate,
      )}`,
    ],
    async_completion_duration: [
      `p(95)<${numberFromEnv(
        "QA_ASYNC_COMPLETION_P95_MS",
        asyncTask.completionP95Milliseconds,
      )}`,
    ],
    task_permanent_stuck: [
      `rate<=${numberFromEnv(
        "QA_MAX_TASK_STUCK_RATE",
        asyncTask.maximumStuckRate,
      )}`,
    ],
    duplicate_business_resources: [
      `count<=${numberFromEnv(
        "QA_MAX_DUPLICATE_RESOURCES",
        asyncTask.maximumDuplicateResources,
      )}`,
    ],
    sse_connection_success: [
      `rate>${numberFromEnv(
        "QA_SSE_MIN_CONNECT_SUCCESS_RATE",
        sse.minimumSuccessRate,
      )}`,
    ],
    sse_duplicate_events: [
      `count<=${numberFromEnv(
        "QA_SSE_MAX_DUPLICATE_EVENTS",
        sse.maximumDuplicateEvents,
      )}`,
    ],
    sse_lost_events: [
      `count<=${numberFromEnv(
        "QA_SSE_MAX_LOST_EVENTS",
        sse.maximumLostEvents,
      )}`,
    ],
  };
}

function configuredStages(profile) {
  if (__ENV.QA_STAGES_JSON) {
    const stages = JSON.parse(__ENV.QA_STAGES_JSON);
    if (!Array.isArray(stages) || stages.length === 0) {
      throw new Error("QA_STAGES_JSON must contain a non-empty stage array");
    }
    return stages;
  }
  return profile.stages;
}

export function workloadOptions(name = profileName) {
  const profile = performanceConfig[name];
  if (!profile) {
    throw new Error(`Unknown performance profile: ${name}`);
  }

  const base = {
    thresholds: thresholds(),
    setupTimeout: __ENV.QA_SETUP_TIMEOUT || "3m",
    summaryTrendStats: [
      "min",
      "avg",
      "med",
      "p(90)",
      "p(95)",
      "p(99)",
      "max",
    ],
    systemTags: ["status", "method", "name", "group", "scenario"],
    discardResponseBodies: false,
  };

  if (profile.stages) {
    return {
      ...base,
      stages: configuredStages(profile),
    };
  }

  const virtualUsers = numberFromEnv("QA_VUS", profile.virtualUsers);
  const duration = __ENV.QA_DURATION || profile.duration;
  if (name === "smoke" && (virtualUsers < 1 || virtualUsers > 3)) {
    throw new Error("Smoke Test requires between 1 and 3 VUs");
  }
  if (
    name === "soak" &&
    durationSeconds(duration) > durationSeconds(profile.maximumDuration)
  ) {
    throw new Error(`Soak Test duration cannot exceed ${profile.maximumDuration}`);
  }
  return {
    ...base,
    vus: virtualUsers,
    duration,
  };
}

function durationSeconds(value) {
  const matched = String(value).match(/^(\d+(?:\.\d+)?)(ms|s|m|h)$/);
  if (!matched) {
    throw new Error(`Unsupported duration: ${value}`);
  }
  const multipliers = { ms: 0.001, s: 1, m: 60, h: 3600 };
  return Number(matched[1]) * multipliers[matched[2]];
}

export function oneShotOptions(name) {
  const profile = performanceConfig[name];
  return {
    scenarios: {
      [name]: {
        executor: "per-vu-iterations",
        vus: numberFromEnv("QA_VUS", profile.virtualUsers),
        iterations: 1,
        maxDuration: __ENV.QA_DURATION || "2m",
      },
    },
    thresholds: {
      http_req_failed: ["rate<0.01"],
      business_errors: ["rate<0.01"],
    },
    setupTimeout: __ENV.QA_SETUP_TIMEOUT || "3m",
    summaryTrendStats: [
      "min",
      "avg",
      "med",
      "p(90)",
      "p(95)",
      "p(99)",
      "max",
    ],
    systemTags: ["status", "method", "name", "group", "scenario"],
  };
}

export function sseOptions() {
  const profile = performanceConfig.sse;
  const sse = performanceConfig.sseConnection;
  return {
    stages: configuredStages(profile),
    setupTimeout: __ENV.QA_SETUP_TIMEOUT || "3m",
    thresholds: {
      sse_connection_success: [
        `rate>${numberFromEnv(
          "QA_SSE_MIN_CONNECT_SUCCESS_RATE",
          sse.minimumSuccessRate,
        )}`,
      ],
      sse_reconnect_success: [
        `rate>${numberFromEnv(
          "QA_SSE_MIN_RECONNECT_SUCCESS_RATE",
          sse.minimumSuccessRate,
        )}`,
      ],
      sse_duplicate_events: [
        `count<=${numberFromEnv(
          "QA_SSE_MAX_DUPLICATE_EVENTS",
          sse.maximumDuplicateEvents,
        )}`,
      ],
      sse_lost_events: [
        `count<=${numberFromEnv(
          "QA_SSE_MAX_LOST_EVENTS",
          sse.maximumLostEvents,
        )}`,
      ],
      task_permanent_stuck: ["rate==0"],
    },
    summaryTrendStats: [
      "min",
      "avg",
      "med",
      "p(90)",
      "p(95)",
      "p(99)",
      "max",
    ],
    systemTags: ["status", "method", "name", "group", "scenario"],
  };
}

export function isDegradationProfile(name = profileName) {
  return name === "stress" || name === "spike";
}
