import type { CreateTripRequest } from "@api-generated/models/CreateTripRequest";
import type { SaveTravelRequirementRequest } from "@api-generated/models/SaveTravelRequirementRequest";
import type { TaskAccepted } from "@api-generated/models/TaskAccepted";
import type { TaskType } from "@api-generated/models/TaskType";
import { create } from "zustand";
import { persist } from "zustand/middleware";

export type RequirementDraft = Omit<
  SaveTravelRequirementRequest,
  "startDate" | "endDate" | "interests" | "mustVisit" | "avoid"
> & {
  startDate: string;
  endDate: string;
  interests: string[];
  mustVisit: string[];
  avoid: string[];
};

export type TripWizardValues = {
  identity: CreateTripRequest;
  requirement: RequirementDraft;
  travelerCount: number;
};

export interface TripDraft {
  values: TripWizardValues;
  currentStep: number;
  tripId?: string;
  createIdempotencyKey: string;
  requirementIdempotencyKey: string;
  updatedAt: string;
}

export type StoredDocumentTask = Pick<
  TaskAccepted,
  "taskId" | "resourceId" | "traceId"
> & {
  tripId: string;
  fileName: string;
  mediaType: string;
  sizeBytes: number;
  sha256: string;
  idempotencyKey: string;
  createdAt: string;
};

export type StoredPlanningTask = Pick<
  TaskAccepted,
  "taskId" | "resourceId" | "traceId"
> & {
  tripId: string;
  type: TaskType;
  createdAt: string;
};

interface TripFlowState {
  userId: string;
  draft: TripDraft;
  recentTripIds: string[];
  activeTripId?: string;
  activePlanId?: string;
  activePlanByTrip: Record<string, string>;
  documentTasks: StoredDocumentTask[];
  planningTasks: StoredPlanningTask[];
  setAuthenticatedUser: (userId: string) => void;
  clearUserSession: () => void;
  saveDraft: (values: TripWizardValues, currentStep: number, tripId?: string) => void;
  loadExampleDraft: () => void;
  resetDraft: () => void;
  rememberTrip: (tripId: string) => void;
  rememberPlan: (tripId: string, planId: string) => void;
  removeTrip: (tripId: string) => void;
  rememberDocument: (document: StoredDocumentTask) => void;
  replaceDocumentTask: (documentId: string, accepted: TaskAccepted) => void;
  rememberPlanningTask: (task: StoredPlanningTask) => void;
}

function localDateAfter(days: number) {
  const date = new Date();
  date.setHours(12, 0, 0, 0);
  date.setDate(date.getDate() + days);
  return date.toISOString().slice(0, 10);
}

function newIdempotencyKey(scope: string) {
  return `${scope}-${crypto.randomUUID()}`;
}

function createBlankDraft(userId: string): TripDraft {
  return {
    values: {
      identity: {
        userId,
        title: "",
        timeZone: Intl.DateTimeFormat().resolvedOptions().timeZone || "Asia/Shanghai",
      },
      requirement: {
        origin: { name: "" },
        destinations: [{ name: "" }],
        startDate: "",
        endDate: "",
        travelers: [
          {
            travelerId: crypto.randomUUID(),
            displayName: "",
            type: "ADULT",
            accessibilityNeeds: [],
          },
        ],
        totalBudget: 0,
        currency: "CNY",
        interests: [],
        pace: "BALANCED",
        mustVisit: [],
        avoid: [],
        freeTextConstraints: "",
      },
      travelerCount: 1,
    },
    currentStep: 0,
    createIdempotencyKey: newIdempotencyKey("trip-create"),
    requirementIdempotencyKey: newIdempotencyKey("requirement-save"),
    updatedAt: new Date().toISOString(),
  };
}

function createExampleDraft(userId: string): TripDraft {
  const draft = createBlankDraft(userId);
  return {
    ...draft,
    values: {
      ...draft.values,
      identity: {
        ...draft.values.identity,
        title: "北京三日文化漫游",
        timeZone: "Asia/Shanghai",
      },
      requirement: {
        ...draft.values.requirement,
        origin: { name: "上海" },
        destinations: [{ name: "北京" }],
        startDate: localDateAfter(30),
        endDate: localDateAfter(32),
        totalBudget: 6000,
        interests: ["历史文化", "本地美食", "城市漫步"],
        pace: "BALANCED",
        mustVisit: ["故宫博物院"],
        avoid: ["凌晨出发"],
        freeTextConstraints: "每天保留午休时间，尽量减少跨城区折返。",
        travelers: [
          {
            travelerId: crypto.randomUUID(),
            displayName: "旅行者 1",
            type: "ADULT",
            accessibilityNeeds: [],
          },
        ],
      },
    },
  };
}

const initialUserId = crypto.randomUUID();

export const useTripFlowStore = create<TripFlowState>()(
  persist(
    (set, get) => ({
      userId: initialUserId,
      draft: createBlankDraft(initialUserId),
      recentTripIds: [],
      activePlanByTrip: {},
      documentTasks: [],
      planningTasks: [],
      setAuthenticatedUser: (userId) =>
        set((state) => {
          if (state.userId === userId) {
            return {
              draft: {
                ...state.draft,
                values: {
                  ...state.draft.values,
                  identity: {
                    ...state.draft.values.identity,
                    userId,
                  },
                },
              },
            };
          }
          return {
            userId,
            draft: createBlankDraft(userId),
            recentTripIds: [],
            activeTripId: undefined,
            activePlanId: undefined,
            activePlanByTrip: {},
            documentTasks: [],
            planningTasks: [],
          };
        }),
      clearUserSession: () => {
        const userId = crypto.randomUUID();
        set({
          userId,
          draft: createBlankDraft(userId),
          recentTripIds: [],
          activeTripId: undefined,
          activePlanId: undefined,
          activePlanByTrip: {},
          documentTasks: [],
          planningTasks: [],
        });
      },
      saveDraft: (values, currentStep, tripId) =>
        set((state) => ({
          draft: {
            ...state.draft,
            values,
            currentStep,
            tripId: tripId ?? state.draft.tripId,
            updatedAt: new Date().toISOString(),
          },
        })),
      loadExampleDraft: () =>
        set((state) => ({ draft: createExampleDraft(state.userId) })),
      resetDraft: () => set((state) => ({ draft: createBlankDraft(state.userId) })),
      rememberTrip: (tripId) =>
        set((state) => ({
          activeTripId: tripId,
          activePlanId: state.activePlanByTrip[tripId],
          recentTripIds: [
            tripId,
            ...state.recentTripIds.filter((item) => item !== tripId),
          ].slice(0, 6),
        })),
      rememberPlan: (tripId, planId) =>
        set((state) => ({
          activeTripId: tripId,
          activePlanId: planId,
          activePlanByTrip: {
            ...state.activePlanByTrip,
            [tripId]: planId,
          },
          recentTripIds: [
            tripId,
            ...state.recentTripIds.filter((item) => item !== tripId),
          ].slice(0, 6),
        })),
      removeTrip: (tripId) =>
        set((state) => ({
          recentTripIds: state.recentTripIds.filter((item) => item !== tripId),
          activeTripId: state.activeTripId === tripId ? undefined : state.activeTripId,
          activePlanId: state.activeTripId === tripId ? undefined : state.activePlanId,
          activePlanByTrip: Object.fromEntries(
            Object.entries(state.activePlanByTrip).filter(([key]) => key !== tripId),
          ),
        })),
      rememberDocument: (document) =>
        set((state) => ({
          documentTasks: [
            document,
            ...state.documentTasks.filter(
              (item) => item.resourceId !== document.resourceId,
            ),
          ].slice(0, 30),
        })),
      replaceDocumentTask: (documentId, accepted) =>
        set((state) => ({
          documentTasks: state.documentTasks.map((item) =>
            item.resourceId === documentId
              ? {
                  ...item,
                  taskId: accepted.taskId,
                  traceId: accepted.traceId,
                  createdAt: accepted.createdAt.toISOString(),
                  idempotencyKey: newIdempotencyKey("document-retry"),
                }
              : item,
          ),
        })),
      rememberPlanningTask: (task) =>
        set((state) => ({
          activeTripId: task.tripId,
          planningTasks: [
            task,
            ...state.planningTasks.filter((item) => item.taskId !== task.taskId),
          ].slice(0, 20),
        })),
    }),
    {
      name: "tripagent-flow",
      version: 1,
    },
  ),
);

export function toRequirementRequest(
  values: TripWizardValues,
): SaveTravelRequirementRequest {
  return {
    ...values.requirement,
    startDate: new Date(`${values.requirement.startDate}T00:00:00`),
    endDate: new Date(`${values.requirement.endDate}T00:00:00`),
    interests: new Set(values.requirement.interests),
    mustVisit: new Set(values.requirement.mustVisit),
    avoid: new Set(values.requirement.avoid),
  };
}
