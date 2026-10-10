import type { AgentResult } from "@api-generated/models/AgentResult";
import type { ParsedBooking } from "@api-generated/models/ParsedBooking";
import type { TaskRecord } from "@api-generated/models/TaskRecord";
import type { TravelPlan } from "@api-generated/models/TravelPlan";

const ids = {
  trip: "018f0e31-6ec8-7a59-b871-0242ac121001",
  plan: "018f0e31-6ec8-7a59-b871-0242ac121002",
  family: "018f0e31-6ec8-7a59-b871-0242ac121003",
  task: "018f0e31-6ec8-7a59-b871-0242ac121004",
  item: "018f0e31-6ec8-7a59-b871-0242ac121005",
  transport: "018f0e31-6ec8-7a59-b871-0242ac121006",
  evidence: "018f0e31-6ec8-7a59-b871-0242ac121007",
  conflict: "018f0e31-6ec8-7a59-b871-0242ac121008",
  booking: "018f0e31-6ec8-7a59-b871-0242ac121009",
  document: "018f0e31-6ec8-7a59-b871-0242ac121010",
};

export function makePlan(overrides: Partial<TravelPlan> = {}): TravelPlan {
  return {
    planId: ids.plan,
    planFamilyId: ids.family,
    tripId: ids.trip,
    previousPlanId: undefined,
    version: 1,
    alternativeKey: "balanced",
    strategy: "BALANCED",
    title: "北京三日均衡探索",
    summary: "兼顾历史文化、本地餐饮、交通时间和预算。",
    days: [
      {
        dayNumber: 1,
        date: new Date("2026-10-01T00:00:00Z"),
        title: "城市中轴线",
        items: [
          {
            itemId: ids.transport,
            type: "TRANSPORT",
            title: "抵达北京",
            description: "抵达后前往市区。",
            startTime: new Date("2026-10-01T01:00:00Z"),
            endTime: new Date("2026-10-01T02:30:00Z"),
            location: {
              name: "北京站",
              latitude: 39.9028,
              longitude: 116.427,
            },
            amount: 500,
            currency: "CNY",
            evidenceIds: new Set([ids.evidence]),
          },
          {
            itemId: ids.item,
            type: "ATTRACTION",
            title: "故宫博物院",
            description: "按预约时段参观。",
            startTime: new Date("2026-10-01T04:00:00Z"),
            endTime: new Date("2026-10-01T07:00:00Z"),
            location: {
              name: "故宫博物院",
              latitude: 39.9163,
              longitude: 116.3972,
            },
            amount: 60,
            currency: "CNY",
            evidenceIds: new Set([ids.evidence]),
          },
        ],
      },
    ],
    budgetSummary: {
      totalBudget: 5000,
      estimatedTotal: 3760,
      remainingBudget: 1240,
      currency: "CNY",
      items: [
        {
          category: "TRANSPORT",
          description: "交通",
          amount: 500,
          currency: "CNY",
        },
        {
          category: "ACCOMMODATION",
          description: "住宿",
          amount: 3200,
          currency: "CNY",
        },
        {
          category: "ATTRACTION",
          description: "门票",
          amount: 60,
          currency: "CNY",
        },
      ],
      dailyBudgets: [
        {
          date: new Date("2026-10-01T00:00:00Z"),
          amount: 3760,
          currency: "CNY",
        },
      ],
    },
    conflicts: [
      {
        conflictId: ids.conflict,
        code: "COMMUTE_TIME_INSUFFICIENT",
        type: "TIME",
        severity: "WARNING",
        message: "景点之间的通勤时间不足。",
        fieldPath: "/days/0/items/1",
        relatedItemIds: new Set([ids.item]),
        autoFixable: true,
        status: "OPEN",
      },
    ],
    evidence: [
      {
        evidenceId: ids.evidence,
        sourceType: "PUBLIC_KNOWLEDGE",
        sourceId: "synthetic-forbidden-city",
        title: "故宫开放与参观信息",
        excerpt: "建议按预约时段入场。",
        url: "https://example.test/forbidden-city",
        retrievedAt: new Date("2026-09-01T08:00:00Z"),
      },
    ],
    triggerReason: "INITIAL_GENERATION",
    replanInput: {
      lockedItemIds: new Set(),
      removedItemIds: new Set(),
      addedPreferences: new Set(),
      naturalLanguageRequest: "",
    },
    changeSummary: "",
    autoReplanned: false,
    status: "READY",
    createdAt: new Date("2026-09-01T08:00:00Z"),
    updatedAt: new Date("2026-09-01T08:00:00Z"),
    ...overrides,
  };
}

export function makeBooking(
  overrides: Partial<ParsedBooking> = {},
): ParsedBooking {
  return {
    bookingId: ids.booking,
    type: "HOTEL",
    sourceDocumentId: ids.document,
    provider: "合成酒店",
    hotelName: "北京中轴线酒店",
    confidence: 0.72,
    missingFields: new Set(["startTime"]),
    reviewStatus: "NEEDS_REVIEW",
    fieldEvidence: [
      {
        field: "hotelName",
        confidence: 0.96,
        status: "EXTRACTED",
        textEvidence: "北京中轴线酒店",
        region: { page: 1, x: 0.1, y: 0.2, width: 0.3, height: 0.08 },
      },
      {
        field: "startTime",
        confidence: 0,
        status: "MISSING",
      },
    ],
    validationIssues: [
      {
        code: "LOW_CONFIDENCE_CRITICAL_FIELD",
        field: "startTime",
        message: "synthetic low confidence",
      },
    ],
    corrections: [],
    ...overrides,
  };
}

export function makeAgent(
  overrides: Partial<AgentResult> = {},
): AgentResult {
  return {
    agentResultId: crypto.randomUUID(),
    taskId: ids.task,
    agentType: "RequirementAgent",
    status: "RUNNING",
    result: {},
    evidenceIds: [],
    errors: [],
    createdAt: new Date("2026-09-01T08:00:00Z"),
    ...overrides,
  };
}

export function makeTask(overrides: Partial<TaskRecord> = {}): TaskRecord {
  return {
    taskId: ids.task,
    type: "PLAN_GENERATION",
    resourceId: ids.plan,
    status: "RUNNING",
    stage: "AGENTS_RUNNING",
    progress: 40,
    traceId: "11111111111111111111111111111111",
    resultResourceIds: new Set(),
    retryable: false,
    recoveryAttempts: 0,
    degradedComponents: new Set(),
    createdAt: new Date("2026-09-01T08:00:00Z"),
    updatedAt: new Date("2026-09-01T08:00:00Z"),
    ...overrides,
  };
}

export { ids as testIds };
