import type { TripWizardValues } from "@/stores/trip-flow-store";
import { tripWizardSchema, validateWizardStep } from "./trip-wizard-schema";

function validValues(): TripWizardValues {
  return {
    identity: {
      userId: "018f0e31-6ec8-7a59-b871-0242ac120001",
      title: "北京三日游",
      timeZone: "Asia/Shanghai",
    },
    requirement: {
      origin: { name: "上海" },
      destinations: [{ name: "北京" }],
      startDate: "2026-10-01",
      endDate: "2026-10-03",
      travelers: [
        {
          travelerId: "018f0e31-6ec8-7a59-b871-0242ac120002",
          displayName: "旅行者 1",
          type: "ADULT",
          accessibilityNeeds: [],
        },
      ],
      totalBudget: 6000,
      currency: "CNY",
      interests: ["历史文化"],
      pace: "BALANCED",
      mustVisit: ["故宫博物院"],
      avoid: [],
      freeTextConstraints: "",
    },
    travelerCount: 1,
  };
}

describe("tripWizardSchema", () => {
  it("accepts a complete requirement matching the generated contract", () => {
    expect(tripWizardSchema.safeParse(validValues()).success).toBe(true);
  });

  it("rejects an inverted date range before submission", () => {
    const values = validValues();
    values.requirement.endDate = "2026-09-30";

    const result = validateWizardStep(1, values);

    expect(result.success).toBe(false);
    if (!result.success) {
      expect(result.error.issues[0]?.path).toEqual(["requirement", "endDate"]);
    }
  });

  it("rejects a traveler count that does not match traveler details", () => {
    const values = validValues();
    values.travelerCount = 2;

    expect(tripWizardSchema.safeParse(values).success).toBe(false);
  });
});
