import type { TravelPlan } from "@api-generated/models/TravelPlan";
import { describe, expect, it } from "vitest";
import {
  latestAlternatives,
  mutationErrorMessage,
  planMetrics,
} from "./plan-view";

function plan(overrides: Partial<TravelPlan> = {}): TravelPlan {
  return {
    planId: crypto.randomUUID(),
    planFamilyId: crypto.randomUUID(),
    tripId: crypto.randomUUID(),
    version: 1,
    alternativeKey: "balanced",
    strategy: "BALANCED",
    title: "Plan",
    summary: "Summary",
    days: [
      {
        dayNumber: 1,
        date: new Date("2026-10-01T00:00:00Z"),
        title: "Day 1",
        items: [
          {
            itemId: crypto.randomUUID(),
            type: "TRANSPORT",
            title: "Train",
            startTime: new Date("2026-10-01T01:00:00Z"),
            endTime: new Date("2026-10-01T03:00:00Z"),
            location: { name: "Beijing" },
            amount: 500,
            currency: "CNY",
            evidenceIds: new Set([crypto.randomUUID()]),
          },
          {
            itemId: crypto.randomUUID(),
            type: "ATTRACTION",
            title: "Museum",
            startTime: new Date("2026-10-01T04:00:00Z"),
            endTime: new Date("2026-10-01T06:00:00Z"),
            location: { name: "Museum" },
            amount: 60,
            currency: "CNY",
            evidenceIds: new Set(),
          },
        ],
      },
    ],
    budgetSummary: {
      totalBudget: 3000,
      estimatedTotal: 560,
      remainingBudget: 2440,
      currency: "CNY",
      items: [],
      dailyBudgets: [],
    },
    conflicts: [],
    evidence: [],
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
    createdAt: new Date("2026-09-01T00:00:00Z"),
    updatedAt: new Date("2026-09-01T00:00:00Z"),
    ...overrides,
  };
}

describe("plan view projections", () => {
  it("derives comparison metrics from one backend plan", () => {
    expect(planMetrics(plan())).toEqual({
      totalItems: 2,
      attractionCount: 1,
      commuteMinutes: 120,
      evidenceCoverage: 50,
      dailyIntensity: "舒缓",
    });
  });

  it("keeps only the newest usable version for each alternative", () => {
    const balancedV1 = plan({ alternativeKey: "balanced", version: 1 });
    const balancedV2 = plan({
      planId: crypto.randomUUID(),
      planFamilyId: balancedV1.planFamilyId,
      alternativeKey: "balanced",
      version: 2,
    });
    const lowTransit = plan({
      planId: crypto.randomUUID(),
      alternativeKey: "low-transit",
      strategy: "LOW_TRANSIT",
    });

    expect(latestAlternatives([balancedV1, lowTransit, balancedV2])).toEqual([
      balancedV2,
      lowTransit,
    ]);
  });

  it("maps API status errors without relying on cross-bundle instanceof", () => {
    expect(mutationErrorMessage({ status: 409, message: "Conflict" })).toContain(
      "方案已发生变化",
    );
    expect(mutationErrorMessage({ status: 429, message: "Limited" })).toContain(
      "过于频繁",
    );
    expect(mutationErrorMessage({ status: 503, message: "Unavailable" })).toContain(
      "稍后重试",
    );
    expect(
      mutationErrorMessage({
        cause: { status: 409, message: "Wrapped generated SDK error" },
      }),
    ).toContain("方案已发生变化");
  });
});
