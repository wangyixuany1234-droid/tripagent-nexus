import { expect, test } from "@playwright/test";
import { makePlan } from "../src/test/factories";
import { TravelPlanToJSON } from "../src/api/generated/src/models/TravelPlan";

test("model evidence remains plain text and unsafe source is not linked", async ({ page, baseURL }) => {
  const payload = '<img src=x onerror="window.__stage9Executed=true">';
  const script = "<script>window.__stage9Executed=true</script>";
  const plan = makePlan();
  plan.title = "Synthetic safety regression";
  plan.evidence[0].title = payload;
  plan.evidence[0].excerpt = script;
  plan.evidence[0].url = "javascript:window.__stage9Executed=true";
  const blocked = new Set<string>();
  await page.route("**/*", async (route) => {
    const url = new URL(route.request().url());
    if (url.origin !== new URL(baseURL!).origin) {
      blocked.add(url.origin);
      return route.abort();
    }
    if (url.pathname === `/api/v1/plans/${plan.planId}`) {
      return route.fulfill({ json: TravelPlanToJSON(plan) });
    }
    if (url.pathname === `/api/v1/trips/${plan.tripId}/plans`) {
      return route.fulfill({ json: { items: [TravelPlanToJSON(plan)] } });
    }
    if (url.pathname.startsWith("/api/")) {
      return route.fulfill({ status: 404, json: { code: "NOT_FOUND" } });
    }
    return route.continue();
  });
  await page.goto(`/plans/${plan.planId}`);
  await expect(page.getByRole("heading", { name: plan.title, exact: true })).toBeVisible();
  const panels = page.getByRole("group", { name: "结果模块" });
  if (await panels.isVisible()) {
    await panels.getByRole("button", { name: "冲突" }).click();
  }
  await page.getByRole("button", { name: "打开证据抽屉" }).click();
  const dialog = page.getByRole("dialog");
  await expect(dialog.getByRole("heading", { name: payload, exact: true })).toBeVisible();
  await expect(dialog.getByText(script, { exact: true })).toBeVisible();
  await expect(dialog.locator("script, img, [onerror], [onload]")).toHaveCount(0);
  await expect(dialog.getByRole("link")).toHaveCount(0);
  expect(await page.evaluate(() => Reflect.get(window, "__stage9Executed"))).toBeUndefined();
  expect([...blocked]).toEqual([]);
  await page.screenshot({ path: test.info().outputPath("stage9-evidence-text.png") });
});
