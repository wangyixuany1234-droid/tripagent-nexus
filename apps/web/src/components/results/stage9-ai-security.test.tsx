import { render, screen } from "@testing-library/react";
import { EvidenceDrawer } from "./evidence-drawer";
import { makePlan } from "@/test/factories";

describe("stage9 model and retrieval text", () => {
  it.each([
    "<script>window.__stage9Executed=true</script>",
    '<img src=x onerror="window.__stage9Executed=true">',
    '<svg onload="window.__stage9Executed=true">',
  ])("renders source content as inert text: %s", (payload) => {
    const item = { ...makePlan().evidence[0], title: payload, excerpt: payload };
    render(<EvidenceDrawer open onOpenChange={vi.fn()} evidence={[item]} />);
    expect(screen.getByRole("heading", { name: payload })).toBeVisible();
    expect(screen.getByRole("dialog").querySelector("script, img, [onload], [onerror]")).toBeNull();
    expect(Reflect.get(window, "__stage9Executed")).toBeUndefined();
  });

  it.each(["javascript:void(0)", "data:text/html,synthetic", "file:///synthetic"])(
    "does not activate unsafe source URL: %s",
    (url) => {
      render(<EvidenceDrawer open onOpenChange={vi.fn()}
        evidence={[{ ...makePlan().evidence[0], url }]} />);
      expect(screen.queryByRole("link")).not.toBeInTheDocument();
    },
  );

  it("does not fabricate a link when there is no source", () => {
    render(<EvidenceDrawer open onOpenChange={vi.fn()} evidence={[]} />);
    expect(screen.queryByRole("link")).not.toBeInTheDocument();
    expect(screen.getByRole("dialog").querySelector("article")).toBeNull();
  });
});
