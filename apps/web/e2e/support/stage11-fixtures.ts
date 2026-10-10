import type { Page, Route } from "@playwright/test";

export const stage11Ids = {
  trip: "018f0e31-6ec8-7a59-b871-0242ac131001",
  currentPlan: "018f0e31-6ec8-7a59-b871-0242ac131020",
  slowPlan: "018f0e31-6ec8-7a59-b871-0242ac131021",
  lowTransitPlan: "018f0e31-6ec8-7a59-b871-0242ac131022",
  family: "018f0e31-6ec8-7a59-b871-0242ac131100",
  lowTransitFamily: "018f0e31-6ec8-7a59-b871-0242ac131101",
  replanTask: "018f0e31-6ec8-7a59-b871-0242ac131200",
  replanPlan: "018f0e31-6ec8-7a59-b871-0242ac131201",
  action: "018f0e31-6ec8-7a59-b871-0242ac131300",
  task: "018f0e31-6ec8-7a59-b871-0242ac131400",
} as const;

const now = "2026-09-16T08:00:00Z";
const mapTilePng = Buffer.from(
  "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
  "base64",
);

function stableId(offset: number) {
  return `018f0e31-6ec8-7a59-b871-${(0x242ac131000 + offset)
    .toString(16)
    .padStart(12, "0")}`;
}

function dateFor(dayIndex: number) {
  return `2026-10-${String(dayIndex + 1).padStart(2, "0")}`;
}

function instantFor(dayIndex: number, itemIndex: number, minutesOffset = 0) {
  const date = new Date(
    Date.UTC(2026, 9, dayIndex + 1, 8 + Math.floor(itemIndex / 2), (itemIndex % 2) * 30),
  );
  date.setUTCMinutes(date.getUTCMinutes() + minutesOffset);
  return date.toISOString();
}

function longText(seed: number) {
  return [
    `这是阶段十一合成长文本 ${seed}，用于验证窄屏、200% 缩放和深色模式下的换行能力。`,
    "推荐理由包含交通换乘、开放时间、同行人休息、预算约束和证据覆盖说明。",
    "文本必须保持可读，不能遮挡后续内容，也不能把内部标识暴露给用户。",
  ].join("");
}

function buildEvidence() {
  const sourceTypes = [
    "PUBLIC_KNOWLEDGE",
    "USER_MEMORY",
    "DOCUMENT",
    "PROVIDER",
    "GRAPH",
  ];
  return Array.from({ length: 100 }, (_, index) => ({
    evidenceId: stableId(600 + index),
    sourceType: sourceTypes[index % sourceTypes.length],
    sourceId: `stage11-source-${index + 1}`,
    title: `阶段十一证据 ${index + 1}：超长来源标题用于验证证据抽屉换行和批量展示稳定性`,
    excerpt: `${longText(index + 1)} 该条证据只使用脱敏合成内容。`,
    url: index % 4 === 0 ? `https://example.test/stage11/${index + 1}` : undefined,
    retrievedAt: now,
  }));
}

function buildItineraryItem(dayIndex: number, itemIndex: number) {
  const absoluteIndex = dayIndex * 30 + itemIndex;
  const types = [
    "TRANSPORT",
    "ATTRACTION",
    "DINING",
    "FREE_TIME",
    "OTHER",
    "ACCOMMODATION",
  ];
  return {
    itemId: stableId(1000 + absoluteIndex),
    type: types[absoluteIndex % types.length],
    title: `第 ${dayIndex + 1} 天行程 ${itemIndex + 1}：包含非常长地点名称与推荐说明的稳定性样本`,
    description: longText(absoluteIndex + 1),
    startTime: instantFor(dayIndex, itemIndex),
    endTime: instantFor(dayIndex, itemIndex, 45),
    location: {
      placeId: stableId(2000 + absoluteIndex),
      name: `北京市东城区超长地点名称稳定性验证样本 ${dayIndex + 1}-${itemIndex + 1}`,
      address: "北京市东城区中轴线附近一段非常长但脱敏的地址描述，用于验证移动端换行",
      latitude: 39.9 + dayIndex * 0.006 + itemIndex * 0.0008,
      longitude: 116.38 + dayIndex * 0.004 + itemIndex * 0.0009,
    },
    amount: 60 + (absoluteIndex % 8) * 35,
    currency: "CNY",
    evidenceIds: [stableId(600 + (absoluteIndex % 100))],
  };
}

function buildDays() {
  return Array.from({ length: 14 }, (_, dayIndex) => {
    const itemCount = dayIndex === 0 ? 30 : 3;
    return {
      dayNumber: dayIndex + 1,
      date: dateFor(dayIndex),
      title: `第 ${dayIndex + 1} 天稳定性行程`,
      items: Array.from({ length: itemCount }, (_, itemIndex) =>
        buildItineraryItem(dayIndex, itemIndex),
      ),
    };
  });
}

function buildConflicts(days: ReturnType<typeof buildDays>) {
  const allItemIds = days.flatMap((day) => day.items.map((item) => item.itemId));
  const severities = ["ERROR", "WARNING", "INFO"];
  const types = ["TIME", "BUDGET", "LOCATION", "AVAILABILITY", "PREFERENCE", "EVIDENCE"];
  return Array.from({ length: 50 }, (_, index) => ({
    conflictId: stableId(3000 + index),
    code: `STAGE11_RULE_${String(index + 1).padStart(2, "0")}`,
    type: types[index % types.length],
    severity: severities[index % severities.length],
    message: `阶段十一冲突 ${index + 1}：长文案用于验证非颜色状态、换行和屏幕阅读器可读性。`,
    fieldPath: `/days/${index % days.length}/items/${index % 3}`,
    relatedItemIds: [allItemIds[index % allItemIds.length]],
    autoFixable: index % 4 !== 0,
    status: "OPEN",
  }));
}

function buildBudget(days: ReturnType<typeof buildDays>) {
  const items = [
    ["TRANSPORT", "城市与城际交通"],
    ["ACCOMMODATION", "住宿"],
    ["DINING", "餐饮"],
    ["ATTRACTION", "景点门票"],
    ["FEES", "服务费用"],
    ["OTHER", "其他"],
  ].map(([category, description], index) => ({
    category,
    description,
    amount: 600 + index * 420,
    currency: "CNY",
  }));
  const estimatedTotal = items.reduce((total, item) => total + item.amount, 0);
  return {
    totalBudget: 18000,
    estimatedTotal,
    remainingBudget: 18000 - estimatedTotal,
    currency: "CNY",
    items,
    dailyBudgets: days.map((day, index) => ({
      date: day.date,
      amount: 900 + index * 25,
      currency: "CNY",
    })),
  };
}

function buildPlan(overrides: Record<string, unknown> = {}) {
  const days = buildDays();
  return {
    planId: stage11Ids.currentPlan,
    planFamilyId: stage11Ids.family,
    tripId: stage11Ids.trip,
    previousPlanId: stableId(4019),
    version: 20,
    alternativeKey: "balanced",
    strategy: "BALANCED",
    title: "阶段十一北京十四日大数据稳定性方案",
    summary: longText(999),
    days,
    budgetSummary: buildBudget(days),
    conflicts: buildConflicts(days),
    evidence: buildEvidence(),
    triggerReason: "STAGE11_STABILITY",
    replanInput: {
      lockedItemIds: [],
      removedItemIds: [],
      addedPreferences: [],
      naturalLanguageRequest: "",
    },
    changeSummary: "保留历史版本并验证长列表、长文本和异常网络。",
    autoReplanned: true,
    status: "READY",
    createdAt: now,
    updatedAt: now,
    ...overrides,
  };
}

export function buildStage11Dataset() {
  const current = buildPlan();
  const versions = Array.from({ length: 20 }, (_, index) =>
    buildPlan({
      planId: index === 19 ? stage11Ids.currentPlan : stableId(4000 + index),
      previousPlanId: index === 0 ? undefined : stableId(3999 + index),
      version: index + 1,
      title: `阶段十一北京十四日大数据稳定性方案 v${index + 1}`,
      autoReplanned: index > 0,
      conflicts:
        index === 18
          ? [
              {
                conflictId: stableId(5050),
                code: "STAGE11_RESOLVED",
                type: "TIME",
                severity: "WARNING",
                message: "上一版本已经解决的历史冲突。",
                fieldPath: "/days/0",
                relatedItemIds: [stableId(1000)],
                autoFixable: true,
                status: "RESOLVED",
              },
            ]
          : current.conflicts,
      evidence: current.evidence,
      days: current.days,
      budgetSummary: current.budgetSummary,
    }),
  );
  const lowTransit = buildPlan({
    planId: stage11Ids.lowTransitPlan,
    planFamilyId: stage11Ids.lowTransitFamily,
    previousPlanId: undefined,
    version: 1,
    alternativeKey: "low-transit",
    strategy: "LOW_TRANSIT",
    title: "阶段十一少通勤备选方案",
    summary: "按区域重排行程，用于验证候选方案切换。",
    conflicts: [],
  });
  const slowPlan = buildPlan({
    planId: stage11Ids.slowPlan,
    previousPlanId: stage11Ids.currentPlan,
    version: 21,
    title: "阶段十一慢响应版本",
  });
  const trip = {
    tripId: stage11Ids.trip,
    userId: "018f0e31-6ec8-7a59-b871-0242ac131999",
    title: "阶段十一北京十四日旅行",
    timeZone: "Asia/Shanghai",
    status: "READY",
    requirement: {
      tripId: stage11Ids.trip,
      origin: { name: "上海" },
      destinations: [{ name: "北京" }],
      startDate: "2026-10-01",
      endDate: "2026-10-14",
      travelers: [
        {
          travelerId: "018f0e31-6ec8-7a59-b871-0242ac131998",
          displayName: "旅行者 1",
          type: "ADULT",
          accessibilityNeeds: [],
        },
      ],
      totalBudget: 18000,
      currency: "CNY",
      interests: ["历史文化", "本地餐饮"],
      pace: "BALANCED",
      mustVisit: ["故宫博物院"],
      avoid: ["凌晨出发"],
      updatedAt: now,
    },
    createdAt: now,
    updatedAt: now,
  };
  return { current, versions, lowTransit, slowPlan, trip };
}

export interface Stage11RouteOptions {
  mapFailure?: boolean;
  failFirstPlanRequest?: boolean;
  failInitialPlanRequests?: number;
  holdFirstPlanRequest?: Promise<void>;
  holdReplan?: Promise<void>;
  onReplanRequest?: () => void;
}

async function fulfillJson(route: Route, status: number, body: unknown) {
  await route.fulfill({
    status,
    contentType: "application/json",
    body: JSON.stringify(body),
  });
}

export async function installStage11Routes(
  page: Page,
  options: Stage11RouteOptions = {},
) {
  const dataset = buildStage11Dataset();
  const plansById = new Map<string, Record<string, unknown>>();
  [...dataset.versions, dataset.lowTransit, dataset.slowPlan].forEach((plan) => {
    plansById.set(plan.planId as string, plan);
  });
  let planRequestCount = 0;

  await page.route("**/api/v1/health", (route) =>
    fulfillJson(route, 200, {
      status: "UP",
      service: "trip-agent-api",
      apiVersion: "v1",
      providerMode: "MOCK",
      timestamp: now,
    }),
  );
  await page.route("**/api/v1/system/status", (route) =>
    fulfillJson(route, 200, {
      status: "AVAILABLE",
      providerMode: "MOCK",
      modelProvider: "MOCK",
      developerToolsEnabled: true,
      dependencies: [],
      capabilities: [],
      checkedAt: now,
    }),
  );
  await page.route(/\/api\/v1\/maps\/tiles\/\d+\/\d+\/\d+\.png$/, (route) =>
    options.mapFailure
      ? route.fulfill({ status: 503, contentType: "text/plain", body: "map unavailable" })
      : route.fulfill({
          status: 200,
          contentType: "image/png",
          headers: { "Cache-Control": "public, max-age=3600" },
          body: mapTilePng,
        }),
  );
  await page.route(`**/api/v1/trips/${stage11Ids.trip}/plans`, (route) =>
    fulfillJson(route, 200, {
      items: [...dataset.versions, dataset.lowTransit, dataset.slowPlan],
    }),
  );
  await page.route(`**/api/v1/trips/${stage11Ids.trip}`, (route) =>
    fulfillJson(route, 200, dataset.trip),
  );
  await page.route(/\/api\/v1\/plans\/[^/]+\/compare\/[^/]+$/, (route) =>
    fulfillJson(route, 200, {
      planFamilyId: stage11Ids.family,
      fromPlanId: stableId(4019),
      toPlanId: stage11Ids.currentPlan,
      fromVersion: 19,
      toVersion: 20,
      budgetDelta: 220,
      addedItemIds: [stableId(1001)],
      removedItemIds: [],
      changedItemIds: [stableId(1000)],
      resolvedConflictCodes: ["STAGE11_RESOLVED"],
      introducedConflictCodes: ["STAGE11_RULE_01"],
      summary: "阶段十一版本对比。",
    }),
  );
  await page.route(/\/api\/v1\/plans\/[^/]+\/confirm$/, (route) =>
    fulfillJson(route, 200, { ...dataset.current, status: "CONFIRMED" }),
  );
  await page.route(/\/api\/v1\/plans\/[^/]+\/replan$/, async (route) => {
    options.onReplanRequest?.();
    if (options.holdReplan) {
      await options.holdReplan;
    }
    await fulfillJson(route, 202, {
      taskId: stage11Ids.replanTask,
      resourceId: stage11Ids.replanPlan,
      status: "QUEUED",
      traceId: "44444444444444444444444444444444",
      createdAt: now,
    });
  });
  await page.route(/\/api\/v1\/plans\/[^/]+$/, async (route) => {
    planRequestCount += 1;
    const failingPlanRequests = options.failInitialPlanRequests ?? (
      options.failFirstPlanRequest ? 1 : 0
    );
    if (planRequestCount <= failingPlanRequests) {
      await route.abort("timedout");
      return;
    }
    if (planRequestCount === 1 && options.holdFirstPlanRequest) {
      await options.holdFirstPlanRequest;
    }
    const planId = route.request().url().split("/").pop() ?? stage11Ids.currentPlan;
    await fulfillJson(route, 200, plansById.get(planId) ?? dataset.current);
  });
  await page.route("**/api/v1/actions", (route) =>
    fulfillJson(route, 201, {
      actionId: stage11Ids.action,
      type: "ORDER_CREATE",
      resourceId: stage11Ids.currentPlan,
      status: "PENDING_CONFIRMATION",
      riskDescription: "确认只授权后续预订流程，不会自动支付。",
      amount: 9800,
      currency: "CNY",
      expiresAt: "2026-09-16T08:15:00Z",
      proposedBy: "018f0e31-6ec8-7a59-b871-0242ac131999",
      confirmedBy: null,
      version: 0,
      auditTrail: [],
      createdAt: now,
      updatedAt: now,
    }),
  );
  await page.route(`**/api/v1/actions/${stage11Ids.action}/confirm`, (route) =>
    fulfillJson(route, 200, {
      actionId: stage11Ids.action,
      type: "ORDER_CREATE",
      resourceId: stage11Ids.currentPlan,
      status: "CONFIRMED",
      riskDescription: "确认只授权后续预订流程，不会自动支付。",
      amount: 9800,
      currency: "CNY",
      expiresAt: "2026-09-16T08:15:00Z",
      proposedBy: "018f0e31-6ec8-7a59-b871-0242ac131999",
      confirmedBy: "018f0e31-6ec8-7a59-b871-0242ac131999",
      version: 1,
      auditTrail: [],
      createdAt: now,
      updatedAt: now,
    }),
  );
  await page.route(`**/api/v1/tasks/${stage11Ids.replanTask}/events`, (route) =>
    route.fulfill({
      status: 200,
      contentType: "text/event-stream",
      body: "",
    }),
  );
  await page.route(`**/api/v1/tasks/${stage11Ids.replanTask}/agents`, (route) =>
    fulfillJson(route, 200, { items: [] }),
  );
  await page.route(`**/api/v1/tasks/${stage11Ids.replanTask}`, (route) =>
    fulfillJson(route, 200, {
      taskId: stage11Ids.replanTask,
      type: "PLAN_REPLAN",
      resourceId: stage11Ids.replanPlan,
      status: "SUCCEEDED",
      stage: "COMPLETED",
      progress: 100,
      message: "新版本已生成",
      traceId: "44444444444444444444444444444444",
      resultResourceIds: [stage11Ids.replanPlan],
      retryable: false,
      recoveryAttempts: 0,
      degradedComponents: [],
      createdAt: now,
      updatedAt: now,
    }),
  );
  await installStage11TaskRoutes(page);
  return dataset;
}

export async function installStage11TaskRoutes(page: Page) {
  let taskPolls = 0;
  await page.route(`**/api/v1/tasks/${stage11Ids.task}/events`, (route) =>
    route.abort("connectionreset"),
  );
  await page.route(`**/api/v1/tasks/${stage11Ids.task}/agents`, (route) =>
    fulfillJson(route, 200, {
      items: [
        {
          agentExecutionId: stableId(7000),
          taskId: stage11Ids.task,
          agentName: "RequirementAgent",
          status: "SUCCEEDED",
          summary: "旅行需求已完成结构化",
          evidenceCount: 8,
          warnings: [],
          durationMs: 120,
          startedAt: now,
          completedAt: now,
        },
      ],
    }),
  );
  await page.route(`**/api/v1/tasks/${stage11Ids.task}`, (route) => {
    taskPolls += 1;
    return fulfillJson(route, 200, {
      taskId: stage11Ids.task,
      type: "PLAN_GENERATION",
      resourceId: stage11Ids.currentPlan,
      status: taskPolls > 1 ? "SUCCEEDED" : "RUNNING",
      stage: taskPolls > 1 ? "COMPLETED" : "AGENTS_RUNNING",
      progress: taskPolls > 1 ? 100 : 55,
      message: taskPolls > 1 ? "方案生成完成" : "正在协同行程方案",
      traceId: "55555555555555555555555555555555",
      resultResourceIds: taskPolls > 1 ? [stage11Ids.currentPlan] : [],
      retryable: false,
      recoveryAttempts: 0,
      degradedComponents: [],
      createdAt: now,
      updatedAt: now,
    });
  });
}
