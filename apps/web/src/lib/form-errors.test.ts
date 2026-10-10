import { vi } from "vitest";
import type { UseFormSetError } from "react-hook-form";
import { ApiClientError } from "@/api/errors";
import type { TripWizardValues } from "@/stores/trip-flow-store";
import { applyServerFieldErrors } from "./form-errors";

describe("applyServerFieldErrors", () => {
  it("maps RFC 6901 pointers into nested React Hook Form fields", () => {
    const setError = vi.fn() as unknown as UseFormSetError<TripWizardValues>;
    const error = new ApiClientError(422, "校验失败", {
      code: "VALIDATION_FAILED",
      message: "校验失败",
      details: [
        {
          field: "/travelers/0/displayName",
          code: "FIELD_INVALID",
          message: "不得为空",
        },
      ],
      traceId: "4bf92f3577b34da6a3ce929d0e0e4736",
      timestamp: new Date("2026-09-06T08:00:00Z"),
    });

    expect(applyServerFieldErrors(error, setError, "requirement")).toBe(true);
    expect(setError).toHaveBeenCalledWith(
      "requirement.travelers.0.displayName",
      expect.objectContaining({
        type: "server",
        message: "请检查此项是否符合要求",
      }),
    );
  });
});
