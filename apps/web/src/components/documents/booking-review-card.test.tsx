import { fireEvent, screen, waitFor } from "@testing-library/react";
import { BookingReviewCard } from "./booking-review-card";
import { apiAdapter } from "@/api/client";
import { makeBooking, testIds } from "@/test/factories";
import { renderWithProviders } from "@/test/render";

vi.mock("@/api/client", () => ({
  apiAdapter: {
    documents: {
      correct: vi.fn(),
    },
  },
}));

describe("BookingReviewCard", () => {
  it("highlights low-confidence and missing OCR fields", () => {
    renderWithProviders(
      <BookingReviewCard
        documentId={testIds.document}
        booking={makeBooking()}
        onCorrected={vi.fn()}
      />,
    );

    expect(screen.getByText("总置信度 72%")).toBeVisible();
    expect(screen.getByText("开始时间需要人工确认")).toBeVisible();
    expect(screen.getByRole("button", { name: "人工修正" })).toBeVisible();
  });

  it("submits only reviewed fields and refreshes the document", async () => {
    vi.mocked(apiAdapter.documents.correct).mockResolvedValue(
      makeBooking({
        startTime: new Date("2026-10-01T07:00:00Z"),
        missingFields: new Set(),
        reviewStatus: "CORRECTED",
      }),
    );
    const onCorrected = vi.fn();
    renderWithProviders(
      <BookingReviewCard
        documentId={testIds.document}
        booking={makeBooking()}
        onCorrected={onCorrected}
      />,
    );

    fireEvent.click(screen.getByRole("button", { name: "人工修正" }));
    fireEvent.change(screen.getByLabelText("开始时间"), {
      target: { value: "2026-10-01T07:00:00Z" },
    });
    fireEvent.change(screen.getByLabelText(/修正原因/), {
      target: { value: "与脱敏订单原文核对" },
    });
    fireEvent.click(screen.getByRole("button", { name: "保存修正" }));

    await waitFor(() => expect(onCorrected).toHaveBeenCalledOnce());
    expect(apiAdapter.documents.correct).toHaveBeenCalledWith(
      testIds.document,
      testIds.booking,
      {
        corrections: [
          { field: "startTime", value: "2026-10-01T07:00:00Z" },
        ],
        reason: "与脱敏订单原文核对",
      },
      expect.stringMatching(/^booking-correction-/),
    );
  });
});
