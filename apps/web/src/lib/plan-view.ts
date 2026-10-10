import type { Evidence } from "@api-generated/models/Evidence";
import type { ItineraryItem } from "@api-generated/models/ItineraryItem";
import type { PlanStrategy } from "@api-generated/models/PlanStrategy";
import type { TravelPlan } from "@api-generated/models/TravelPlan";

export interface PlanMetrics {
  totalItems: number;
  attractionCount: number;
  commuteMinutes: number;
  evidenceCoverage: number;
  dailyIntensity: string;
}

const strategyLabels: Record<PlanStrategy, string> = {
  BALANCED: "均衡探索",
  LOW_TRANSIT: "少通勤",
  BUDGET: "预算优先",
  COMFORT: "舒适优先",
  CUSTOM: "自定义",
};

const strategyFit: Record<PlanStrategy, string> = {
  BALANCED: "适合希望兼顾文化、美食、节奏与预算的旅行者。",
  LOW_TRANSIT: "适合减少跨区移动、重视步行连续性的旅行者。",
  BUDGET: "适合优先控制总支出、接受更多取舍的旅行者。",
  COMFORT: "适合重视停留质量、休息时间和交通舒适度的旅行者。",
  CUSTOM: "适合有明确锁定项或特殊约束的旅行者。",
};

export const evidenceSourceMeta: Record<
  Evidence["sourceType"],
  { label: string; strategy: string; tone: string }
> = {
  PUBLIC_KNOWLEDGE: {
    label: "公共知识",
    strategy: "多来源信息整理",
    tone: "bg-sky-soft text-sky",
  },
  USER_MEMORY: {
    label: "用户记忆",
    strategy: "个人偏好匹配",
    tone: "bg-success-soft text-success",
  },
  DOCUMENT: {
    label: "已确认订单",
    strategy: "订单内容核对",
    tone: "bg-coral-soft text-coral",
  },
  PROVIDER: {
    label: "实时信息",
    strategy: "最新信息核对",
    tone: "bg-warning-soft text-warning",
  },
  GRAPH: {
    label: "地点关联",
    strategy: "地点关系核对",
    tone: "bg-surface-subtle text-muted",
  },
};

export function strategyLabel(strategy: PlanStrategy) {
  return strategyLabels[strategy];
}

export function strategyFitDescription(strategy: PlanStrategy) {
  return strategyFit[strategy];
}

export function itemDurationMinutes(item: ItineraryItem) {
  return Math.max(
    0,
    Math.round((item.endTime.getTime() - item.startTime.getTime()) / 60_000),
  );
}

export function planMetrics(plan: TravelPlan): PlanMetrics {
  const items = plan.days.flatMap((day) => day.items);
  const activeItems = items.filter((item) => item.type !== "ACCOMMODATION");
  const averagePerDay = activeItems.length / Math.max(plan.days.length, 1);
  const evidenced = items.filter((item) => item.evidenceIds.size > 0).length;
  return {
    totalItems: items.length,
    attractionCount: items.filter((item) => item.type === "ATTRACTION").length,
    commuteMinutes: items
      .filter((item) => item.type === "TRANSPORT")
      .reduce((total, item) => total + itemDurationMinutes(item), 0),
    evidenceCoverage:
      items.length === 0 ? 0 : Math.round((evidenced / items.length) * 100),
    dailyIntensity:
      averagePerDay <= 2 ? "舒缓" : averagePerDay <= 4 ? "适中" : "紧凑",
  };
}

export function latestAlternatives(plans: TravelPlan[]) {
  const latest = new Map<string, TravelPlan>();
  plans
    .filter((plan) => !["GENERATING", "FAILED", "CANCELLED"].includes(plan.status))
    .forEach((plan) => {
      const current = latest.get(plan.alternativeKey);
      if (!current || plan.version > current.version) {
        latest.set(plan.alternativeKey, plan);
      }
    });
  return [...latest.values()].sort((left, right) =>
    left.alternativeKey.localeCompare(right.alternativeKey),
  );
}

export function familyVersions(plans: TravelPlan[], familyId: string) {
  return plans
    .filter((plan) => plan.planFamilyId === familyId)
    .sort((left, right) => right.version - left.version);
}

export function itemEvidence(
  item: ItineraryItem,
  evidenceById: Map<string, Evidence>,
) {
  return [...item.evidenceIds]
    .map((evidenceId) => evidenceById.get(evidenceId))
    .filter((evidence): evidence is Evidence => Boolean(evidence));
}

export function mutationErrorMessage(error: unknown) {
  if (!error || typeof error !== "object") {
    return "操作未完成，请检查网络后重试。";
  }
  const nested =
    "cause" in error && error.cause && typeof error.cause === "object"
      ? error.cause
      : error;
  const response =
    "response" in nested && nested.response && typeof nested.response === "object"
      ? nested.response
      : undefined;
  const status = Number(
    "status" in nested
      ? nested.status
      : response && "status" in response
        ? response.status
        : Number.NaN,
  );
  const retryAfterSeconds = Number(
    "retryAfterSeconds" in nested ? nested.retryAfterSeconds : Number.NaN,
  );
  const traceId =
    "traceId" in nested && typeof nested.traceId === "string"
      ? nested.traceId
      : undefined;
  if (status === 409) {
    return "方案已发生变化，请刷新后再次提交。";
  }
  if (status === 429) {
    return Number.isFinite(retryAfterSeconds)
      ? `操作过于频繁，请在 ${retryAfterSeconds} 秒后重试。`
      : "操作过于频繁，请稍候再试。";
  }
  if (status >= 500) {
    return traceId
      ? `暂时无法完成操作，当前方案没有被修改。问题编号：${traceId}`
      : "暂时无法完成操作，当前方案没有被修改，可以稍后重试。";
  }
  return "操作未完成，请检查网络后重试。";
}
