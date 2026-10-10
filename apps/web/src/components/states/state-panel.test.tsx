import { fireEvent, render, screen } from "@testing-library/react";
import { EmptyState, ErrorState } from "./state-panel";

describe("state panels", () => {
  it("renders an empty state and runs its recovery action", () => {
    const onAction = vi.fn();
    render(
      <EmptyState
        title="暂无方案"
        description="完成规划后将在这里显示。"
        actionLabel="开始规划"
        onAction={onAction}
      />,
    );

    fireEvent.click(screen.getByRole("button", { name: "开始规划" }));
    expect(onAction).toHaveBeenCalledOnce();
  });

  it("renders an actionable error state without raw backend details", () => {
    render(
      <ErrorState
        title="暂时无法读取内容"
        description="已填写的信息不会丢失。"
        onAction={vi.fn()}
      />,
    );

    expect(screen.getByRole("alert")).toHaveTextContent("已填写的信息不会丢失");
    expect(screen.getByRole("button", { name: "重试" })).toBeVisible();
  });
});
