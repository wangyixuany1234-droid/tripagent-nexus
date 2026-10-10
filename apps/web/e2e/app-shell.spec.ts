import { expect, test, type Page, type Route } from "@playwright/test";

const mapTilePng = Buffer.from(
  "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
  "base64",
);

const healthResponse = {
  status: "UP",
  service: "trip-agent-api",
  apiVersion: "v1",
  providerMode: "MOCK",
  timestamp: "2026-09-06T08:00:00Z",
};

const resultIds = {
  trip: "018f0e31-6ec8-7a59-b871-0242ac121001",
  balanced: "018f0e31-6ec8-7a59-b871-0242ac121002",
  balancedV1: "018f0e31-6ec8-7a59-b871-0242ac121003",
  balancedFamily: "018f0e31-6ec8-7a59-b871-0242ac121004",
  lowTransit: "018f0e31-6ec8-7a59-b871-0242ac121005",
  lowTransitFamily: "018f0e31-6ec8-7a59-b871-0242ac121006",
  attraction: "018f0e31-6ec8-7a59-b871-0242ac121007",
  dining: "018f0e31-6ec8-7a59-b871-0242ac121008",
  transport: "018f0e31-6ec8-7a59-b871-0242ac121009",
  conflict: "018f0e31-6ec8-7a59-b871-0242ac121010",
  evidence: "018f0e31-6ec8-7a59-b871-0242ac121011",
  replanTask: "018f0e31-6ec8-7a59-b871-0242ac121012",
  replanPlan: "018f0e31-6ec8-7a59-b871-0242ac121013",
  action: "018f0e31-6ec8-7a59-b871-0242ac121014",
};

const resultNow = "2026-09-06T08:00:00Z";

function itineraryItem(
  itemId: string,
  type: string,
  title: string,
  startTime: string,
  endTime: string,
  amount: number,
  location: Record<string, unknown>,
  evidenceIds: string[] = [],
) {
  return {
    itemId,
    type,
    title,
    description: `${title} 的推荐原因与行程约束说明。`,
    startTime,
    endTime,
    location,
    amount,
    currency: "CNY",
    evidenceIds,
  };
}

function resultPlan(
  overrides: Record<string, unknown> = {},
): Record<string, unknown> {
  return {
    planId: resultIds.balanced,
    planFamilyId: resultIds.balancedFamily,
    tripId: resultIds.trip,
    previousPlanId: resultIds.balancedV1,
    version: 2,
    alternativeKey: "balanced",
    strategy: "BALANCED",
    title: "北京三日均衡探索",
    summary: "兼顾历史文化、本地餐饮、交通时间和预算。",
    days: [
      {
        dayNumber: 1,
        date: "2026-10-01",
        title: "城市中轴线",
        items: [
          itineraryItem(
            resultIds.transport,
            "TRANSPORT",
            "抵达北京",
            "2026-10-01T01:00:00Z",
            "2026-10-01T03:00:00Z",
            500,
            { name: "北京站", address: "东城区", latitude: 39.9028, longitude: 116.427 },
            [resultIds.evidence],
          ),
          itineraryItem(
            resultIds.attraction,
            "ATTRACTION",
            "故宫博物院",
            "2026-10-01T04:00:00Z",
            "2026-10-01T07:00:00Z",
            60,
            {
              name: "故宫博物院",
              address: "东城区景山前街 4 号",
              latitude: 39.9163,
              longitude: 116.3972,
            },
            [resultIds.evidence],
          ),
        ],
      },
      {
        dayNumber: 2,
        date: "2026-10-02",
        title: "胡同与本地餐饮",
        items: [
          itineraryItem(
            resultIds.dining,
            "DINING",
            "东城本地餐厅",
            "2026-10-02T10:00:00Z",
            "2026-10-02T11:30:00Z",
            200,
            {
              name: "东城本地餐厅",
              address: "东城区",
              latitude: 39.9234,
              longitude: 116.4176,
            },
          ),
        ],
      },
    ],
    budgetSummary: {
      totalBudget: 5000,
      estimatedTotal: 3760,
      remainingBudget: 1240,
      currency: "CNY",
      items: [
        { category: "TRANSPORT", description: "城际交通", amount: 500, currency: "CNY" },
        { category: "ACCOMMODATION", description: "酒店", amount: 2800, currency: "CNY" },
        { category: "ATTRACTION", description: "故宫门票", amount: 60, currency: "CNY" },
        { category: "DINING", description: "餐饮", amount: 400, currency: "CNY" },
      ],
      dailyBudgets: [
        { date: "2026-10-01", amount: 3360, currency: "CNY" },
        { date: "2026-10-02", amount: 400, currency: "CNY" },
      ],
    },
    conflicts: [
      {
        conflictId: resultIds.conflict,
        code: "COMMUTE_TIME_INSUFFICIENT",
        type: "TIME",
        severity: "WARNING",
        message: "故宫与下一活动之间的通勤时间不足。",
        fieldPath: "/days/0/items/1",
        relatedItemIds: [resultIds.attraction],
        autoFixable: true,
        status: "OPEN",
      },
    ],
    evidence: [
      {
        evidenceId: resultIds.evidence,
        sourceType: "PUBLIC_KNOWLEDGE",
        sourceId: "kb-forbidden-city",
        title: "故宫开放与参观信息",
        excerpt: "建议按预约时段入场，并预留完整参观时间。",
        retrievedAt: resultNow,
      },
    ],
    triggerReason: "INITIAL_GENERATION",
    replanInput: {
      lockedItemIds: [],
      removedItemIds: [],
      addedPreferences: [],
      naturalLanguageRequest: "",
    },
    changeSummary: "自动调整首日通勤安排。",
    autoReplanned: true,
    status: "READY",
    createdAt: resultNow,
    updatedAt: resultNow,
    ...overrides,
  };
}

async function installResultRoutes(page: Page) {
  let status = "READY";
  let replanAttempts = 0;
  const balanced = resultPlan();
  const previous = resultPlan({
    planId: resultIds.balancedV1,
    previousPlanId: undefined,
    version: 1,
    autoReplanned: false,
    conflicts: [
      {
        conflictId: resultIds.conflict,
        code: "COMMUTE_TIME_INSUFFICIENT",
        type: "TIME",
        severity: "ERROR",
        message: "首日通勤时间不足。",
        fieldPath: "/days/0",
        relatedItemIds: [resultIds.attraction],
        autoFixable: true,
        status: "RESOLVED",
      },
    ],
  });
  const lowTransit = resultPlan({
    planId: resultIds.lowTransit,
    planFamilyId: resultIds.lowTransitFamily,
    previousPlanId: undefined,
    version: 1,
    alternativeKey: "low-transit",
    strategy: "LOW_TRANSIT",
    title: "北京三日少通勤",
    summary: "按区域组织活动，减少每日跨区移动。",
    budgetSummary: {
      ...(balanced.budgetSummary as Record<string, unknown>),
      estimatedTotal: 3920,
      remainingBudget: 1080,
    },
    conflicts: [],
  });

  await page.route(`**/api/v1/plans/${resultIds.balanced}`, async (route) => {
    if (route.request().method() !== "GET") {
      await route.fallback();
      return;
    }
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({ ...balanced, status }),
    });
  });
  await page.route(`**/api/v1/trips/${resultIds.trip}/plans`, (route) =>
    route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({ items: [{ ...balanced, status }, previous, lowTransit] }),
    }),
  );
  await page.route(`**/api/v1/trips/${resultIds.trip}`, (route) =>
    route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        tripId: resultIds.trip,
        userId: "018f0e31-6ec8-7a59-b871-0242ac121099",
        title: "北京三日旅行",
        timeZone: "Asia/Shanghai",
        status: "READY",
        requirement: {
          tripId: resultIds.trip,
          origin: { name: "上海" },
          destinations: [{ name: "北京" }],
          startDate: "2026-10-01",
          endDate: "2026-10-03",
          travelers: [
            {
              travelerId: "018f0e31-6ec8-7a59-b871-0242ac121098",
              displayName: "旅行者 1",
              type: "ADULT",
              accessibilityNeeds: [],
            },
          ],
          totalBudget: 5000,
          currency: "CNY",
          interests: ["历史文化"],
          pace: "BALANCED",
          mustVisit: ["故宫博物院"],
          avoid: ["凌晨出发"],
          updatedAt: resultNow,
        },
        createdAt: resultNow,
        updatedAt: resultNow,
      }),
    }),
  );
  await page.route(
    `**/api/v1/plans/${resultIds.balancedV1}/compare/${resultIds.balanced}`,
    (route) =>
      route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify({
          planFamilyId: resultIds.balancedFamily,
          fromPlanId: resultIds.balancedV1,
          toPlanId: resultIds.balanced,
          fromVersion: 1,
          toVersion: 2,
          budgetDelta: -200,
          addedItemIds: [],
          removedItemIds: [],
          changedItemIds: [resultIds.attraction],
          resolvedConflictCodes: ["COMMUTE_TIME_INSUFFICIENT"],
          introducedConflictCodes: [],
          summary: "Resolved commute conflict.",
        }),
      }),
  );
  await page.route(`**/api/v1/plans/${resultIds.balanced}/confirm`, async (route) => {
    status = "CONFIRMED";
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({ ...balanced, status }),
    });
  });
  await page.route(`**/api/v1/plans/${resultIds.balanced}/replan`, async (route) => {
    replanAttempts += 1;
    if (replanAttempts === 1) {
      await route.fulfill({
        status: 409,
        contentType: "application/json",
        body: JSON.stringify({
          code: "PLAN_VERSION_CONFLICT",
          message: "Plan version changed.",
          details: [],
          traceId: "33333333333333333333333333333333",
          timestamp: resultNow,
        }),
      });
      return;
    }
    await route.fulfill({
      status: 202,
      contentType: "application/json",
      body: JSON.stringify({
        taskId: resultIds.replanTask,
        resourceId: resultIds.replanPlan,
        status: "QUEUED",
        traceId: "44444444444444444444444444444444",
        createdAt: resultNow,
      }),
    });
  });
  await page.route(`**/api/v1/tasks/${resultIds.replanTask}/events`, (route) =>
    route.fulfill({
      status: 200,
      contentType: "text/event-stream",
      body: `event: task-progress\ndata: ${JSON.stringify({
        eventId: "018f0e31-6ec8-7a59-b871-0242ac121015",
        taskId: resultIds.replanTask,
        stage: "COMPLETED",
        progress: 100,
        message: "新版本已生成",
        timestamp: resultNow,
      })}\n\n`,
    }),
  );
  await page.route(`**/api/v1/tasks/${resultIds.replanTask}/agents`, (route) =>
    route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({ items: [] }),
    }),
  );
  await page.route(`**/api/v1/tasks/${resultIds.replanTask}`, (route) =>
    route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        taskId: resultIds.replanTask,
        type: "PLAN_REPLAN",
        resourceId: resultIds.replanPlan,
        status: "SUCCEEDED",
        stage: "COMPLETED",
        progress: 100,
        message: "新版本已生成",
        traceId: "44444444444444444444444444444444",
        resultResourceIds: [resultIds.replanPlan],
        retryable: false,
        recoveryAttempts: 0,
        degradedComponents: [],
        createdAt: resultNow,
        updatedAt: resultNow,
      }),
    }),
  );
  await page.route("**/api/v1/actions", (route) =>
    route.fulfill({
      status: 201,
      contentType: "application/json",
      body: JSON.stringify({
        actionId: resultIds.action,
        type: "ORDER_CREATE",
        resourceId: resultIds.balanced,
        status: "PENDING_CONFIRMATION",
        riskDescription: "确认只授权后续预订流程，不会自动支付。",
        amount: 3760,
        currency: "CNY",
        expiresAt: "2026-09-06T08:15:00Z",
        proposedBy: "018f0e31-6ec8-7a59-b871-0242ac121099",
        confirmedBy: null,
        version: 0,
        auditTrail: [],
        createdAt: resultNow,
        updatedAt: resultNow,
      }),
    }),
  );
  await page.route(`**/api/v1/actions/${resultIds.action}/confirm`, (route) =>
    route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        actionId: resultIds.action,
        type: "ORDER_CREATE",
        resourceId: resultIds.balanced,
        status: "CONFIRMED",
        riskDescription: "确认只授权后续预订流程，不会自动支付。",
        amount: 3760,
        currency: "CNY",
        expiresAt: "2026-09-06T08:15:00Z",
        proposedBy: "018f0e31-6ec8-7a59-b871-0242ac121099",
        confirmedBy: "018f0e31-6ec8-7a59-b871-0242ac121099",
        version: 1,
        auditTrail: [],
        createdAt: resultNow,
        updatedAt: resultNow,
      }),
    }),
  );
}

test.beforeEach(async ({ page }) => {
  await page.route("**/api/v1/health", (route) =>
    route.fulfill({
      status: 200,
      contentType: "application/json",
      headers: { "X-Trace-Id": "4bf92f3577b34da6a3ce929d0e0e4736" },
      body: JSON.stringify(healthResponse),
    }),
  );
  await page.route("**/api/v1/system/status", (route) =>
    route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        status: "AVAILABLE",
        providerMode: "MOCK",
        modelProvider: "MOCK",
        developerToolsEnabled: true,
        dependencies: [
          { key: "database", label: "数据存储", status: "AVAILABLE" },
          { key: "cache", label: "会话与任务状态", status: "AVAILABLE" },
          { key: "messaging", label: "异步任务处理", status: "AVAILABLE" },
          { key: "retrieval", label: "旅行知识检索", status: "AVAILABLE" },
        ],
        capabilities: [
          { key: "model", label: "智能规划", status: "AVAILABLE" },
          { key: "map", label: "地图", status: "AVAILABLE" },
          { key: "weather", label: "天气信息", status: "AVAILABLE" },
          { key: "retrieval", label: "资料检索", status: "AVAILABLE" },
        ],
        checkedAt: "2026-09-07T08:00:00Z",
      }),
    }),
  );
  await page.route(/\/api\/v1\/maps\/tiles\/\d+\/\d+\/\d+\.png$/, (route) =>
    route.fulfill({
      status: 200,
      contentType: "image/png",
      headers: { "Cache-Control": "public, max-age=3600" },
      body: mapTilePng,
    }),
  );
});

test("desktop shell connects through the generated health client and switches theme", async ({
  page,
  isMobile,
}) => {
  test.skip(Boolean(isMobile), "desktop project only");
  await page.goto("/");

  await expect(page.getByRole("heading", { name: "TripAgent Nexus", level: 1 })).toBeVisible();
  await expect(page.getByText("当前展示演示数据")).toBeVisible();
  await expect(page.getByText("后端已连接")).toHaveCount(0);
  await expect(page.getByText("MOCK", { exact: true })).toHaveCount(0);

  await page.getByRole("button", { name: "打开用户菜单" }).click();
  await page.getByText("深色", { exact: true }).click();
  await expect(page.locator("html")).toHaveClass(/dark/);

  const overflow = await page.evaluate(
    () => document.documentElement.scrollWidth - window.innerWidth,
  );
  expect(overflow).toBeLessThanOrEqual(0);
});

test("completes the Beijing three-day planning and feedback journey", async ({
  page,
  isMobile,
}) => {
  test.skip(Boolean(isMobile), "desktop project only");
  const blockingConsoleErrors: string[] = [];
  page.on("console", (message) => {
    if (message.type() === "error") blockingConsoleErrors.push(message.text());
  });
  page.on("pageerror", (error) => blockingConsoleErrors.push(error.message));
  await installResultRoutes(page);
  const tripId = resultIds.trip;
  const documentId = "018f0e31-6ec8-7a59-b871-0242ac120102";
  const documentTaskId = "018f0e31-6ec8-7a59-b871-0242ac120103";
  const planId = resultIds.balanced;
  const planTaskId = "018f0e31-6ec8-7a59-b871-0242ac120105";
  const now = "2026-09-06T08:00:00Z";
  let requirementBody: Record<string, unknown> = {};
  let correctionBody: Record<string, unknown> | undefined;

  await page.route("**/api/v1/trips", async (route) => {
    if (route.request().method() !== "POST") {
      await route.fallback();
      return;
    }
    const request = route.request().postDataJSON();
    await route.fulfill({
      status: 201,
      contentType: "application/json",
      body: JSON.stringify({
        tripId,
        ...request,
        status: "DRAFT",
        createdAt: now,
        updatedAt: now,
      }),
    });
  });
  await page.route(`**/api/v1/trips/${tripId}/requirements`, async (route) => {
    requirementBody = route.request().postDataJSON();
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({ tripId, ...requirementBody, updatedAt: now }),
    });
  });
  await page.route(`**/api/v1/trips/${tripId}`, (route) =>
    route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        tripId,
        userId: "018f0e31-6ec8-7a59-b871-0242ac120100",
        title: "北京三日文化漫游",
        timeZone: "Asia/Shanghai",
        status: "READY",
        requirement: { tripId, ...requirementBody, updatedAt: now },
        createdAt: now,
        updatedAt: now,
      }),
    }),
  );
  await page.route("**/api/v1/documents/parse", (route) =>
    route.fulfill({
      status: 202,
      contentType: "application/json",
      body: JSON.stringify({
        taskId: documentTaskId,
        resourceId: documentId,
        status: "QUEUED",
        traceId: "11111111111111111111111111111111",
        createdAt: now,
      }),
    }),
  );
  await page.route(`**/api/v1/documents/${documentId}`, (route) =>
    route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        documentId,
        tripId,
        fileName: "beijing-hotel-confirmation.pdf",
        mediaType: "application/pdf",
        sizeBytes: 128,
        sha256: "a".repeat(64),
        pageCount: 1,
        status: "NEEDS_REVIEW",
        bookings: [
          {
            bookingId: "018f0e31-6ec8-7a59-b871-0242ac120106",
            type: "HOTEL",
            sourceDocumentId: documentId,
            hotelName: "北京中轴线酒店",
            confidence: 0.72,
            missingFields: ["startTime"],
            reviewStatus: "NEEDS_REVIEW",
            fieldEvidence: [
              {
                field: "hotelName",
                confidence: 0.96,
                status: "EXTRACTED",
                textEvidence: "北京中轴线酒店",
                region: { page: 1, x: 0.1, y: 0.2, width: 0.3, height: 0.08 },
              },
              { field: "startTime", confidence: 0, status: "MISSING" },
            ],
            validationIssues: [
              {
                code: "LOW_CONFIDENCE_CRITICAL_FIELD",
                field: "startTime",
                message: "入住时间需要人工确认",
              },
            ],
            corrections: [],
          },
        ],
        createdAt: now,
        updatedAt: now,
      }),
    }),
  );
  await page.route(`**/api/v1/documents/${documentId}/bookings/*`, async (route) => {
    correctionBody = route.request().postDataJSON();
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        bookingId: "018f0e31-6ec8-7a59-b871-0242ac120106",
        type: "HOTEL",
        sourceDocumentId: documentId,
        hotelName: "北京中轴线酒店",
        startTime: "2026-10-01T07:00:00Z",
        confidence: 1,
        missingFields: [],
        reviewStatus: "CORRECTED",
        fieldEvidence: [],
        validationIssues: [],
        corrections: [],
      }),
    });
  });
  await page.route(`**/api/v1/tasks/${documentTaskId}/events`, (route) =>
    route.fulfill({
      status: 200,
      contentType: "text/event-stream",
      body: `event: task-progress\ndata: ${JSON.stringify({
        eventId: "018f0e31-6ec8-7a59-b871-0242ac120107",
        taskId: documentTaskId,
        stage: "NEEDS_REVIEW",
        progress: 90,
        message: "等待字段确认",
        timestamp: now,
      })}\n\n`,
    }),
  );
  await page.route(`**/api/v1/tasks/${documentTaskId}`, (route) =>
    route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        taskId: documentTaskId,
        type: "DOCUMENT_PARSE",
        resourceId: documentId,
        status: "WAITING_CONFIRMATION",
        stage: "NEEDS_REVIEW",
        progress: 90,
        message: "等待字段确认",
        traceId: "11111111111111111111111111111111",
        resultResourceIds: [],
        retryable: false,
        recoveryAttempts: 0,
        degradedComponents: [],
        createdAt: now,
        updatedAt: now,
      }),
    }),
  );
  await page.route("**/api/v1/plans", (route) =>
    route.fulfill({
      status: 202,
      contentType: "application/json",
      body: JSON.stringify({
        taskId: planTaskId,
        resourceId: planId,
        status: "QUEUED",
        traceId: "22222222222222222222222222222222",
        createdAt: now,
      }),
    }),
  );
  await page.route(`**/api/v1/tasks/${planTaskId}/events`, (route) =>
    route.fulfill({
      status: 200,
      contentType: "text/event-stream",
      body: `event: task-progress\ndata: ${JSON.stringify({
        eventId: "018f0e31-6ec8-7a59-b871-0242ac120108",
        taskId: planTaskId,
        stage: "COMPLETED",
        progress: 100,
        message: "方案生成完成",
        timestamp: now,
      })}\n\n`,
    }),
  );
  await page.route(`**/api/v1/tasks/${planTaskId}/agents`, (route) =>
    route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        items: [
          {
            agentExecutionId: "018f0e31-6ec8-7a59-b871-0242ac120109",
            taskId: planTaskId,
            agentName: "RequirementAgent",
            status: "SUCCEEDED",
            summary: "旅行需求已完成结构化",
            evidenceCount: 0,
            warnings: [],
            durationMs: 128,
            startedAt: now,
            completedAt: now,
          },
        ],
      }),
    }),
  );
  await page.route(`**/api/v1/tasks/${planTaskId}`, (route) =>
    route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        taskId: planTaskId,
        type: "PLAN_GENERATION",
        resourceId: planId,
        status: "SUCCEEDED",
        stage: "COMPLETED",
        progress: 100,
        message: "方案生成完成",
        traceId: "22222222222222222222222222222222",
        resultResourceIds: [planId, resultIds.lowTransit],
        retryable: false,
        recoveryAttempts: 0,
        degradedComponents: [],
        createdAt: now,
        updatedAt: now,
      }),
    }),
  );
  await page.route("**/api/v1/feedback?*", (route) =>
    route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({ items: [] }),
    }),
  );
  await page.route("**/api/v1/feedback", (route) =>
    route.fulfill({
      status: 201,
      contentType: "application/json",
      body: JSON.stringify({
        feedbackId: "018f0e31-6ec8-7a59-b871-0242ac120110",
        tripId,
        planId,
        targetType: "PLAN",
        sentiment: "LIKE",
        rating: 5,
        comment: "方案兼顾了历史文化与通勤时间。",
        tags: ["路线安排合理"],
        memoryAccepted: true,
        createdAt: now,
      }),
    }),
  );

  await page.goto("/");
  await page.getByRole("link", { name: "开始规划" }).click();
  await page.getByLabel("旅行名称").fill("北京三日文化漫游");
  await page.getByLabel("出发地").fill("上海");
  await page.getByLabel("目的地").fill("北京");
  await page.getByRole("button", { name: "下一步" }).click();
  await page.getByLabel("出发日期").fill("2026-10-01");
  await page.getByLabel("返程日期").fill("2026-10-03");
  await page.getByRole("button", { name: "下一步" }).click();
  await page.getByLabel("总预算").fill("5000");
  await page.getByRole("button", { name: "下一步" }).click();
  await page.getByRole("button", { name: "历史文化" }).click();
  await page.getByRole("button", { name: "下一步" }).click();
  await page.getByRole("button", { name: "均衡 体验密度与休息兼顾" }).click();
  await page.getByRole("button", { name: "下一步" }).click();
  await page.getByRole("button", { name: "下一步" }).click();
  await page.getByLabel("同行人员 1").fill("旅行者 1");
  await page.getByRole("button", { name: "下一步" }).click();
  await page.getByRole("button", { name: "创建旅行" }).focus();
  const documentsUrl = page.waitForURL(new RegExp(`/trips/${tripId}/documents$`));
  await page.keyboard.press("Enter");
  await documentsUrl;
  await expect(page.getByRole("heading", { name: "订单资料中心", level: 1 })).toBeVisible();

  await page
    .locator('input[type="file"]')
    .setInputFiles("e2e/fixtures/beijing-hotel-confirmation.pdf");
  await page.getByRole("button", { name: "上传并识别" }).click();
  await expect(page.getByText("开始时间需要人工确认")).toBeVisible();
  await page.getByRole("button", { name: "人工修正" }).click();
  await page.getByLabel("开始时间").fill("2026-10-01T07:00:00Z");
  await page.getByRole("button", { name: "保存修正" }).click();
  await expect.poll(() => correctionBody).toMatchObject({
    corrections: [{ field: "startTime", value: "2026-10-01T07:00:00Z" }],
  });

  await page.getByRole("button", { name: "生成旅行方案" }).click();
  await expect(page).toHaveURL(new RegExp(`/tasks/${planTaskId}$`));
  await expect(page.getByRole("heading", { name: "方案生成进度", level: 1 })).toBeVisible();
  await expect(page.getByText("整理旅行需求与同行人约束，已完成")).toBeVisible();
  await expect(page.getByText("100%").first()).toBeVisible();
  await expect(page.locator("body")).not.toContainText(planTaskId);
  await expect(page.locator("body")).not.toContainText("22222222222222222222222222222222");
  await expect(page.locator("body")).not.toContainText("PLAN_GENERATION");
  await expect(page.locator("body")).not.toContainText("旅行需求已完成结构化");

  await page.getByRole("link", { name: "查看旅行方案" }).click();
  await expect(
    page.getByRole("heading", { name: "北京三日均衡探索", level: 1 }),
  ).toBeVisible();
  await page.getByRole("button", { name: "并排比较" }).click();
  const comparison = page.getByRole("dialog");
  await expect(comparison.getByText("均衡探索", { exact: true })).toBeVisible();
  await expect(comparison.getByText("少通勤", { exact: true })).toBeVisible();
  await comparison.getByRole("button", { name: "关闭" }).click();

  const resultMap = page.getByRole("region", { name: "均衡探索路线" });
  await expect(
    resultMap.getByRole("button", { name: "查看地点：故宫博物院" }),
  ).toBeVisible();
  await page.getByRole("button", { name: "打开证据抽屉" }).click();
  const evidence = page.getByRole("dialog");
  await expect(evidence.getByText("故宫开放与参观信息")).toBeVisible();
  await evidence.getByRole("button", { name: "关闭" }).click();
  await expect(page.getByRole("heading", { name: "冲突中心" })).toBeVisible();

  await page.getByRole("button", { name: "带入重规划" }).click();
  const replan = page.getByRole("dialog");
  await replan.getByRole("button", { name: "生成新版本" }).click();
  await expect(
    replan.getByText("方案已发生变化，请刷新后再次提交。"),
  ).toBeVisible();
  await replan.getByRole("button", { name: "重试提交" }).click();
  await expect(page).toHaveURL(new RegExp(`/tasks/${resultIds.replanTask}$`));

  await page.goto(`/plans/${planId}`);
  await expect(page.getByText("相对上一版本")).toBeVisible();
  await page.getByRole("button", { name: "确认候选行程" }).click();
  await expect(page.getByRole("button", { name: "行程已确认" })).toBeVisible();

  await page.goto("/feedback");
  await page.getByRole("button", { name: "路线安排合理" }).click();
  await page.getByLabel("补充说明").fill("方案兼顾了历史文化与通勤时间。");
  await page.getByRole("button", { name: "提交反馈" }).click();
  await page.getByRole("button", { name: "打开通知" }).click();
  await expect(page.getByText("这条反馈会作为可管理的偏好建议。")).toBeVisible();
  expect(
    blockingConsoleErrors.filter((message) => !message.includes("status of 409")),
  ).toEqual([]);
});

test("restores an unfinished travel draft after refresh", async ({ page, isMobile }) => {
  test.skip(Boolean(isMobile), "desktop project only");
  await page.goto("/trips/new");
  await page.getByLabel("旅行名称").fill("未完成的成都行程");
  await page.getByLabel("出发地").fill("广州");
  await page.getByLabel("目的地").fill("成都");
  await page.getByRole("button", { name: "下一步" }).click();
  await expect(page.getByRole("heading", { name: "日期人数" })).toBeVisible();
  await expect
    .poll(() =>
      page.evaluate(() => {
        const persisted = window.localStorage.getItem("tripagent-flow");
        return persisted ? JSON.parse(persisted).state.draft.currentStep : -1;
      }),
    )
    .toBe(1);

  await page.reload();
  await expect(page.getByRole("heading", { name: "日期人数" })).toBeVisible();
  await page.getByRole("button", { name: "上一步" }).click();
  await expect(page.getByLabel("旅行名称")).toHaveValue("未完成的成都行程");
  await expect(page.getByLabel("出发地")).toHaveValue("广州");
  await expect(page.getByLabel("目的地")).toHaveValue("成都");
});

test("mobile navigation and component preview remain within the viewport", async ({
  page,
  isMobile,
}) => {
  test.skip(!isMobile, "mobile project only");
  await page.goto("/");

  await page.getByRole("button", { name: "打开主导航" }).click();
  await expect(page.getByRole("dialog")).toBeVisible();
  await page.getByRole("link", { name: "界面预览" }).click();
  await expect(page.getByRole("heading", { name: "界面预览", level: 1 })).toBeVisible();
  const map = page.getByRole("region", { name: "城市探索地图" });
  await expect(map.locator(".maplibregl-canvas")).toBeVisible();
  await expect(map.getByText("故宫博物院")).toBeVisible();
  await expect(map.getByRole("button", { name: "放大地图" })).toBeVisible();
  await expect(map.getByRole("link", { name: "© OpenStreetMap contributors" })).toBeVisible();
  await expect(page.getByText("加载失败")).toHaveCount(0);
  await page.getByRole("button", { name: "不可用状态" }).click();
  await expect(page.getByText("内容暂时无法显示")).toBeVisible();
  await expect(page.getByText("当前页面中的其他内容不受影响")).toBeVisible();

  const overflow = await page.evaluate(
    () => document.documentElement.scrollWidth - window.innerWidth,
  );
  expect(overflow).toBeLessThanOrEqual(0);
});

test("desktop result workspace compares plans and protects every write", async ({
  page,
  isMobile,
}) => {
  test.skip(Boolean(isMobile), "desktop project only");
  const blockingConsoleErrors: string[] = [];
  page.on("console", (message) => {
    if (message.type() === "error") blockingConsoleErrors.push(message.text());
  });
  page.on("pageerror", (error) => blockingConsoleErrors.push(error.message));
  await installResultRoutes(page);
  await page.goto(`/plans/${resultIds.balanced}`);

  await expect(
    page.getByRole("heading", { name: "北京三日均衡探索", level: 1 }),
  ).toBeVisible();
  await expect(page.getByRole("heading", { name: "旅行约束" })).toBeVisible();
  await expect(page.getByRole("heading", { name: "每日时间线" })).toBeVisible();
  await expect(page.getByRole("heading", { name: "冲突中心" })).toBeVisible();
  await expect(page.getByRole("img", { name: "预算分类柱状图" })).toBeVisible();

  const map = page.getByRole("region", { name: "均衡探索路线" });
  await expect(map.getByRole("button", { name: "查看地点：故宫博物院" })).toBeVisible();
  await map.getByRole("button", { name: "查看地点：故宫博物院" }).click();
  await expect(
    map.getByRole("button", { name: "查看地点：故宫博物院" }),
  ).toHaveAttribute("aria-pressed", "true");

  await page.getByRole("button", { name: "并排比较" }).click();
  const comparison = page.getByRole("dialog");
  await expect(
    comparison.getByRole("heading", { name: "方案并排比较" }),
  ).toBeVisible();
  await expect(comparison.getByText("均衡探索", { exact: true })).toBeVisible();
  await expect(comparison.getByText("少通勤", { exact: true })).toBeVisible();
  await comparison.getByRole("button", { name: "关闭" }).click();

  await page.getByRole("button", { name: "确认候选行程" }).click();
  await expect(page.getByRole("button", { name: "行程已确认" })).toBeVisible();

  await page.getByRole("button", { name: "费用操作确认" }).click();
  const hitl = page.getByRole("dialog");
  await expect(hitl.getByText("不会自动支付", { exact: false }).first()).toBeVisible();
  await hitl.getByRole("button", { name: "继续核对费用" }).click();
  await hitl.getByRole("checkbox").check();
  await hitl.getByRole("button", { name: "确认授权" }).click();
  await expect(hitl.getByText("费用授权已确认")).toBeVisible();
  await expect(hitl.getByText("不会向商家提交订单，也不会自动支付。")).toBeVisible();
  await hitl.getByRole("button", { name: "关闭" }).click();

  await page.getByRole("button", { name: "锁定" }).first().click();
  await page.getByRole("button", { name: "带入重规划" }).click();
  const replan = page.getByRole("dialog");
  await expect(replan.getByLabel("自然语言修改")).toHaveValue(
    /故宫与下一活动之间的通勤时间不足/,
  );
  await replan.getByRole("button", { name: "生成新版本" }).click();
  await expect(
    replan.getByText("方案已发生变化，请刷新后再次提交。"),
  ).toBeVisible();
  await replan.getByRole("button", { name: "重试提交" }).click();
  await expect(page).toHaveURL(new RegExp(`/tasks/${resultIds.replanTask}$`));
  expect(
    blockingConsoleErrors.filter((message) => !message.includes("status of 409")),
  ).toEqual([]);
});

test("mobile result workspace switches modules without horizontal overflow", async ({
  page,
  isMobile,
}) => {
  test.skip(!isMobile, "mobile project only");
  await installResultRoutes(page);
  await page.goto(`/plans/${resultIds.balanced}`);

  const panels = page.getByRole("group", { name: "结果模块" });
  const map = page.getByRole("region", { name: "均衡探索路线" });
  await expect(map).toBeVisible();
  await expect(map).toBeInViewport({ ratio: 0.35 });
  await expect(
    page.getByRole("combobox", { name: "版本历史" }).first(),
  ).toBeVisible();

  await panels.getByRole("button", { name: "时间线" }).click();
  await expect(page.getByRole("heading", { name: "每日时间线" })).toBeVisible();

  await panels.getByRole("button", { name: "预算" }).click();
  await expect(page.getByRole("img", { name: "预算分类柱状图" })).toBeVisible();

  await panels.getByRole("button", { name: "冲突" }).click();
  await expect(page.getByRole("heading", { name: "冲突中心" })).toBeVisible();
  await expect(page.getByRole("button", { name: "打开证据抽屉" })).toBeVisible();
  await page.getByRole("button", { name: "打开证据抽屉" }).click();
  const evidence = page.getByRole("dialog");
  await expect(evidence.getByRole("heading", { name: "证据抽屉" })).toBeVisible();
  await expect(evidence.getByText("故宫开放与参观信息")).toBeVisible();
  await evidence.getByRole("button", { name: "关闭" }).click();
  await expect(page.getByRole("button", { name: "重规划" }).last()).toBeVisible();

  const overflow = await page.evaluate(
    () => document.documentElement.scrollWidth - window.innerWidth,
  );
  expect(overflow).toBeLessThanOrEqual(0);
});

test("phase fourteen auxiliary pages use protected backend projections", async ({
  page,
  isMobile,
}) => {
  test.skip(Boolean(isMobile), "desktop project only");
  const blockingConsoleErrors: string[] = [];
  page.on("console", (message) => {
    if (message.type() === "error") blockingConsoleErrors.push(message.text());
  });
  page.on("pageerror", (error) => blockingConsoleErrors.push(error.message));
  await installResultRoutes(page);

  const now = "2026-09-07T08:00:00Z";
  const activeTaskId = "018f0e31-6ec8-7a59-b871-0242ac121020";
  const reviewTaskId = "018f0e31-6ec8-7a59-b871-0242ac121021";
  const inferredPreferenceId = "018f0e31-6ec8-7a59-b871-0242ac121022";
  const userPreferenceId = "018f0e31-6ec8-7a59-b871-0242ac121023";
  const feedbackId = "018f0e31-6ec8-7a59-b871-0242ac121024";
  const traceId = "55555555555555555555555555555555";
  let workbenchTasks = [
    {
      taskId: activeTaskId,
      tripId: resultIds.trip,
      resultPlanId: resultIds.balanced,
      title: "北京三日旅行",
      destination: "北京",
      startDate: "2026-10-01",
      endDate: "2026-10-03",
      type: "PLAN_GENERATION",
      status: "RUNNING",
      progress: 68,
      createdAt: now,
      updatedAt: now,
    },
    {
      taskId: reviewTaskId,
      tripId: resultIds.trip,
      title: "北京住宿资料",
      destination: "北京",
      startDate: "2026-10-01",
      endDate: "2026-10-03",
      type: "DOCUMENT_PARSE",
      status: "WAITING_CONFIRMATION",
      progress: 90,
      createdAt: now,
      updatedAt: now,
    },
  ];
  let preferences = [
    {
      preferenceId: inferredPreferenceId,
      userId: "018f0e31-6ec8-7a59-b871-0242ac121099",
      category: "feedback.place",
      value: "like: 历史建筑",
      source: "INFERRED",
      status: "ACTIVE",
      createdAt: now,
      updatedAt: now,
    },
  ];
  let feedbackItems: Array<Record<string, unknown>> = [];

  await page.route("**/api/v1/workbench/tasks?*", (route) =>
    route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({ items: workbenchTasks }),
    }),
  );
  await page.route(`**/api/v1/tasks/${activeTaskId}/cancel`, async (route) => {
    workbenchTasks = workbenchTasks.map((task) =>
      task.taskId === activeTaskId
        ? { ...task, status: "CANCELLED", progress: 68, updatedAt: now }
        : task,
    );
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        taskId: activeTaskId,
        type: "PLAN_GENERATION",
        resourceId: resultIds.balanced,
        status: "CANCELLED",
        stage: "CANCELLED",
        progress: 68,
        message: "任务已取消",
        traceId: "66666666666666666666666666666666",
        resultResourceIds: [],
        retryable: false,
        recoveryAttempts: 0,
        degradedComponents: [],
        createdAt: now,
        updatedAt: now,
      }),
    });
  });

  await page.route(/\/api\/v1\/users\/[^/]+\/preferences(?:\/[^/]+(?:\/reject)?)?$/, async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    if (request.method() === "GET") {
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify({ items: preferences }),
      });
      return;
    }
    if (request.method() === "POST" && url.pathname.endsWith("/reject")) {
      preferences = preferences.map((item) =>
        item.preferenceId === inferredPreferenceId
          ? { ...item, status: "REJECTED", updatedAt: now }
          : item,
      );
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify(preferences[0]),
      });
      return;
    }
    if (request.method() === "POST") {
      const body = request.postDataJSON();
      const created = {
        preferenceId: userPreferenceId,
        userId: "018f0e31-6ec8-7a59-b871-0242ac121099",
        ...body,
        source: "USER",
        status: "ACTIVE",
        createdAt: now,
        updatedAt: now,
      };
      preferences = [created, ...preferences];
      await route.fulfill({
        status: 201,
        contentType: "application/json",
        body: JSON.stringify(created),
      });
      return;
    }
    if (request.method() === "DELETE") {
      preferences = preferences.filter(
        (item) => !url.pathname.endsWith(`/${item.preferenceId}`),
      );
      await route.fulfill({ status: 204 });
      return;
    }
    await route.fulfill({ status: 405 });
  });

  await page.route("**/api/v1/feedback?*", (route) =>
    route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({ items: feedbackItems }),
    }),
  );
  await page.route("**/api/v1/feedback", async (route) => {
    if (route.request().method() !== "POST") {
      await route.fallback();
      return;
    }
    const body = route.request().postDataJSON();
    const created = {
      ...body,
      feedbackId,
      memoryAccepted: true,
      createdAt: now,
    };
    feedbackItems = [created];
    await route.fulfill({
      status: 201,
      contentType: "application/json",
      body: JSON.stringify(created),
    });
  });

  await page.route(`**/api/v1/developer/traces/${traceId}`, (route) =>
    route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        traceId,
        status: "OK",
        startedAt: now,
        durationMs: 842,
        spans: [
          {
            spanId: "span-model",
            parentSpanId: null,
            category: "MODEL",
            name: "planning.model.generate",
            status: "OK",
            startedAt: now,
            durationMs: 520,
            attributes: {
              "gen_ai.prompt": "***",
              "user.email": "***",
              result: "已生成两个候选方案",
            },
          },
          {
            spanId: "span-rag",
            parentSpanId: "span-model",
            category: "RAG",
            name: "travel.knowledge.search",
            status: "OK",
            startedAt: now,
            durationMs: 122,
            attributes: { matches: "6" },
          },
        ],
      }),
    }),
  );

  await page.goto(`/plans/${resultIds.balanced}`);
  await expect(page.getByRole("heading", { name: "北京三日均衡探索" })).toBeVisible();

  await page.goto("/workbench");
  await expect(page.getByRole("heading", { name: "行程工作台" })).toBeVisible();
  await expect(page.getByText("北京三日旅行")).toBeVisible();
  await page.getByRole("button", { name: "取消" }).click();
  await page.getByRole("button", { name: "打开通知" }).click();
  await expect(page.getByText("任务已取消", { exact: true })).toBeVisible();
  await page.keyboard.press("Escape");
  await page.getByRole("tab", { name: /待确认/ }).click();
  await expect(page.getByText("北京住宿资料")).toBeVisible();
  await expect(page.getByRole("link", { name: "继续任务" })).toBeVisible();

  await page.goto("/preferences");
  await expect(page.getByText("根据反馈推断")).toBeVisible();
  await page.getByRole("button", { name: "拒绝" }).click();
  await expect(page.getByRole("heading", { name: "已拒绝的推断" })).toBeVisible();
  await page.getByLabel("偏好内容").fill("每天保留午休时间");
  await page.getByRole("button", { name: "保存" }).click();
  await expect(page.getByText("每天保留午休时间")).toBeVisible();

  await page.goto("/feedback");
  await page.getByRole("button", { name: "单个地点" }).click();
  await page.getByLabel("地点名称").fill("故宫博物院");
  await page.getByRole("button", { name: "地点选择合适" }).click();
  await page.getByLabel("补充说明").fill("参观时段和周边路线安排合理。");
  await page.getByRole("button", { name: "提交反馈" }).click();
  await expect(page.getByText("已形成可管理的偏好建议")).toBeVisible();

  await page.goto("/system-status");
  await expect(page.getByText("当前展示演示数据")).toBeVisible();
  await expect(page.getByText("演示规划服务")).toBeVisible();
  await expect(page.getByText("数据存储")).toBeVisible();
  await expect(page.getByText("地图", { exact: true })).toBeVisible();

  await page.goto("/developer/traces");
  await page.getByLabel("追踪编号").fill(traceId);
  await page.getByRole("button", { name: "查询链路" }).click();
  await expect(page.getByText("planning.model.generate")).toBeVisible();
  await expect(page.getByText("travel.knowledge.search")).toBeVisible();
  await expect(page.getByText("***").first()).toBeVisible();
  await expect(page.getByText("user@example.com")).toHaveCount(0);
  expect(blockingConsoleErrors).toEqual([]);
});

test("falls back after stream interruption and presents an Agent timeout safely", async ({
  page,
  isMobile,
}) => {
  test.skip(Boolean(isMobile), "desktop project only");
  const taskId = "018f0e31-6ec8-7a59-b871-0242ac121030";
  const now = "2026-09-07T08:00:00Z";
  let taskReads = 0;

  await page.route(`**/api/v1/tasks/${taskId}/events`, (route) =>
    route.abort("failed"),
  );
  await page.route(`**/api/v1/tasks/${taskId}/agents`, (route) =>
    route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        items: [
          {
            agentExecutionId: "018f0e31-6ec8-7a59-b871-0242ac121031",
            taskId,
            agentName: "ResearchAgent",
            status: "FAILED",
            summary: "provider timeout",
            evidenceCount: 0,
            warnings: [{ code: "AGENT_TIMEOUT", message: "upstream timeout" }],
            durationMs: 30000,
            startedAt: now,
            completedAt: now,
          },
        ],
      }),
    }),
  );
  await page.route(`**/api/v1/tasks/${taskId}`, (route) => {
    taskReads += 1;
    return route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        taskId,
        type: "PLAN_GENERATION",
        resourceId: resultIds.balanced,
        status: "FAILED",
        stage: "FAILED",
        progress: 42,
        message: "upstream timeout",
        traceId: "77777777777777777777777777777777",
        resultResourceIds: [],
        retryable: true,
        recoveryAttempts: 1,
        degradedComponents: ["model"],
        createdAt: now,
        updatedAt: now,
      }),
    });
  });

  await page.goto(`/tasks/${taskId}`);
  await expect(
    page.getByRole("heading", { name: "本次方案未能完成" }),
  ).toBeVisible();
  await expect(page.getByText("部分实时信息获取较慢")).toBeVisible();
  await expect.poll(() => taskReads).toBeGreaterThanOrEqual(2);
  await expect(page.locator("body")).not.toContainText("upstream timeout");
  await expect(page.locator("body")).not.toContainText(taskId);
});

test("maps validation errors to fields and keeps one idempotency key across retry", async ({
  page,
  isMobile,
}) => {
  test.skip(Boolean(isMobile), "desktop project only");
  const tripId = "018f0e31-6ec8-7a59-b871-0242ac121040";
  const now = "2026-09-07T08:00:00Z";
  const idempotencyKeys: string[] = [];
  let createAttempts = 0;
  let pendingSuccessfulCreate: Route | undefined;

  await page.route("**/api/v1/trips", async (route) => {
    if (route.request().method() !== "POST") {
      await route.fallback();
      return;
    }
    createAttempts += 1;
    idempotencyKeys.push(route.request().headers()["idempotency-key"] ?? "");
    if (createAttempts === 1) {
      await route.fulfill({
        status: 422,
        contentType: "application/json",
        body: JSON.stringify({
          code: "VALIDATION_FAILED",
          message: "invalid title",
          details: [
            {
              field: "/title",
              code: "TITLE_INVALID",
              message: "invalid title",
            },
          ],
          traceId: "88888888888888888888888888888888",
          timestamp: now,
        }),
      });
      return;
    }
    pendingSuccessfulCreate = route;
  });
  await page.route(`**/api/v1/trips/${tripId}/requirements`, async (route) => {
    const body = route.request().postDataJSON();
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({ tripId, ...body, updatedAt: now }),
    });
  });
  await page.route(`**/api/v1/trips/${tripId}`, (route) =>
    route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        tripId,
        userId: "018f0e31-6ec8-7a59-b871-0242ac121099",
        title: "北京三日文化漫游",
        timeZone: "Asia/Shanghai",
        status: "READY",
        requirement: null,
        createdAt: now,
        updatedAt: now,
      }),
    }),
  );

  await page.goto("/");
  await page.getByRole("button", { name: "使用示例" }).click();
  for (let step = 0; step < 7; step += 1) {
    await page.getByRole("button", { name: "下一步" }).click();
  }
  await page.getByRole("button", { name: "创建旅行" }).click();

  for (let step = 0; step < 7; step += 1) {
    await page.getByRole("button", { name: "上一步" }).click();
  }
  await expect(page.getByText("请检查此项是否符合要求")).toBeVisible();
  await page.getByLabel("旅行名称").fill("北京三日文化漫游");
  for (let step = 0; step < 7; step += 1) {
    await page.getByRole("button", { name: "下一步" }).click();
  }

  const navigation = page.waitForURL(new RegExp(`/trips/${tripId}/documents$`));
  const doubleClick = page
    .getByRole("button", { name: "创建旅行" })
    .dblclick();
  await expect.poll(() => pendingSuccessfulCreate).toBeTruthy();
  const createRoute = pendingSuccessfulCreate;
  if (!createRoute) {
    throw new Error("The successful trip request was not captured.");
  }
  const body = createRoute.request().postDataJSON();
  await createRoute.fulfill({
    status: 201,
    contentType: "application/json",
    body: JSON.stringify({
      tripId,
      ...body,
      status: "DRAFT",
      createdAt: now,
      updatedAt: now,
    }),
  });
  await Promise.all([doubleClick, navigation]);
  expect(createAttempts).toBe(2);
  expect(idempotencyKeys[0]).toBeTruthy();
  expect(idempotencyKeys[1]).toBe(idempotencyKeys[0]);
});

test("shows rate limits, missing evidence, and unavailable maps in user language", async ({
  page,
  isMobile,
}) => {
  test.skip(Boolean(isMobile), "desktop project only");
  await installResultRoutes(page);
  const noEvidencePlan = resultPlan({ evidence: [] });

  await page.route(`**/api/v1/plans/${resultIds.balanced}`, (route) =>
    route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify(noEvidencePlan),
    }),
  );
  await page.route(`**/api/v1/plans/${resultIds.balanced}/replan`, (route) =>
    route.fulfill({
      status: 429,
      contentType: "application/json",
      headers: { "Retry-After": "17" },
      body: JSON.stringify({
        code: "MODEL_RATE_LIMITED",
        message: "provider rate limit",
        details: [],
        traceId: "99999999999999999999999999999999",
        timestamp: "2026-09-07T08:00:00Z",
      }),
    }),
  );
  await page.route(/\/api\/v1\/maps\/tiles\/\d+\/\d+\/\d+\.png$/, (route) =>
    route.fulfill({ status: 503 }),
  );

  await page.goto(`/plans/${resultIds.balanced}`);
  await expect(page.getByText("当前方案证据不足")).toBeVisible();
  await expect(page.getByText("地图暂时不可用")).toBeVisible();
  await expect(
    page.getByRole("heading", { name: "每日时间线" }),
  ).toBeVisible();
  await page.getByRole("button", { name: "打开证据抽屉" }).click();
  const evidence = page.getByRole("dialog");
  await expect(evidence.getByText("当前方案证据不足")).toBeVisible();
  await expect(evidence.getByRole("link")).toHaveCount(0);
  await evidence.getByRole("button", { name: "关闭" }).click();
  await page.getByRole("button", { name: "重规划" }).first().click();
  const replan = page.getByRole("dialog");
  await replan.getByRole("button", { name: "生成新版本" }).click();
  await expect(
    replan.getByText("操作过于频繁，请在 17 秒后重试。"),
  ).toBeVisible();
  await expect(page.locator("body")).not.toContainText("MODEL_RATE_LIMITED");
  await expect(page.locator("body")).not.toContainText("provider rate limit");
});
