import AxeBuilder from "@axe-core/playwright";
import { expect, test } from "@playwright/test";

const pages = [
  { path: "/", heading: "TripAgent Nexus" },
  { path: "/trips/new", heading: "创建旅行" },
  { path: "/design-system", heading: "界面预览" },
] as const;

for (const target of pages) {
  test(`${target.path} has no serious accessibility violations`, async ({
    page,
  }, testInfo) => {
    test.setTimeout(90_000);
    await page.route("**/api/v1/maps/tiles/**", (route) =>
      route.fulfill({
        status: 200,
        contentType: "image/png",
        body: Buffer.from(
          "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
          "base64",
        ),
      }),
    );
    await page.goto(target.path);
    await expect(
      page.getByRole("heading", { name: target.heading, exact: true }),
    ).toBeVisible();

    const result = await new AxeBuilder({ page })
      .withTags(["wcag2a", "wcag2aa", "wcag21a", "wcag21aa"])
      .include("main")
      .exclude(".maplibregl-map")
      .exclude("canvas")
      .analyze();
    const serious = result.violations.filter((violation) =>
      ["serious", "critical"].includes(violation.impact ?? ""),
    );
    await testInfo.attach("axe-results", {
      body: JSON.stringify(result, null, 2),
      contentType: "application/json",
    });

    expect(serious).toEqual([]);
  });
}
