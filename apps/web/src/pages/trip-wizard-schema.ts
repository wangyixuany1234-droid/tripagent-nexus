import { z } from "zod";
import type { TripWizardValues } from "@/stores/trip-flow-store";

const placeSchema = z.object({
  placeId: z.string().uuid().optional(),
  name: z.string().trim().min(1, "请输入地点").max(300, "地点名称不能超过 300 个字符"),
  address: z.string().max(500).optional(),
  latitude: z.number().min(-90).max(90).optional(),
  longitude: z.number().min(-180).max(180).optional(),
});

const travelerSchema = z.object({
  travelerId: z.string().uuid(),
  displayName: z.string().trim().min(1, "请输入同行人员称呼").max(100),
  type: z.enum(["ADULT", "CHILD", "INFANT", "SENIOR"]),
  accessibilityNeeds: z.array(z.string().trim().min(1).max(100)).max(20),
});

const identitySchema = z.object({
  userId: z.string().uuid(),
  title: z.string().trim().min(1, "请输入旅行名称").max(160),
  timeZone: z.string().trim().min(1).max(64),
});

const requirementSchema = z.object({
  origin: placeSchema,
  destinations: z.array(placeSchema).min(1).max(20),
  startDate: z.string().date("请选择有效的出发日期"),
  endDate: z.string().date("请选择有效的返程日期"),
  travelers: z.array(travelerSchema).min(1).max(50),
  totalBudget: z.number().positive("总预算必须大于 0"),
  currency: z.string().regex(/^[A-Z]{3}$/, "请输入三位大写币种代码"),
  interests: z.array(z.string().trim().min(1).max(100)).max(50),
  pace: z.enum(["RELAXED", "BALANCED", "INTENSIVE"]),
  mustVisit: z.array(z.string().trim().min(1).max(200)).max(50),
  avoid: z.array(z.string().trim().min(1).max(200)).max(50),
  freeTextConstraints: z.string().max(5000),
});

export const tripWizardSchema: z.ZodType<TripWizardValues> = z
  .object({
    identity: identitySchema,
    requirement: requirementSchema,
    travelerCount: z.number().int().min(1, "至少需要 1 位旅行者").max(20),
  })
  .superRefine((values, context) => {
    if (values.requirement.endDate < values.requirement.startDate) {
      context.addIssue({
        code: "custom",
        path: ["requirement", "endDate"],
        message: "返程日期不能早于出发日期",
      });
    }
    if (values.requirement.travelers.length !== values.travelerCount) {
      context.addIssue({
        code: "custom",
        path: ["requirement", "travelers"],
        message: "同行人员数量与填写人数不一致",
      });
    }
  });

const stepSchemas = [
  z.object({
    identity: identitySchema,
    requirement: z.object({
      origin: placeSchema,
      destinations: z.array(placeSchema).min(1).max(20),
    }),
  }),
  z
    .object({
      requirement: z.object({
        startDate: z.string().date("请选择有效的出发日期"),
        endDate: z.string().date("请选择有效的返程日期"),
      }),
      travelerCount: z.number().int().min(1, "至少需要 1 位旅行者").max(20),
    })
    .superRefine((values, context) => {
      if (values.requirement.endDate < values.requirement.startDate) {
        context.addIssue({
          code: "custom",
          path: ["requirement", "endDate"],
          message: "返程日期不能早于出发日期",
        });
      }
    }),
  z.object({
    requirement: z.object({
      totalBudget: z.number().positive("总预算必须大于 0"),
      currency: z.string().regex(/^[A-Z]{3}$/, "请选择币种"),
    }),
  }),
  z.object({ requirement: z.object({ interests: z.array(z.string()).max(50) }) }),
  z.object({
    requirement: z.object({
      pace: z.enum(["RELAXED", "BALANCED", "INTENSIVE"]),
    }),
  }),
  z.object({
    requirement: z.object({
      mustVisit: z.array(z.string().max(200)).max(50),
      avoid: z.array(z.string().max(200)).max(50),
      freeTextConstraints: z.string().max(5000),
    }),
  }),
  z.object({
    requirement: z.object({
      travelers: z.array(travelerSchema).min(1).max(50),
    }),
  }),
] as const;

export function validateWizardStep(step: number, values: TripWizardValues) {
  const schema = stepSchemas[step];
  if (!schema) {
    return tripWizardSchema.safeParse(values);
  }
  return schema.safeParse(values);
}
