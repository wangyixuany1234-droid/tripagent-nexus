import { expect, test, type Page } from "@playwright/test";

const planId = "018f0e31-6ec8-7a59-b871-0242ac121090";
const timestamp = "2026-09-09T08:00:00Z";

async function installHealthRoute(page: Page) {
  await page.route("**/api/v1/health", (route) =>
    route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        status: "UP",
        service: "trip-agent-api",
        apiVersion: "v1",
        providerMode: "MOCK",
        timestamp,
      }),
    }),
  );
}

function errorBody(status: number) {
  return {
    code: `SYNTHETIC_${status}`,
    message: `internal backend detail for ${status}`,
    details: [],
    traceId: "abcdef0123456789abcdef0123456789",
    timestamp,
  };
}

test.beforeEach(async ({ page }) => {
  test.skip(
    process.env.QA_REAL_E2E === "true",
    "Synthetic HTTP failures run only in the isolated browser suite.",
  );
  await installHealthRoute(page);
});

test("redirects an expired session to login and preserves the return path", async ({
  page,
}) => {
  const pageErrors: string[] = [];
  page.on("pageerror", (error) => pageErrors.push(error.message));
  await page.route(`**/api/v1/plans/${planId}`, (route) =>
    route.fulfill({
      status: 401,
      contentType: "application/json",
      body: JSON.stringify(errorBody(401)),
    }),
  );

  await page.goto(`/plans/${planId}`);

  await expect(page).toHaveURL(
    new RegExp(`/login\\?returnTo=%2Fplans%2F${planId}$`),
  );
  await expect(
    page.getByRole("heading", { name: "请重新登录" }),
  ).toBeVisible();
  await expect(page.getByRole("link", { name: "返回并重新登录" })).toHaveAttribute(
    "href",
    `/plans/${planId}`,
  );
  expect(pageErrors).toEqual([]);
});

test("shows a user-facing permission message for forbidden requests", async ({
  page,
}) => {
  await page.route(`**/api/v1/plans/${planId}`, (route) =>
    route.fulfill({
      status: 403,
      contentType: "application/json",
      body: JSON.stringify(errorBody(403)),
    }),
  );

  await page.goto(`/plans/${planId}`);
  await expect(page.getByRole("alert")).toContainText("无法加载旅行方案");
  await page.getByRole("button", { name: "打开通知" }).click();

  const notifications = page.getByRole("menu", { name: "打开通知" });
  await expect(
    notifications.getByText("当前账号无法执行此操作"),
  ).toHaveCount(1);
  await expect(page.locator("body")).not.toContainText(
    "internal backend detail",
  );
});

test("shows a support reference for server failures without raw details", async ({
  page,
}) => {
  await page.route(`**/api/v1/plans/${planId}`, (route) =>
    route.fulfill({
      status: 500,
      contentType: "application/json",
      body: JSON.stringify(errorBody(500)),
    }),
  );

  await page.goto(`/plans/${planId}`);
  await expect(page.getByRole("alert")).toContainText("无法加载旅行方案");
  await page.getByRole("button", { name: "打开通知" }).click();

  const notifications = page.getByRole("menu", { name: "打开通知" });
  await expect(
    notifications.getByText(
      "问题编号：abcdef0123456789abcdef0123456789",
    ),
  ).toHaveCount(1);
  await expect(page.locator("body")).not.toContainText(
    "internal backend detail",
  );
});
