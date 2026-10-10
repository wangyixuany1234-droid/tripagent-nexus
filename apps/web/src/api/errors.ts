import {
  ApiErrorFromJSON,
  type ApiError,
} from "@api-generated/models/ApiError";

const statusMessages: Record<number, string> = {
  400: "请检查填写内容后重试",
  401: "登录状态已失效，请重新登录",
  403: "当前账号无法执行此操作",
  404: "未找到相关内容",
  409: "内容已更新，请刷新后重试",
  422: "请检查填写内容",
  429: "操作较为频繁，请稍后再试",
};

export class ApiClientError extends Error {
  readonly status: number;
  readonly payload?: ApiError;
  readonly traceId?: string;
  readonly retryAfterSeconds?: number;

  constructor(
    status: number,
    message: string,
    payload?: ApiError,
    traceId?: string,
    retryAfterSeconds?: number,
  ) {
    super(message);
    this.name = "ApiClientError";
    this.status = status;
    this.payload = payload;
    this.traceId = payload?.traceId ?? traceId;
    this.retryAfterSeconds = retryAfterSeconds;
  }
}

function retryAfterSeconds(value: string | null) {
  if (!value) {
    return undefined;
  }
  const seconds = Number(value);
  if (Number.isFinite(seconds) && seconds >= 0) {
    return Math.ceil(seconds);
  }
  const retryAt = Date.parse(value);
  if (Number.isNaN(retryAt)) {
    return undefined;
  }
  return Math.max(0, Math.ceil((retryAt - Date.now()) / 1000));
}

export async function toApiClientError(response: Response) {
  const traceId = response.headers.get("X-Trace-Id") ?? undefined;
  const retryAfter = retryAfterSeconds(response.headers.get("Retry-After"));
  let payload: ApiError | undefined;

  if (response.headers.get("content-type")?.includes("application/json")) {
    try {
      payload = ApiErrorFromJSON(await response.json());
    } catch {
      payload = undefined;
    }
  }

  const fallback = retryAfter === undefined
    ? statusMessages[response.status] ??
      (response.status >= 500 ? "暂时无法完成此操作，请稍后重试" : "操作未完成，请稍后重试")
    : `操作较为频繁，请在 ${retryAfter} 秒后重试`;

  return new ApiClientError(response.status, fallback, payload, traceId, retryAfter);
}
