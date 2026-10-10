import { render, screen } from "@testing-library/react";
import { TaskStatus } from "@api-generated/models/TaskStatus";
import { StatusBadge } from "./status-badge";

describe("StatusBadge", () => {
  it("renders the localized label without exposing the contract value", () => {
    render(<StatusBadge status={TaskStatus.WaitingConfirmation} />);

    expect(screen.getByText("待确认")).not.toHaveAttribute("title");
  });
});
