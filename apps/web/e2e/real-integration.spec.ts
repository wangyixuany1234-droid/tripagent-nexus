import path from "node:path";
import { expect, test, type Page } from "@playwright/test";

const realIntegration = process.env.QA_REAL_E2E === "true";
const fixturePath = path.resolve(
  process.cwd(),
  "../../qa/fixtures/documents/low-confidence.png",
);

function apiPath(url: string) {
  return new URL(url).pathname;
}

function collectUnhandledErrors(page: Page) {
  const errors: string[] = [];
  page.on("pageerror", (error) => errors.push(error.message));
  page.on("console", (message) => {
    if (message.type() === "error") {
      errors.push(message.text());
    }
  });
  return errors;
}

test.describe("real frontend and backend integration", () => {
  test.skip(
    !realIntegration,
    "Requires the isolated QA Compose stack and explicit QA_REAL_E2E=true.",
  );

  test("completes the Beijing planning workflow against real services", async ({
    page,
  }) => {
    test.setTimeout(180_000);
    const unhandledErrors = collectUnhandledErrors(page);
    let tripCreateRequests = 0;
    let planCreateRequests = 0;
    let replanRequests = 0;

    page.on("request", (request) => {
      const pathname = apiPath(request.url());
      if (request.method() === "POST" && pathname === "/api/v1/trips") {
        tripCreateRequests += 1;
      }
      if (request.method() === "POST" && pathname === "/api/v1/plans") {
        planCreateRequests += 1;
      }
      if (
        request.method() === "POST" &&
        /^\/api\/v1\/plans\/[^/]+\/replan$/.test(pathname)
      ) {
        replanRequests += 1;
      }
    });

    await page.addInitScript(() => {
      window.localStorage.clear();
    });
    await page.goto("/trips/new");
    await expect(
      page.getByRole("heading", { name: "创建旅行", level: 1 }),
    ).toBeVisible();

    await page.getByLabel("旅行名称").fill("北京三日真实联调");
    await page.getByLabel("出发地").fill("上海");
    await page.getByLabel("目的地").fill("北京");
    await page.getByRole("button", { name: "下一步" }).click();

    await page.getByLabel("出发日期").fill("2026-10-01");
    await page.getByLabel("返程日期").fill("2026-10-03");
    await page.getByLabel("旅行人数").fill("2");
    await page.getByRole("button", { name: "下一步" }).click();

    await page.getByLabel("总预算").fill("6000");
    await page.getByRole("button", { name: "下一步" }).click();
    await page.getByRole("button", { name: "历史文化" }).click();
    await page.getByRole("button", { name: "本地美食" }).click();
    await page.getByRole("button", { name: "下一步" }).click();
    await page
      .getByRole("button", { name: "均衡 体验密度与休息兼顾" })
      .click();
    await page.getByRole("button", { name: "下一步" }).click();

    await page.getByLabel("必去地点").fill("故宫博物院");
    await page.getByLabel("希望避开").fill("凌晨出发");
    await page
      .getByLabel("其他约束")
      .fill("每天保留午休时间，减少跨城区往返。");
    await page.getByRole("button", { name: "下一步" }).click();
    await page.getByLabel("同行人员 1").fill("旅行者一");
    await page.getByLabel("同行人员 2").fill("旅行者二");
    await page.getByRole("button", { name: "下一步" }).click();

    const tripResponsePromise = page.waitForResponse(
      (response) =>
        response.request().method() === "POST" &&
        apiPath(response.url()) === "/api/v1/trips",
    );
    const requirementResponsePromise = page.waitForResponse(
      (response) =>
        response.request().method() === "PUT" &&
        /\/api\/v1\/trips\/[^/]+\/requirements$/.test(apiPath(response.url())),
    );
    await page.getByRole("button", { name: "创建旅行" }).dblclick();
    const tripResponse = await tripResponsePromise;
    expect(tripResponse.status()).toBe(201);
    const trip = (await tripResponse.json()) as { tripId: string };
    expect((await requirementResponsePromise).status()).toBe(200);
    await expect(page).toHaveURL(
      new RegExp(`/trips/${trip.tripId}/documents$`),
    );
    expect(tripCreateRequests).toBe(1);

    await page.locator('input[type="file"]').setInputFiles(fixturePath);
    const uploadResponsePromise = page.waitForResponse(
      (response) =>
        response.request().method() === "POST" &&
        apiPath(response.url()) === "/api/v1/documents/parse",
    );
    await page.getByRole("button", { name: "上传并识别" }).click();
    const uploadResponse = await uploadResponsePromise;
    expect(uploadResponse.status()).toBe(202);

    await expect(
      page.getByText("开始时间需要人工确认"),
    ).toBeVisible({ timeout: 60_000 });
    await expect(
      page.getByText("结束时间需要人工确认"),
    ).toBeVisible();
    await page.getByRole("button", { name: "人工修正" }).click();
    await page.getByLabel("开始时间").fill("2026-10-01T07:00:00Z");
    await page.getByLabel("结束时间").fill("2026-10-03T02:00:00Z");
    await page
      .getByLabel(/修正原因/)
      .fill("已与脱敏合成订单核对。");
    const correctionResponsePromise = page.waitForResponse(
      (response) =>
        response.request().method() === "PATCH" &&
        /\/api\/v1\/documents\/[^/]+\/bookings\/[^/]+$/.test(
          apiPath(response.url()),
        ),
    );
    await page.getByRole("button", { name: "保存修正" }).click();
    expect((await correctionResponsePromise).status()).toBe(200);
    await expect(page.getByText("已修正")).toBeVisible();

    const planResponsePromise = page.waitForResponse(
      (response) =>
        response.request().method() === "POST" &&
        apiPath(response.url()) === "/api/v1/plans",
    );
    await page.getByRole("button", { name: "生成旅行方案" }).click();
    const planResponse = await planResponsePromise;
    expect(planResponse.status()).toBe(202);
    const planningTask = (await planResponse.json()) as { taskId: string };
    await expect(page).toHaveURL(
      new RegExp(`/tasks/${planningTask.taskId}$`),
    );

    await page.reload();
    await expect(
      page.getByRole("heading", { name: "方案生成进度", level: 1 }),
    ).toBeVisible();
    expect(planCreateRequests).toBe(1);
    await expect(
      page.getByRole("link", { name: "查看旅行方案" }),
    ).toBeVisible({ timeout: 90_000 });
    await expect(
      page.getByRole("heading", { name: "协作进度" }),
    ).toBeVisible();
    await page.getByRole("link", { name: "查看旅行方案" }).click();

    await expect(
      page.getByRole("heading", { name: /北京.*plan/, level: 1 }),
    ).toBeVisible({ timeout: 30_000 });
    await expect(page.getByText("2 个候选")).toBeVisible();
    await expect(
      page.getByRole("img", { name: "预算分类柱状图" }),
    ).toBeVisible();

    await page.getByRole("button", { name: "并排比较" }).click();
    const comparison = page.getByRole("dialog");
    await expect(
      comparison.getByRole("heading", { name: "方案并排比较" }),
    ).toBeVisible();
    await expect(comparison.getByText("预计总额")).toBeVisible();
    await expect(comparison.getByText("交通耗时")).toBeVisible();
    await comparison.getByRole("button", { name: "关闭" }).click();

    const map = page.getByRole("region", { name: "均衡探索路线" });
    const firstMarker = map.getByRole("button", { name: /查看地点/ }).first();
    await expect(firstMarker).toBeVisible();
    await firstMarker.click();
    await expect(firstMarker).toHaveAttribute("aria-pressed", "true");
    await expect(
      page.getByRole("heading", { name: "每日时间线" }),
    ).toBeVisible();

    await page.getByRole("button", { name: "打开证据抽屉" }).click();
    const evidence = page.getByRole("dialog");
    await expect(
      evidence.getByRole("heading", { name: "证据抽屉" }),
    ).toBeVisible();
    await expect(evidence.locator("article").first()).toBeVisible();
    await evidence.getByRole("button", { name: "关闭" }).click();
    await expect(
      page.getByRole("heading", { name: "冲突中心" }),
    ).toBeVisible();

    await page.getByRole("button", { name: "锁定" }).first().click();
    await page.getByRole("button", { name: "重规划" }).first().click();
    const replan = page.getByRole("dialog");
    await expect(replan.getByText("1", { exact: true })).toBeVisible();
    await replan
      .getByLabel("自然语言修改")
      .fill("保留锁定项目，减少跨区移动并保留午休时间。");
    const replanResponsePromise = page.waitForResponse(
      (response) =>
        response.request().method() === "POST" &&
        /\/api\/v1\/plans\/[^/]+\/replan$/.test(apiPath(response.url())),
    );
    await replan.getByRole("button", { name: "生成新版本" }).click();
    const replanResponse = await replanResponsePromise;
    expect(replanResponse.status()).toBe(202);
    const replanTask = (await replanResponse.json()) as { taskId: string };
    await expect(page).toHaveURL(new RegExp(`/tasks/${replanTask.taskId}$`));
    expect(replanRequests).toBe(1);

    await expect(
      page.getByRole("link", { name: "查看旅行方案" }),
    ).toBeVisible({ timeout: 90_000 });
    await page.getByRole("link", { name: "查看旅行方案" }).click();
    await expect(page.getByText("相对上一版本")).toBeVisible({
      timeout: 30_000,
    });
    const versionSelect = page.locator(
      'section[aria-labelledby="version-switch-title"] select',
    );
    await expect
      .poll(() => versionSelect.locator("option").count())
      .toBeGreaterThan(1);

    const confirmResponsePromise = page.waitForResponse(
      (response) =>
        response.request().method() === "POST" &&
        /\/api\/v1\/plans\/[^/]+\/confirm$/.test(apiPath(response.url())),
    );
    await page.getByRole("button", { name: "确认候选行程" }).click();
    expect((await confirmResponsePromise).status()).toBe(200);
    await expect(
      page.getByRole("button", { name: "行程已确认" }),
    ).toBeVisible();

    await page.reload();
    await expect(
      page.getByRole("button", { name: "行程已确认" }),
    ).toBeVisible();
    expect(planCreateRequests).toBe(1);
    expect(replanRequests).toBe(1);

    await page.goto("/feedback");
    await page.getByRole("button", { name: "路线安排合理" }).click();
    await page
      .getByLabel("补充说明")
      .fill("真实联调方案兼顾了预算与通勤时间。");
    const feedbackResponsePromise = page.waitForResponse(
      (response) =>
        response.request().method() === "POST" &&
        apiPath(response.url()) === "/api/v1/feedback",
    );
    await page.getByRole("button", { name: "提交反馈" }).click();
    expect((await feedbackResponsePromise).status()).toBe(201);
    await expect(
      page.getByText("真实联调方案兼顾了预算与通勤时间。"),
    ).toBeVisible();

    await page.reload();
    await expect(
      page.getByText("真实联调方案兼顾了预算与通勤时间。"),
    ).toBeVisible();
    await expect(page.locator("body")).not.toContainText(planningTask.taskId);
    await expect(page.locator("body")).not.toContainText("MOCK");
    expect(unhandledErrors).toEqual([]);
  });
});
