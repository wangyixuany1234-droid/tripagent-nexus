import { ActionsApi } from "@api-generated/apis/ActionsApi";
import { DeveloperApi } from "@api-generated/apis/DeveloperApi";
import { DocumentsApi } from "@api-generated/apis/DocumentsApi";
import { FeedbackApi } from "@api-generated/apis/FeedbackApi";
import { MapsApi } from "@api-generated/apis/MapsApi";
import { PlansApi } from "@api-generated/apis/PlansApi";
import { PreferencesApi } from "@api-generated/apis/PreferencesApi";
import { SystemApi } from "@api-generated/apis/SystemApi";
import { TasksApi } from "@api-generated/apis/TasksApi";
import { TripsApi } from "@api-generated/apis/TripsApi";
import {
  WorkbenchApi,
  type ListWorkbenchTasksRequest,
} from "@api-generated/apis/WorkbenchApi";
import { TaskAcceptedFromJSON, type TaskAccepted } from "@api-generated/models/TaskAccepted";
import type { BookingCorrectionRequest } from "@api-generated/models/BookingCorrectionRequest";
import type { ConfirmGuardedActionRequest } from "@api-generated/models/ConfirmGuardedActionRequest";
import type { ConfirmPlanRequest } from "@api-generated/models/ConfirmPlanRequest";
import type { CreateFeedbackRequest } from "@api-generated/models/CreateFeedbackRequest";
import type { CreateGuardedActionRequest } from "@api-generated/models/CreateGuardedActionRequest";
import type { CreateTravelPlanRequest } from "@api-generated/models/CreateTravelPlanRequest";
import type { CreateTripRequest } from "@api-generated/models/CreateTripRequest";
import type { RejectGuardedActionRequest } from "@api-generated/models/RejectGuardedActionRequest";
import type { ReplanRequest } from "@api-generated/models/ReplanRequest";
import type { SaveUserPreferenceRequest } from "@api-generated/models/SaveUserPreferenceRequest";
import type { SaveTravelRequirementRequest } from "@api-generated/models/SaveTravelRequirementRequest";
import { Configuration } from "@api-generated/runtime";
import { environment } from "@/lib/environment";
import { useAppStore } from "@/stores/app-store";
import { toApiClientError } from "./errors";

let accessTokenProvider: (() => string | undefined) | undefined;

export function setAccessTokenProvider(provider?: () => string | undefined) {
  accessTokenProvider = provider;
}

function reportNetworkFailure() {
  useAppStore.getState().addNotification({
    tone: "error",
    title: "网络连接不稳定",
    message: "请检查网络后重试，已填写的内容不会丢失。",
  });
}

async function rejectApiResponse(response: Response): Promise<never> {
  const error = await toApiClientError(response);
  const tone = response.status === 409 || response.status === 422 ? "warning" : "error";
  useAppStore.getState().addNotification({
    tone,
    title: error.message,
    message:
      response.status >= 500 && error.traceId
        ? `问题编号：${error.traceId}`
        : undefined,
  });

  if (response.status === 401) {
    window.dispatchEvent(new CustomEvent("tripagent:session-expired"));
  }
  throw error;
}

export async function apiFetch(input: RequestInfo | URL, init?: RequestInit) {
  const headers = new Headers(init?.headers);
  headers.set("X-Request-Id", crypto.randomUUID());

  const accessToken = accessTokenProvider?.();
  if (accessToken) {
    headers.set("Authorization", `Bearer ${accessToken}`);
  }

  let response: Response;
  try {
    response = await fetch(input, {
      ...init,
      headers,
      credentials: "include",
    });
  } catch (cause) {
    if (cause instanceof DOMException && cause.name === "AbortError") {
      throw cause;
    }
    reportNetworkFailure();
    throw cause;
  }

  const traceId = response.headers.get("X-Trace-Id") ?? undefined;
  if (traceId) {
    useAppStore.getState().setLastTraceId(traceId);
  }

  return response;
}

const configuration = new Configuration({
  basePath: environment.apiBaseUrl,
  fetchApi: apiFetch,
  credentials: "include",
  middleware: [
    {
      post: async ({ response }) => {
        if (!response.ok) {
          return rejectApiResponse(response);
        }
        return response;
      },
    },
  ],
});

const generated = {
  actions: new ActionsApi(configuration),
  developer: new DeveloperApi(configuration),
  documents: new DocumentsApi(configuration),
  feedback: new FeedbackApi(configuration),
  maps: new MapsApi(configuration),
  plans: new PlansApi(configuration),
  preferences: new PreferencesApi(configuration),
  system: new SystemApi(configuration),
  tasks: new TasksApi(configuration),
  trips: new TripsApi(configuration),
  workbench: new WorkbenchApi(configuration),
};

interface UploadOptions {
  tripId: string;
  file: File;
  idempotencyKey: string;
  signal?: AbortSignal;
  onProgress?: (progress: number) => void;
}

async function uploadDocument({
  tripId,
  file,
  idempotencyKey,
  signal,
  onProgress,
}: UploadOptions): Promise<TaskAccepted> {
  const request = await generated.documents.parseDocumentRequestOpts({
    tripId,
    file,
    idempotencyKey,
  });

  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    xhr.open(request.method, new URL(request.path, `${environment.apiBaseUrl}/`));
    xhr.withCredentials = true;
    Object.entries(request.headers).forEach(([name, value]) => {
      if (value !== undefined) {
        xhr.setRequestHeader(name, value);
      }
    });
    xhr.setRequestHeader("X-Request-Id", crypto.randomUUID());
    const accessToken = accessTokenProvider?.();
    if (accessToken) {
      xhr.setRequestHeader("Authorization", `Bearer ${accessToken}`);
    }
    xhr.upload.onprogress = (event) => {
      if (event.lengthComputable) {
        onProgress?.(Math.round((event.loaded / event.total) * 100));
      }
    };
    xhr.onerror = () => {
      reportNetworkFailure();
      reject(new TypeError("Document upload failed."));
    };
    xhr.onabort = () => reject(new DOMException("Upload aborted.", "AbortError"));
    xhr.onload = () => {
      const traceId = xhr.getResponseHeader("X-Trace-Id") ?? undefined;
      if (traceId) {
        useAppStore.getState().setLastTraceId(traceId);
      }
      const response = new Response(xhr.responseText, {
        status: xhr.status,
        headers: {
          "Content-Type": xhr.getResponseHeader("Content-Type") ?? "application/json",
          ...(traceId ? { "X-Trace-Id": traceId } : {}),
        },
      });
      if (xhr.status < 200 || xhr.status >= 300) {
        void rejectApiResponse(response).catch(reject);
        return;
      }
      try {
        resolve(TaskAcceptedFromJSON(JSON.parse(xhr.responseText)));
      } catch (cause) {
        reject(cause);
      }
    };
    signal?.addEventListener("abort", () => xhr.abort(), { once: true });
    xhr.send(request.body as XMLHttpRequestBodyInit);
  });
}

export const apiAdapter = {
  system: {
    getHealth: () => generated.system.getSystemHealth(),
    getStatus: () => generated.system.getSystemStatus(),
  },
  workbench: {
    list: (request: ListWorkbenchTasksRequest) =>
      generated.workbench.listWorkbenchTasks(request),
  },
  preferences: {
    list: (userId: string) => generated.preferences.listUserPreferences({ userId }),
    create: (
      userId: string,
      request: SaveUserPreferenceRequest,
      idempotencyKey: string,
    ) =>
      generated.preferences.createUserPreference({
        userId,
        saveUserPreferenceRequest: request,
        idempotencyKey,
      }),
    update: (
      userId: string,
      preferenceId: string,
      request: SaveUserPreferenceRequest,
      idempotencyKey: string,
    ) =>
      generated.preferences.updateUserPreference({
        userId,
        preferenceId,
        saveUserPreferenceRequest: request,
        idempotencyKey,
      }),
    reject: (userId: string, preferenceId: string, idempotencyKey: string) =>
      generated.preferences.rejectInferredPreference({
        userId,
        preferenceId,
        idempotencyKey,
      }),
    delete: (userId: string, preferenceId: string, idempotencyKey: string) =>
      generated.preferences.deleteUserPreference({
        userId,
        preferenceId,
        idempotencyKey,
      }),
  },
  feedback: {
    list: (tripId: string) => generated.feedback.listFeedback({ tripId }),
    create: (request: CreateFeedbackRequest, idempotencyKey: string) =>
      generated.feedback.createFeedback({
        createFeedbackRequest: request,
        idempotencyKey,
      }),
  },
  developer: {
    getTrace: (traceId: string) => generated.developer.getDeveloperTrace({ traceId }),
  },
  maps: {
    getTile: async (zoom: number, x: number, y: number, signal?: AbortSignal) => {
      const response = await generated.maps.getMapTileRaw(
        { zoom, x, y },
        signal ? { signal } : undefined,
      );
      const blob = await response.value();
      return {
        data: await blob.arrayBuffer(),
        cacheControl: response.raw.headers.get("Cache-Control"),
        expires: response.raw.headers.get("Expires"),
        etag: response.raw.headers.get("ETag") ?? undefined,
      };
    },
  },
  trips: {
    create: (request: CreateTripRequest, idempotencyKey: string) =>
      generated.trips.createTrip({ createTripRequest: request, idempotencyKey }),
    get: (tripId: string, signal?: AbortSignal) =>
      generated.trips.getTrip({ tripId }, signal ? { signal } : undefined),
    saveRequirement: (
      tripId: string,
      request: SaveTravelRequirementRequest,
      idempotencyKey: string,
    ) =>
      generated.trips.saveTravelRequirement({
        tripId,
        saveTravelRequirementRequest: request,
        idempotencyKey,
      }),
  },
  documents: {
    upload: uploadDocument,
    get: (documentId: string) => generated.documents.getParsedDocument({ documentId }),
    retry: (documentId: string, idempotencyKey: string) =>
      generated.documents.retryDocumentParsing({ documentId, idempotencyKey }),
    correct: (
      documentId: string,
      bookingId: string,
      request: BookingCorrectionRequest,
      idempotencyKey: string,
    ) =>
      generated.documents.correctParsedBooking({
        documentId,
        bookingId,
        bookingCorrectionRequest: request,
        idempotencyKey,
      }),
  },
  plans: {
    create: (request: CreateTravelPlanRequest, idempotencyKey: string) =>
      generated.plans.createTravelPlan({
        createTravelPlanRequest: request,
        idempotencyKey,
      }),
    list: (tripId: string, signal?: AbortSignal) =>
      generated.plans.listTravelPlans({ tripId }, signal ? { signal } : undefined),
    get: (planId: string, signal?: AbortSignal) =>
      generated.plans.getTravelPlan({ planId }, signal ? { signal } : undefined),
    compare: (planId: string, otherPlanId: string, signal?: AbortSignal) =>
      generated.plans.compareTravelPlanVersions(
        { planId, otherPlanId },
        signal ? { signal } : undefined,
      ),
    replan: (planId: string, request: ReplanRequest, idempotencyKey: string) =>
      generated.plans.replanTravelPlan({
        planId,
        replanRequest: request,
        idempotencyKey,
      }),
    confirm: (planId: string, request: ConfirmPlanRequest, idempotencyKey: string) =>
      generated.plans.confirmTravelPlan({
        planId,
        confirmPlanRequest: request,
        idempotencyKey,
      }),
  },
  actions: {
    propose: (request: CreateGuardedActionRequest, idempotencyKey: string) =>
      generated.actions.proposeGuardedAction({
        createGuardedActionRequest: request,
        idempotencyKey,
      }),
    get: (actionId: string) =>
      generated.actions.getGuardedAction({ actionId }),
    confirm: (
      actionId: string,
      request: ConfirmGuardedActionRequest,
      idempotencyKey: string,
    ) =>
      generated.actions.confirmGuardedAction({
        actionId,
        confirmGuardedActionRequest: request,
        idempotencyKey,
      }),
    reject: (
      actionId: string,
      request: RejectGuardedActionRequest,
      idempotencyKey: string,
    ) =>
      generated.actions.rejectGuardedAction({
        actionId,
        rejectGuardedActionRequest: request,
        idempotencyKey,
      }),
  },
  tasks: {
    get: (taskId: string, signal?: AbortSignal) =>
      generated.tasks.getTask({ taskId }, signal ? { signal } : undefined),
    cancel: (taskId: string, idempotencyKey: string) =>
      generated.tasks.cancelTask({ taskId, idempotencyKey }),
    listAgents: (taskId: string, signal?: AbortSignal) =>
      generated.tasks.listTaskAgentExecutions(
        { taskId },
        signal ? { signal } : undefined,
      ),
    getEventRequest: (taskId: string, lastEventID?: string) =>
      generated.tasks.streamTaskEventsRequestOpts({ taskId, lastEventID }),
  },
} as const;
