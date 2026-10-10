import { render, screen } from "@testing-library/react";
import { AgentProgress } from "./agent-progress";
import { makeAgent } from "@/test/factories";

describe("AgentProgress", () => {
  it("shows localized agent names, evidence counts, and live status", () => {
    render(
      <AgentProgress
        agents={[
          makeAgent({ agentType: "RequirementAgent", status: "SUCCEEDED" }),
          makeAgent({
            agentResultId: crypto.randomUUID(),
            agentType: "ResearchAgent",
            status: "RUNNING",
            evidenceIds: ["evidence-1", "evidence-2"],
          }),
        ]}
      />,
    );

    expect(screen.getByText("需求理解")).toBeVisible();
    expect(screen.getByText("旅行研究")).toBeVisible();
    expect(screen.getByText("2 条证据")).toBeVisible();
    expect(screen.getByText("处理中")).toBeVisible();
  });

  it("uses a user-facing fallback for unknown agent names", () => {
    render(<AgentProgress agents={[makeAgent({ agentType: "FutureAgent" })]} />);

    expect(screen.getByText("旅行安排")).toBeVisible();
    expect(screen.queryByText("FutureAgent")).not.toBeInTheDocument();
  });
});
