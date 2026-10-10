import http from "k6/http";
import { check, fail, sleep } from "k6";
import {
  correctionPayload,
  createTripPayload,
  feedbackPayload,
  idempotencyKey,
  replanPayload,
  requirementPayload,
} from "./data.js";
import { getJson, jsonRequest, request, safeJson } from "./http.js";
import {
  asyncCompletionDuration,
  businessErrors,
  dataConsistencyFailures,
  duplicateBusinessResources,
  taskPermanentStuck,
} from "./metrics.js";

const TERMINAL_TASK_STATES = new Set([
  "WAITING_CONFIRMATION",
  "SUCCEEDED",
  "PARTIAL_SUCCESS",
  "FAILED",
  "CANCELLED",
]);

function idempotencyHeaders(state, operation) {
  state.sequence += 1;
  return {
    "Idempotency-Key": idempotencyKey(
      operation,
      state.scenario,
      state.vu,
      state.sequence,
    ),
  };
}

function degradationStatuses(state) {
  return state.allowDegradation ? [429, 503] : [];
}

function validIdentifier(value) {
  return typeof value === "string" && value.length === 36;
}

export function createVuState(scenario, vu, fixtureBytes) {
  return {
    scenario,
    vu,
    fixtureBytes,
    sequence: 0,
    tripId: null,
    userId: createTripPayload(scenario, vu).userId,
    taskId: null,
    documentId: null,
    bookingId: null,
    planId: null,
    planVersion: null,
    confirmed: false,
    seenTaskIds: new Set(),
    seenResourceIds: new Set(),
    allowDegradation: scenario === "stress" || scenario === "spike",
  };
}

function recordAcceptedTask(
  state,
  accepted,
  { allowExistingTask = false, allowExistingResource = false } = {},
) {
  const duplicate =
    (!allowExistingTask && state.seenTaskIds.has(accepted.taskId)) ||
    (!allowExistingResource && state.seenResourceIds.has(accepted.resourceId));
  duplicateBusinessResources.add(duplicate ? 1 : 0);
  state.seenTaskIds.add(accepted.taskId);
  state.seenResourceIds.add(accepted.resourceId);
}

export function verifyMockEnvironment(baseUrl) {
  const response = http.get(`${baseUrl}/api/v1/health`, {
    tags: { group: "sync", operation: "environment-gate" },
    responseCallback: http.expectedStatuses(200),
  });
  const valid = check(response, {
    "服务健康": (item) => item.status === 200,
    "大规模性能测试使用模拟 Provider": (item) =>
      safeJson(item)?.providerMode === "MOCK",
  });
  if (!valid) {
    fail("性能测试只允许连接健康且 providerMode=MOCK 的测试环境");
  }
  return {
    providerMode: "MOCK",
  };
}

export function ensureTrip(state, baseUrl) {
  if (state.tripId) {
    return true;
  }

  const created = jsonRequest(
    "POST",
    `${baseUrl}/api/v1/trips`,
    createTripPayload(state.scenario, state.vu),
    {
      group: "write",
      operation: "create-trip",
      headers: idempotencyHeaders(state, "create-trip"),
      successStatuses: [201],
      acceptedDegradationStatuses: degradationStatuses(state),
    },
  );
  const body = safeJson(created);
  const createOk = created.status === 201 && validIdentifier(body?.tripId);
  check(created, { "创建独立旅行成功": () => createOk });
  if (!createOk) {
    return false;
  }
  state.tripId = body.tripId;

  const requirement = jsonRequest(
    "PUT",
    `${baseUrl}/api/v1/trips/${state.tripId}/requirements`,
    requirementPayload(state.scenario, state.vu),
    {
      group: "write",
      operation: "save-requirement",
      headers: idempotencyHeaders(state, "save-requirement"),
      successStatuses: [200],
      acceptedDegradationStatuses: degradationStatuses(state),
    },
  );
  const requirementOk =
    requirement.status === 200 &&
    safeJson(requirement)?.tripId === state.tripId;
  check(requirement, { "保存独立旅行需求成功": () => requirementOk });
  return requirementOk;
}

export function pollTask(state, baseUrl, taskId) {
  const startedAt = Date.now();
  const timeoutMilliseconds =
    Number(__ENV.QA_ASYNC_TIMEOUT_SECONDS || "35") * 1000;
  const pollSeconds = Number(__ENV.QA_TASK_POLL_INTERVAL_SECONDS || "0.25");
  let lastTask = null;

  while (Date.now() - startedAt < timeoutMilliseconds) {
    const response = getJson(`${baseUrl}/api/v1/tasks/${taskId}`, {
      group: "sync",
      operation: "get-task",
      successStatuses: [200],
      acceptedDegradationStatuses: degradationStatuses(state),
    });
    if (response.status === 200) {
      lastTask = safeJson(response);
      if (TERMINAL_TASK_STATES.has(lastTask?.status)) {
        const elapsed = Date.now() - startedAt;
        asyncCompletionDuration.add(elapsed, {
          taskType: lastTask.type || "UNKNOWN",
        });
        taskPermanentStuck.add(false, {
          taskType: lastTask.type || "UNKNOWN",
        });
        const completed =
          lastTask.status === "WAITING_CONFIRMATION" ||
          lastTask.status === "SUCCEEDED" ||
          lastTask.status === "PARTIAL_SUCCESS";
        businessErrors.add(!completed, {
          group: "async-completion",
          operation: lastTask.type || "UNKNOWN",
        });
        return lastTask;
      }
    }
    sleep(pollSeconds);
  }

  taskPermanentStuck.add(true, {
    taskType: lastTask?.type || "UNKNOWN",
  });
  businessErrors.add(true, {
    group: "async-completion",
    operation: lastTask?.type || "UNKNOWN",
  });
  return lastTask;
}

export function readOperation(state, baseUrl, selector) {
  if (!ensureTrip(state, baseUrl)) {
    return;
  }

  const operation = selector % 6;
  if (operation === 0) {
    const response = getJson(`${baseUrl}/api/v1/health`, {
      group: "sync",
      operation: "health",
      successStatuses: [200],
      acceptedDegradationStatuses: degradationStatuses(state),
    });
    check(response, { "健康检查成功": (item) => item.status === 200 });
    return;
  }
  if (operation === 1) {
    const response = getJson(`${baseUrl}/api/v1/trips/${state.tripId}`, {
      group: "sync",
      operation: "get-trip",
      headers: { "X-User-Id": state.userId },
      successStatuses: [200],
      acceptedDegradationStatuses: degradationStatuses(state),
    });
    const consistent =
      response.status === 200 && safeJson(response)?.tripId === state.tripId;
    check(response, { "读取旅行一致": () => consistent });
    dataConsistencyFailures.add(consistent ? 0 : 1, {
      operation: "get-trip",
    });
    return;
  }
  if (operation === 2) {
    const response = getJson(
      `${baseUrl}/api/v1/trips/${state.tripId}/plans`,
      {
        group: "sync",
        operation: "list-plan-history",
        successStatuses: [200],
        acceptedDegradationStatuses: degradationStatuses(state),
      },
    );
    check(response, { "查询计划历史成功": (item) => item.status === 200 });
    const plans = safeJson(response)?.items || [];
    if (!state.planId && plans.length > 0) {
      state.planId = plans[0].planId;
      state.planVersion = plans[0].version;
    }
    return;
  }
  if (operation === 3 && state.taskId) {
    const response = getJson(`${baseUrl}/api/v1/tasks/${state.taskId}`, {
      group: "sync",
      operation: "get-task",
      successStatuses: [200],
      acceptedDegradationStatuses: degradationStatuses(state),
    });
    check(response, { "查询任务成功": (item) => item.status === 200 });
    return;
  }
  if (operation === 4 && state.planId) {
    const response = getJson(`${baseUrl}/api/v1/plans/${state.planId}`, {
      group: "sync",
      operation: "get-plan",
      successStatuses: [200],
      acceptedDegradationStatuses: degradationStatuses(state),
    });
    check(response, { "查询计划成功": (item) => item.status === 200 });
    return;
  }

  const response = getJson(
    `${baseUrl}/api/v1/feedback?tripId=${state.tripId}`,
    {
      group: "sync",
      operation: "list-feedback-history",
      successStatuses: [200],
      acceptedDegradationStatuses: degradationStatuses(state),
    },
  );
  check(response, { "查询反馈历史成功": (item) => item.status === 200 });
}

function updateRequirement(state, baseUrl) {
  const response = jsonRequest(
    "PUT",
    `${baseUrl}/api/v1/trips/${state.tripId}/requirements`,
    requirementPayload(state.scenario, state.vu, state.sequence % 50),
    {
      group: "write",
      operation: "update-requirement",
      headers: idempotencyHeaders(state, "update-requirement"),
      successStatuses: [200],
      acceptedDegradationStatuses: degradationStatuses(state),
    },
  );
  check(response, { "更新旅行需求成功": (item) => item.status === 200 });
}

function submitFeedback(state, baseUrl) {
  const response = jsonRequest(
    "POST",
    `${baseUrl}/api/v1/feedback`,
    feedbackPayload(state.tripId, state.planId, state.vu, state.sequence),
    {
      group: "write",
      operation: "submit-feedback",
      headers: idempotencyHeaders(state, "submit-feedback"),
      successStatuses: [201],
      acceptedDegradationStatuses: degradationStatuses(state),
    },
  );
  check(response, { "提交反馈成功": (item) => item.status === 201 });
}

function correctBooking(state, baseUrl) {
  if (!state.documentId || !state.bookingId) {
    uploadDocument(state, baseUrl);
  }
  if (!state.documentId || !state.bookingId) {
    return;
  }

  const response = jsonRequest(
    "PATCH",
    `${baseUrl}/api/v1/documents/${state.documentId}/bookings/${state.bookingId}`,
    correctionPayload(),
    {
      group: "write",
      operation: "correct-ocr",
      headers: {
        ...idempotencyHeaders(state, "correct-ocr"),
        "X-Actor-Id": `qa-stage5-actor-${state.vu}`,
      },
      successStatuses: [200],
      acceptedDegradationStatuses: degradationStatuses(state),
    },
  );
  check(response, { "人工修正 OCR 成功": (item) => item.status === 200 });
}

function confirmPlan(state, baseUrl) {
  if (!state.planId) {
    createPlan(state, baseUrl);
  }
  if (!state.planId || state.confirmed) {
    updateRequirement(state, baseUrl);
    return;
  }

  const plan = getJson(`${baseUrl}/api/v1/plans/${state.planId}`, {
    group: "sync",
    operation: "get-plan",
    successStatuses: [200],
    acceptedDegradationStatuses: degradationStatuses(state),
  });
  if (plan.status !== 200) {
    return;
  }
  state.planVersion = safeJson(plan)?.version;
  const response = jsonRequest(
    "POST",
    `${baseUrl}/api/v1/plans/${state.planId}/confirm`,
    { expectedVersion: state.planVersion },
    {
      group: "write",
      operation: "confirm-plan",
      headers: idempotencyHeaders(state, "confirm-plan"),
      successStatuses: [200],
      acceptedDegradationStatuses: [409, ...degradationStatuses(state)],
    },
  );
  state.confirmed = response.status === 200;
  check(response, {
    "确认计划返回成功或明确冲突": (item) =>
      item.status === 200 || item.status === 409,
  });
}

export function writeOperation(state, baseUrl, selector) {
  if (!ensureTrip(state, baseUrl)) {
    return;
  }
  const operation = selector % 4;
  if (operation === 0) {
    updateRequirement(state, baseUrl);
  } else if (operation === 1) {
    submitFeedback(state, baseUrl);
  } else if (operation === 2) {
    correctBooking(state, baseUrl);
  } else {
    confirmPlan(state, baseUrl);
  }
}

export function uploadDocument(state, baseUrl) {
  if (!ensureTrip(state, baseUrl) || !state.fixtureBytes) {
    return;
  }
  const response = request(
    "POST",
    `${baseUrl}/api/v1/documents/parse`,
    {
      tripId: state.tripId,
      file: http.file(
        state.fixtureBytes,
        `low-confidence-${state.vu}-${state.sequence}.png`,
        "image/png",
      ),
    },
    {
      group: "async-submit",
      operation: "upload-document",
      headers: idempotencyHeaders(state, "upload-document"),
      successStatuses: [202],
      acceptedDegradationStatuses: degradationStatuses(state),
    },
  );
  const accepted = safeJson(response);
  if (response.status !== 202 || !validIdentifier(accepted?.taskId)) {
    return;
  }
  recordAcceptedTask(state, accepted, {
    allowExistingTask: true,
    allowExistingResource: true,
  });
  state.taskId = accepted.taskId;
  state.documentId = accepted.resourceId;
  const task = pollTask(state, baseUrl, state.taskId);
  if (
    task?.status !== "WAITING_CONFIRMATION" &&
    task?.status !== "SUCCEEDED" &&
    task?.status !== "PARTIAL_SUCCESS"
  ) {
    return;
  }
  const document = getJson(
    `${baseUrl}/api/v1/documents/${state.documentId}`,
    {
      group: "sync",
      operation: "get-document",
      successStatuses: [200],
      acceptedDegradationStatuses: degradationStatuses(state),
    },
  );
  const bookings = safeJson(document)?.bookings || [];
  if (bookings.length > 0) {
    state.bookingId = bookings[0].bookingId;
  }
}

export function createPlan(state, baseUrl) {
  if (!ensureTrip(state, baseUrl)) {
    return;
  }
  const response = jsonRequest(
    "POST",
    `${baseUrl}/api/v1/plans`,
    {
      tripId: state.tripId,
      title: `合成并发方案-${state.scenario}-${state.vu}`,
    },
    {
      group: "async-submit",
      operation: "create-plan",
      headers: idempotencyHeaders(state, "create-plan"),
      successStatuses: [202],
      acceptedDegradationStatuses: degradationStatuses(state),
    },
  );
  const accepted = safeJson(response);
  if (response.status !== 202 || !validIdentifier(accepted?.taskId)) {
    return;
  }
  recordAcceptedTask(state, accepted);
  state.taskId = accepted.taskId;
  state.planId = accepted.resourceId;
  const task = pollTask(state, baseUrl, state.taskId);
  if (task?.resultResourceIds?.length > 0) {
    state.planId = task.resultResourceIds[0];
  }
}

function replan(state, baseUrl) {
  if (!state.planId) {
    createPlan(state, baseUrl);
  }
  if (!state.planId) {
    return;
  }
  const response = jsonRequest(
    "POST",
    `${baseUrl}/api/v1/plans/${state.planId}/replan`,
    replanPayload(),
    {
      group: "async-submit",
      operation: "replan",
      headers: idempotencyHeaders(state, "replan"),
      successStatuses: [202],
      acceptedDegradationStatuses: [409, ...degradationStatuses(state)],
    },
  );
  const accepted = safeJson(response);
  if (response.status !== 202 || !validIdentifier(accepted?.taskId)) {
    return;
  }
  recordAcceptedTask(state, accepted);
  state.taskId = accepted.taskId;
  const task = pollTask(state, baseUrl, state.taskId);
  if (task?.resultResourceIds?.length > 0) {
    state.planId = task.resultResourceIds[0];
  }
}

function createAndCancelTask(state, baseUrl) {
  if (!ensureTrip(state, baseUrl)) {
    return;
  }
  const response = jsonRequest(
    "POST",
    `${baseUrl}/api/v1/plans`,
    { tripId: state.tripId, title: "合成取消任务" },
    {
      group: "async-submit",
      operation: "create-cancellable-task",
      headers: idempotencyHeaders(state, "create-cancellable-task"),
      successStatuses: [202],
      acceptedDegradationStatuses: degradationStatuses(state),
    },
  );
  const accepted = safeJson(response);
  if (response.status !== 202 || !validIdentifier(accepted?.taskId)) {
    return;
  }
  recordAcceptedTask(state, accepted);
  state.taskId = accepted.taskId;
  const cancelled = request(
    "POST",
    `${baseUrl}/api/v1/tasks/${state.taskId}/cancel`,
    null,
    {
      group: "async-submit",
      operation: "cancel-task",
      headers: idempotencyHeaders(state, "cancel-task"),
      successStatuses: [200, 409],
      acceptedDegradationStatuses: degradationStatuses(state),
    },
  );
  check(cancelled, {
    "取消任务成功或返回明确终态冲突": (item) =>
      item.status === 200 || item.status === 409,
  });
}

export function asyncOperation(state, baseUrl, selector) {
  const operation = selector % 4;
  if (operation === 0) {
    uploadDocument(state, baseUrl);
  } else if (operation === 1) {
    createPlan(state, baseUrl);
  } else if (operation === 2) {
    replan(state, baseUrl);
  } else {
    createAndCancelTask(state, baseUrl);
  }
}

export function runWeightedIteration(state, baseUrl, sequence) {
  const syncWeight = Number(__ENV.QA_SYNC_WEIGHT || "70");
  const writeWeight = Number(__ENV.QA_WRITE_WEIGHT || "20");
  const asyncWeight = Number(__ENV.QA_ASYNC_WEIGHT || "10");
  if (syncWeight + writeWeight + asyncWeight !== 100) {
    fail("QA_SYNC_WEIGHT、QA_WRITE_WEIGHT 和 QA_ASYNC_WEIGHT 之和必须为 100");
  }

  const selector = (sequence * 17 + state.vu * 13) % 100;
  if (selector < syncWeight) {
    readOperation(state, baseUrl, sequence);
  } else if (selector < syncWeight + writeWeight) {
    writeOperation(state, baseUrl, sequence);
  } else {
    asyncOperation(state, baseUrl, sequence);
  }
  sleep(Number(__ENV.QA_THINK_TIME_SECONDS || "0.2"));
}
