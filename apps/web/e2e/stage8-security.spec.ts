import { expect, test } from "@playwright/test";

const title = '<img src=x onerror="window.__stage8Executed=true">';
const taskId = "60000000-0000-0000-0000-000000000008";
const tripId = "40000000-0000-0000-0000-000000000008";

test("renders untrusted task text without executing markup", async ({ page, baseURL }) => {
  const blockedOrigins = new Set<string>();
  await page.route("**/*", async (route) => {
    const url = new URL(route.request().url());
    if (url.origin !== new URL(baseURL!).origin) {
      blockedOrigins.add(url.origin);
      await route.abort();
      return;
    }
    if (url.pathname === "/api/v1/workbench/tasks") {
      await route.fulfill({ json: {
        items: [{
          taskId, tripId, title, type: "PLAN_GENERATION", status: "QUEUED",
          stage: "QUEUED", progress: 0, destination: '<svg onload="window.__stage8Executed=true">',
          startDate: "2026-10-01", endDate: "2026-10-03",
          createdAt: "2026-09-14T00:00:00Z", updatedAt: "2026-09-14T00:00:00Z",
          resultPlanId: null,
        }],
      } });
      return;
    }
    if (url.pathname === "/api/v1/health") {
      await route.fulfill({ json: {
        status: "UP", providerMode: "MOCK", service: "trip-agent-api",
        apiVersion: "v1", timestamp: "2026-09-14T00:00:00Z",
      } });
      return;
    }
    await route.continue();
  });
  await page.goto("/workbench");
  await expect(page.getByRole("heading", { name: title, exact: true })).toBeVisible();
  await expect(page.locator('article img, article svg[onload], [onerror]')).toHaveCount(0);
  expect(await page.evaluate(() => Reflect.get(window, "__stage8Executed"))).toBeUndefined();
  expect([...blockedOrigins]).toEqual([]);
  await page.screenshot({ path: test.info().outputPath("untrusted-task-text.png") });
});
