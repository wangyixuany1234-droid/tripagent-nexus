import { ApiClientError, toApiClientError } from "./errors";

describe("toApiClientError", () => {
  it("preserves the backend trace ID and generated error payload", async () => {
    const response = new Response(
      JSON.stringify({
        code: "PLAN_VERSION_CONFLICT",
        message: "方案版本已变化",
        details: [],
        traceId: "4bf92f3577b34da6a3ce929d0e0e4736",
        timestamp: "2026-09-06T08:00:00Z",
      }),
      {
        status: 409,
        headers: { "Content-Type": "application/json" },
      },
    );

    const error = await toApiClientError(response);

    expect(error).toBeInstanceOf(ApiClientError);
    expect(error.status).toBe(409);
    expect(error.payload?.code).toBe("PLAN_VERSION_CONFLICT");
    expect(error.traceId).toBe("4bf92f3577b34da6a3ce929d0e0e4736");
  });

  it("uses the global status message when the response is not JSON", async () => {
    const error = await toApiClientError(new Response(null, { status: 429 }));

    expect(error.message).toBe("操作较为频繁，请稍后再试");
  });

  it.each([
    [401, "登录状态已失效，请重新登录"],
    [403, "当前账号无法执行此操作"],
  ])("maps status %i to a user-facing message", async (status, message) => {
    const error = await toApiClientError(new Response(null, { status }));

    expect(error.message).toBe(message);
  });

  it("preserves Retry-After without exposing protocol details", async () => {
    const error = await toApiClientError(
      new Response(null, {
        status: 429,
        headers: { "Retry-After": "17" },
      }),
    );

    expect(error.retryAfterSeconds).toBe(17);
    expect(error.message).toBe("操作较为频繁，请在 17 秒后重试");
  });

  it("uses the trace header as the support reference for server failures", async () => {
    const error = await toApiClientError(
      new Response("internal stack details", {
        status: 500,
        headers: {
          "Content-Type": "text/plain",
          "X-Trace-Id": "4bf92f3577b34da6a3ce929d0e0e4736",
        },
      }),
    );

    expect(error.traceId).toBe("4bf92f3577b34da6a3ce929d0e0e4736");
    expect(error.message).toBe("暂时无法完成此操作，请稍后重试");
    expect(error.message).not.toContain("stack");
  });

  it("does not expose the response message to the interface", async () => {
    const error = await toApiClientError(
      new Response(
        JSON.stringify({
          code: "INTERNAL_FAILURE",
          message: "database connection pool exhausted",
          details: [],
          traceId: "4bf92f3577b34da6a3ce929d0e0e4736",
          timestamp: "2026-09-06T08:00:00Z",
        }),
        {
          status: 500,
          headers: { "Content-Type": "application/json" },
        },
      ),
    );

    expect(error.message).toBe("暂时无法完成此操作，请稍后重试");
    expect(error.message).not.toContain("database");
  });
});
