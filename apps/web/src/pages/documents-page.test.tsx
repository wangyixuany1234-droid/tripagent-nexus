import { fireEvent, screen } from "@testing-library/react";
import { DocumentsPage } from "./documents-page";
import { apiAdapter } from "@/api/client";
import { testIds } from "@/test/factories";
import { renderWithProviders } from "@/test/render";
import { useAppStore } from "@/stores/app-store";
import { useTripFlowStore } from "@/stores/trip-flow-store";

vi.mock("@/api/client", () => ({
  apiAdapter: {
    trips: { get: vi.fn() },
    documents: {
      upload: vi.fn(),
      get: vi.fn(),
      retry: vi.fn(),
      correct: vi.fn(),
    },
    plans: { create: vi.fn() },
    tasks: {
      get: vi.fn(),
      listAgents: vi.fn(),
      getEventRequest: vi.fn(),
    },
  },
}));

describe("DocumentsPage", () => {
  it("shows an empty state until a trip exists", () => {
    useTripFlowStore.setState({
      activeTripId: undefined,
      documentTasks: [],
      planningTasks: [],
    });
    renderWithProviders(<DocumentsPage />);

    expect(screen.getByText("请先创建旅行")).toBeVisible();
    expect(screen.getByRole("button", { name: "开始规划" })).toBeVisible();
  });

  it("validates file type and stages an accepted sanitized fixture", async () => {
    useTripFlowStore.setState({
      activeTripId: testIds.trip,
      documentTasks: [],
      planningTasks: [],
    });
    useAppStore.setState({ notifications: [] });
    vi.mocked(apiAdapter.trips.get).mockResolvedValue({
      tripId: testIds.trip,
      userId: crypto.randomUUID(),
      title: "北京三日游",
      timeZone: "Asia/Shanghai",
      status: "READY",
      createdAt: new Date(),
      updatedAt: new Date(),
    });
    renderWithProviders(<DocumentsPage />);
    const input = document.querySelector('input[type="file"]') as HTMLInputElement;

    fireEvent.change(input, {
      target: {
        files: [new File(["synthetic"], "unsafe.exe", { type: "application/x-msdownload" })],
      },
    });
    expect(useAppStore.getState().notifications[0]?.title).toBe(
      "不支持的文件类型",
    );

    fireEvent.change(input, {
      target: {
        files: [
          new File(["sanitized booking"], "beijing-hotel.pdf", {
            type: "application/pdf",
          }),
        ],
      },
    });
    expect(await screen.findByText("beijing-hotel.pdf")).toBeVisible();
    expect(screen.getByRole("button", { name: "上传并识别" })).toBeEnabled();
  });
});
