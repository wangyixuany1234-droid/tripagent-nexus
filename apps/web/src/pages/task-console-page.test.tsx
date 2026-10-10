import { screen } from "@testing-library/react";
import { TaskConsolePage } from "./task-console-page";
import { apiAdapter } from "@/api/client";
import { useTaskMonitor } from "@/hooks/use-task-monitor";
import { makeTask, testIds } from "@/test/factories";
import { renderWithProviders } from "@/test/render";
import { useTripFlowStore } from "@/stores/trip-flow-store";

vi.mock("@/api/client", () => ({
  apiAdapter: {
    tasks: {
      listAgents: vi.fn(),
    },
  },
}));
vi.mock("@/hooks/use-task-monitor", () => ({
  agentExecutionsQueryKey: (taskId: string) => ["tasks", taskId, "agents"],
  useTaskMonitor: vi.fn(),
}));

function setStoredTask() {
  useTripFlowStore.setState({
    planningTasks: [
      {
        taskId: testIds.task,
        resourceId: testIds.plan,
        traceId: "11111111111111111111111111111111",
        tripId: testIds.trip,
        type: "PLAN_GENERATION",
        createdAt: "2026-09-01T08:00:00Z",
      },
    ],
  });
}

describe("TaskConsolePage", () => {
  it("renders Agent progress from backend projections", async () => {
    setStoredTask();
    vi.mocked(useTaskMonitor).mockReturnValue({
      data: makeTask(),
      events: [],
      connectionMode: "sse",
      isTerminal: false,
      isLoading: false,
      isError: false,
      refetch: vi.fn(),
    } as never);
    vi.mocked(apiAdapter.tasks.listAgents).mockResolvedValue({
      items: [
        {
          agentExecutionId: crypto.randomUUID(),
          taskId: testIds.task,
          agentName: "RequirementAgent",
          status: "RUNNING",
          summary: "internal summary",
          evidenceCount: 0,
          warnings: [],
        },
      ],
    });
    renderWithProviders(<TaskConsolePage />);

    expect(await screen.findByText("需求理解")).toBeVisible();
    expect(screen.getByText("整理旅行需求与同行人约束，正在进行")).toBeVisible();
    expect(screen.queryByText("internal summary")).not.toBeInTheDocument();
  });

  it("renders partial success as usable completion", async () => {
    setStoredTask();
    vi.mocked(useTaskMonitor).mockReturnValue({
      data: makeTask({
        status: "PARTIAL_SUCCESS",
        stage: "PARTIAL_SUCCESS",
        progress: 100,
        resultResourceIds: new Set([testIds.plan]),
        degradedComponents: new Set(["RETRIEVAL"]),
      }),
      events: [],
      connectionMode: "polling",
      isTerminal: true,
      isLoading: false,
      isError: false,
      refetch: vi.fn(),
    } as never);
    vi.mocked(apiAdapter.tasks.listAgents).mockResolvedValue({ items: [] });
    renderWithProviders(<TaskConsolePage />);

    expect(
      screen.getByRole("heading", {
        name: "方案已生成，部分实时信息暂不可用",
      }),
    ).toBeVisible();
    expect(await screen.findByRole("link", { name: "查看旅行方案" })).toBeVisible();
  });

  it("leaves loading and presents a recovery action after failure", () => {
    setStoredTask();
    vi.mocked(useTaskMonitor).mockReturnValue({
      data: undefined,
      events: [],
      connectionMode: "polling",
      isTerminal: false,
      isLoading: false,
      isError: true,
      refetch: vi.fn(),
    } as never);
    renderWithProviders(<TaskConsolePage />);

    expect(screen.getByRole("alert")).toHaveTextContent("暂时无法查看进度");
    expect(screen.getByRole("button", { name: "重试" })).toBeVisible();
  });
});
