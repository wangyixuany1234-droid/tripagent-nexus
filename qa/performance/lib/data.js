const SCENARIO_CODES = {
  smoke: 11,
  load: 12,
  stress: 13,
  spike: 14,
  soak: 15,
  sse: 16,
  idempotency: 17,
  race: 18,
  canary: 19,
};

function suffix(value) {
  return String(value % 1000000000000).padStart(12, "0");
}

export function syntheticUuid(kind, scenario, vu, sequence = 0) {
  const code = SCENARIO_CODES[scenario] || 99;
  const kindCode = {
    user: 1,
    traveler: 2,
    request: 3,
  }[kind];
  const value = code * 10000000000 + kindCode * 100000000 + vu * 10000 + sequence;
  return `${kindCode}0000000-0000-4000-8000-${suffix(value)}`;
}

export function idempotencyKey(operation, scenario, vu, sequence) {
  const sanitizedRunId = (__ENV.QA_RUN_ID || "local").replace(
    /[^A-Za-z0-9._-]/g,
    "-",
  );
  const runToken =
    sanitizedRunId.length <= 48
      ? sanitizedRunId
      : `${sanitizedRunId.slice(0, 23)}-${sanitizedRunId.slice(-24)}`;
  return `qa-stage5-${runToken}-${scenario}-${operation}-${vu}-${sequence}`;
}

export function createTripPayload(scenario, vu) {
  return {
    userId: syntheticUuid("user", scenario, vu),
    title: `合成负载行程-${scenario}-${vu}`,
    timeZone: "Asia/Shanghai",
  };
}

export function requirementPayload(scenario, vu, budgetOffset = 0) {
  return {
    origin: { name: "上海" },
    destinations: [{ name: "北京" }],
    startDate: "2026-10-01",
    endDate: "2026-10-03",
    travelers: [
      {
        travelerId: syntheticUuid("traveler", scenario, vu),
        displayName: `合成旅行者-${vu}`,
        type: "ADULT",
        accessibilityNeeds: [],
      },
    ],
    totalBudget: 6000 + budgetOffset,
    currency: "CNY",
    interests: ["历史文化", "本地餐饮"],
    pace: "BALANCED",
    mustVisit: ["故宫博物院"],
    avoid: ["凌晨出发"],
    freeTextConstraints: `合成性能测试约束-${scenario}-${vu}-${budgetOffset}`,
  };
}

export function feedbackPayload(tripId, planId, vu, sequence) {
  const payload = {
    tripId,
    rating: 4,
    comment: `合成性能反馈-${vu}-${sequence}`,
    tags: ["路线安排合理"],
    sentiment: "LIKE",
  };
  if (planId) {
    payload.planId = planId;
    payload.targetType = "PLAN";
    payload.targetId = `synthetic-plan-${vu}`;
  } else {
    payload.targetType = "PLACE";
    payload.targetId = `synthetic-place-${vu}`;
  }
  return payload;
}

export function correctionPayload() {
  return {
    corrections: [
      { field: "startTime", value: "2026-10-01T07:00:00Z" },
      { field: "endTime", value: "2026-10-03T02:00:00Z" },
    ],
    reason: "合成性能测试人工修正",
  };
}

export function replanPayload() {
  return {
    reason: "降低通勤时间",
    freeTextConstraints: "保留上午活动并减少跨城区移动。",
    lockedItemIds: [],
    removedItemIds: [],
    adjustedTotalBudget: 6200,
    adjustedPace: "BALANCED",
    addedPreferences: ["午间休息"],
    naturalLanguageRequest: "使用合成数据生成低通勤版本。",
  };
}
