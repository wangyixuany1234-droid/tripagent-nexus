import { render, screen } from "@testing-library/react";
import { Stepper } from "./stepper";

describe("Stepper", () => {
  it("marks the active item as the current step", () => {
    render(
      <Stepper
        items={[
          { id: "one", label: "需求理解", state: "complete" },
          { id: "two", label: "证据检索", state: "current" },
          { id: "three", label: "规则审校", state: "upcoming" },
        ]}
      />,
    );

    expect(screen.getByText("证据检索").closest("li")).toHaveAttribute(
      "aria-current",
      "step",
    );
  });
});
