import type { AgentResult } from "@api-generated/models/AgentResult";
import type { BudgetSummary } from "@api-generated/models/BudgetSummary";
import type { Conflict } from "@api-generated/models/Conflict";
import type { DayPlan } from "@api-generated/models/DayPlan";
import type { Evidence } from "@api-generated/models/Evidence";
import type { Place } from "@api-generated/models/Place";

const now = new Date("2026-10-01T08:00:00Z");

export const previewBudget = {
  totalBudget: 6800,
  estimatedTotal: 4860,
  remainingBudget: 1940,
  currency: "CNY",
  items: [
    {
      category: "TRANSPORT",
      description: "往返交通",
      amount: 1760,
      currency: "CNY",
    },
    {
      category: "ACCOMMODATION",
      description: "住宿",
      amount: 2100,
      currency: "CNY",
    },
    {
      category: "DINING",
      description: "餐饮",
      amount: 1000,
      currency: "CNY",
    },
  ],
  dailyBudgets: [],
} satisfies BudgetSummary;

export const previewEvidence = [
  {
    evidenceId: "10000000-0000-0000-0000-000000000001",
    sourceType: "PUBLIC_KNOWLEDGE",
    sourceId: "preview-public",
    title: "城市核心区域",
    excerpt: "组件预览证据，不代表实时旅行数据。",
    retrievedAt: now,
  },
  {
    evidenceId: "10000000-0000-0000-0000-000000000002",
    sourceType: "USER_MEMORY",
    sourceId: "preview-memory",
    title: "用户节奏偏好",
    excerpt: "组件预览证据，不代表已读取用户隐私数据。",
    retrievedAt: now,
  },
  {
    evidenceId: "10000000-0000-0000-0000-000000000003",
    sourceType: "GRAPH",
    sourceId: "preview-graph",
    title: "地点邻近关系",
    excerpt: "组件预览证据，不代表实时路线。",
    retrievedAt: now,
  },
] satisfies Evidence[];

export const previewConflicts = [
  {
    conflictId: "20000000-0000-0000-0000-000000000001",
    code: "COMMUTE_TIME_INSUFFICIENT",
    type: "TIME",
    severity: "ERROR",
    message: "相邻活动间通勤时间不足。",
    relatedItemIds: new Set<string>(),
    autoFixable: true,
    status: "OPEN",
  },
  {
    conflictId: "20000000-0000-0000-0000-000000000002",
    code: "RECOMMENDATION_EVIDENCE_MISSING",
    type: "EVIDENCE",
    severity: "WARNING",
    message: "推荐项缺少足够证据。",
    relatedItemIds: new Set<string>(),
    autoFixable: false,
    status: "OPEN",
  },
] satisfies Conflict[];

export const previewAgents = [
  {
    agentResultId: "30000000-0000-0000-0000-000000000001",
    taskId: "40000000-0000-0000-0000-000000000001",
    agentType: "RequirementAgent",
    status: "SUCCEEDED",
    result: {},
    evidenceIds: [],
    errors: [],
    createdAt: now,
  },
  {
    agentResultId: "30000000-0000-0000-0000-000000000002",
    taskId: "40000000-0000-0000-0000-000000000001",
    agentType: "ResearchAgent",
    status: "SUCCEEDED",
    result: {},
    evidenceIds: [
      "10000000-0000-0000-0000-000000000001",
      "10000000-0000-0000-0000-000000000003",
    ],
    errors: [],
    createdAt: now,
  },
  {
    agentResultId: "30000000-0000-0000-0000-000000000003",
    taskId: "40000000-0000-0000-0000-000000000001",
    agentType: "TransportAgent",
    status: "RUNNING",
    result: {},
    evidenceIds: ["10000000-0000-0000-0000-000000000003"],
    errors: [],
    createdAt: now,
  },
  {
    agentResultId: "30000000-0000-0000-0000-000000000004",
    taskId: "40000000-0000-0000-0000-000000000001",
    agentType: "ReviewAgent",
    status: "QUEUED",
    result: {},
    evidenceIds: [],
    errors: [],
    createdAt: now,
  },
] satisfies AgentResult[];

export const previewPlaces = [
  {
    placeId: "50000000-0000-0000-0000-000000000001",
    name: "故宫博物院",
    longitude: 116.397,
    latitude: 39.918,
  },
  {
    placeId: "50000000-0000-0000-0000-000000000002",
    name: "景山公园",
    longitude: 116.397,
    latitude: 39.925,
  },
  {
    placeId: "50000000-0000-0000-0000-000000000003",
    name: "南锣鼓巷",
    longitude: 116.404,
    latitude: 39.937,
  },
] satisfies Place[];

export const previewDay = {
  dayNumber: 1,
  date: new Date("2026-10-01T00:00:00+08:00"),
  title: "城市中轴线",
  items: [
    {
      itemId: "60000000-0000-0000-0000-000000000001",
      type: "ATTRACTION",
      title: "故宫博物院",
      description: "按预约时段入场，保留充足参观时间。",
      startTime: new Date("2026-10-01T01:00:00Z"),
      endTime: new Date("2026-10-01T04:00:00Z"),
      location: previewPlaces[0],
      amount: 60,
      currency: "CNY",
      evidenceIds: new Set(["10000000-0000-0000-0000-000000000001"]),
    },
    {
      itemId: "60000000-0000-0000-0000-000000000002",
      type: "DINING",
      title: "午餐",
      startTime: new Date("2026-10-01T04:30:00Z"),
      endTime: new Date("2026-10-01T05:30:00Z"),
      location: previewPlaces[1],
      amount: 120,
      currency: "CNY",
      evidenceIds: new Set<string>(),
    },
    {
      itemId: "60000000-0000-0000-0000-000000000003",
      type: "TRANSPORT",
      title: "前往南锣鼓巷",
      startTime: new Date("2026-10-01T06:00:00Z"),
      endTime: new Date("2026-10-01T06:25:00Z"),
      location: previewPlaces[2],
      amount: 10,
      currency: "CNY",
      evidenceIds: new Set(["10000000-0000-0000-0000-000000000003"]),
    },
  ],
} satisfies DayPlan;
