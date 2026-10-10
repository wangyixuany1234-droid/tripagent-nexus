import AxeBuilder from "@axe-core/playwright";
import { expect, test, type Page, type TestInfo } from "@playwright/test";
import {
  installStage11Routes,
  stage11Ids,
} from "./support/stage11-fixtures";

const primaryProject = "chromium-1366x768";

async function preferStableVisualModes(page: Page) {
  await page.emulateMedia({ colorScheme: "dark", reducedMotion: "reduce" });
  await page.addInitScript(() => {
    window.localStorage.setItem(
      "tripagent-ui",
      JSON.stringify({ state: { theme: "dark" }, version: 0 }),
    );
  });
}

async function openMobilePanel(page: Page, name: string) {
  const panels = page.getByRole("group", { name: "结果模块" });
  if (await panels.isVisible()) {
    await panels.getByRole("button", { name }).click();
  }
}

async function assertNoHorizontalOverflow(page: Page, testInfo: TestInfo) {
  const overflow = await page.evaluate(() => {
    const viewportWidth = document.documentElement.clientWidth;
    const rootOverflow = document.documentElement.scrollWidth - viewportWidth;
    const insideHorizontalScroller = (element: Element) => {
      let current = element.parentElement;
      while (current && current !== document.body) {
        const style = window.getComputedStyle(current);
        const allowsHorizontalScroll =
          style.overflowX === "auto" || style.overflowX === "scroll";
        if (allowsHorizontalScroll && current.scrollWidth > current.clientWidth) {
          return true;
        }
        current = current.parentElement;
      }
      return false;
    };
    const elements = [...document.querySelectorAll("#main-content *")]
      .filter((element) => {
        const rect = element.getBoundingClientRect();
        const style = window.getComputedStyle(element);
        return (
          rect.width > 0 &&
          style.position !== "fixed" &&
          !insideHorizontalScroller(element) &&
          (rect.left < -1 || rect.right > viewportWidth + 1)
        );
      })
      .map((element) => {
        const rect = element.getBoundingClientRect();
        const style = window.getComputedStyle(element);
        return {
          tag: element.tagName.toLowerCase(),
          text: element.textContent?.trim().replace(/\s+/g, " ").slice(0, 80) ?? "",
          left: Math.round(rect.left),
          right: Math.round(rect.right),
          width: Math.round(rect.width),
          position: style.position,
        };
      })
      .slice(0, 8);
    return { viewportWidth, rootOverflow, elements };
  });

  await testInfo.attach("horizontal-overflow", {
    body: JSON.stringify(overflow, null, 2),
    contentType: "application/json",
  });
  expect(overflow.rootOverflow).toBeLessThanOrEqual(1);
  expect(overflow.elements).toEqual([]);
}

async function assertBudgetChartDoesNotCoverText(page: Page) {
  await openMobilePanel(page, "预算");
  const chart = page.getByRole("img", { name: "预算分类柱状图" });
  await expect(chart).toBeVisible();
  const layout = await chart.evaluate((node) => {
    const chartRect = node.getBoundingClientRect();
    const summary = node.nextElementSibling?.getBoundingClientRect();
    return {
      chartBottom: Math.round(chartRect.bottom),
      summaryTop: Math.round(summary?.top ?? chartRect.bottom),
    };
  });
  expect(layout.chartBottom).toBeLessThanOrEqual(layout.summaryTop + 1);
}

async function assertDialogFocusIsContained(page: Page) {
  const dialog = page.getByRole("dialog");
  await expect(dialog).toBeVisible();
  for (let index = 0; index < 8; index += 1) {
    await page.keyboard.press("Tab");
    const focusWithinDialog = await page.evaluate(() => {
      const currentDialog = document.querySelector('[role="dialog"]');
      return Boolean(currentDialog?.contains(document.activeElement));
    });
    expect(focusWithinDialog).toBe(true);
  }
}

test.beforeEach(async ({ page }) => {
  await preferStableVisualModes(page);
});

test("大数据结果页在浏览器和视口矩阵中保持可访问、可读且无横向溢出", async ({
  page,
}, testInfo) => {
  test.setTimeout(120_000);
  await installStage11Routes(page);
  await page.goto(`/plans/${stage11Ids.currentPlan}`);

  await expect(
    page.getByRole("heading", {
      name: "阶段十一北京十四日大数据稳定性方案 v20",
      level: 1,
    }),
  ).toBeVisible();
  await expect(page.locator("html")).toHaveClass(/dark/);
  await expect
    .poll(() =>
      page.evaluate(() =>
        window.matchMedia("(prefers-reduced-motion: reduce)").matches,
      ),
    )
    .toBe(true);

  await openMobilePanel(page, "时间线");
  await expect(page.getByRole("heading", { name: "每日时间线" })).toBeVisible();
  await expect(page.locator('[id^="timeline-item-"]')).toHaveCount(12);
  await page.getByRole("button", { name: /显示更多行程/ }).click();
  await expect(page.locator('[id^="timeline-item-"]')).toHaveCount(24);

  await openMobilePanel(page, "预算");
  await assertBudgetChartDoesNotCoverText(page);

  await openMobilePanel(page, "冲突");
  await expect(page.getByText("50 个待处理")).toBeVisible();
  await expect(page.getByRole("button", { name: /显示更多冲突/ })).toBeVisible();
  await expect(page.getByRole("button", { name: "打开证据抽屉" })).toBeVisible();
  await page.getByRole("button", { name: "打开证据抽屉" }).click();
  await expect(
    page.getByRole("dialog").getByRole("heading", {
      name: "证据抽屉",
      exact: true,
    }),
  ).toBeVisible();
  await expect(page.getByRole("button", { name: /显示更多证据/ })).toBeVisible();
  await assertDialogFocusIsContained(page);
  await page.keyboard.press("Escape");
  await expect(page.getByRole("dialog")).toBeHidden();

  await expect(page.locator("body")).not.toContainText(/018f0e31|STAGE11_RULE|timedout/i);
  await assertNoHorizontalOverflow(page, testInfo);

  const result = await new AxeBuilder({ page })
    .withTags(["wcag2a", "wcag2aa", "wcag21a", "wcag21aa"])
    .include("main")
    .exclude(".maplibregl-map")
    .exclude("canvas")
    .analyze();
  await testInfo.attach("axe-results", {
    body: JSON.stringify(result, null, 2),
    contentType: "application/json",
  });
  expect(
    result.violations.filter((violation) =>
      ["serious", "critical"].includes(violation.impact ?? ""),
    ),
  ).toEqual([]);
});

test("地图失败和请求超时不会阻断其他结果模块，错误为可读用户文案", async ({
  page,
}, testInfo) => {
  test.skip(testInfo.project.name !== primaryProject, "网络异常场景在主浏览器执行");
  await installStage11Routes(page, {
    failInitialPlanRequests: 2,
    mapFailure: true,
  });
  await page.goto(`/plans/${stage11Ids.currentPlan}`);

  const alert = page.getByRole("alert");
  await expect(alert).toContainText("无法加载旅行方案");
  await expect(alert).not.toContainText(/timedout|ECONN|HTTP|status|trace/i);

  await page.getByRole("button", { name: "重试" }).click();
  await expect(
    page.getByRole("heading", {
      name: "阶段十一北京十四日大数据稳定性方案 v20",
      level: 1,
    }),
  ).toBeVisible();
  await expect(page.getByRole("heading", { name: "每日时间线" })).toBeVisible();
  await openMobilePanel(page, "冲突");
  await expect(page.getByRole("heading", { name: "冲突中心" })).toBeVisible();
  await assertNoHorizontalOverflow(page, testInfo);
});

test("慢速 3G 和高延迟下展示真实加载态并恢复为后端数据", async ({
  page,
}, testInfo) => {
  test.skip(testInfo.project.name !== primaryProject, "弱网延迟场景在主浏览器执行");
  test.setTimeout(60_000);
  let releaseFirstPlan!: () => void;
  const holdFirstPlanRequest = new Promise<void>((resolve) => {
    releaseFirstPlan = resolve;
  });
  await installStage11Routes(page, { holdFirstPlanRequest });

  await page.goto(`/plans/${stage11Ids.currentPlan}`, { waitUntil: "domcontentloaded" });
  await expect(page.locator('[aria-hidden="true"].animate-pulse').first()).toBeVisible();
  releaseFirstPlan();
  await expect(
    page.getByRole("heading", {
      name: "阶段十一北京十四日大数据稳定性方案 v20",
      level: 1,
    }),
  ).toBeVisible();
  await assertNoHorizontalOverflow(page, testInfo);
});

test("SSE 中断后进入轮询并最终展示后端终态", async ({ page }, testInfo) => {
  test.skip(testInfo.project.name !== primaryProject, "SSE 断流场景在主浏览器执行");
  await installStage11Routes(page);
  await page.goto(`/tasks/${stage11Ids.task}`);

  await expect(page.getByRole("heading", { name: "方案生成进度", level: 1 })).toBeVisible();
  await expect(page.getByRole("heading", { name: "旅行方案生成完成" })).toBeVisible();
  await expect(page.getByText("100%").first()).toBeVisible();
  await expect(page.getByRole("link", { name: "查看旅行方案" })).toBeVisible();
  await expect(page.locator("body")).not.toContainText(stage11Ids.task);
});

test("重复提交重规划只产生一次后端写请求", async ({ page }, testInfo) => {
  test.skip(testInfo.project.name !== primaryProject, "重复请求场景在主浏览器执行");
  let releaseReplan!: () => void;
  const holdReplan = new Promise<void>((resolve) => {
    releaseReplan = resolve;
  });
  let replanRequests = 0;
  await installStage11Routes(page, {
    holdReplan,
    onReplanRequest: () => {
      replanRequests += 1;
    },
  });
  await page.goto(`/plans/${stage11Ids.currentPlan}`);
  await openMobilePanel(page, "冲突");
  await page.getByRole("button", { name: "带入重规划" }).first().click();
  const dialog = page.getByRole("dialog");
  await expect(dialog.getByRole("heading", { name: "生成新版本" })).toBeVisible();

  await dialog.getByRole("button", { name: "生成新版本" }).dblclick();
  await expect(dialog.getByRole("button", { name: "生成新版本" })).toBeDisabled();
  expect(replanRequests).toBe(1);
  releaseReplan();
  await expect(page).toHaveURL(new RegExp(`/tasks/${stage11Ids.replanTask}$`));
});

test("反复挂载卸载地图、图表和抽屉后没有残留 DOM 实例或明显堆增长", async ({
  page,
}, testInfo) => {
  test.skip(testInfo.project.name !== primaryProject, "稳定性采样在主浏览器执行");
  await installStage11Routes(page);
  const heapSamples: number[] = [];

  for (let index = 0; index < 5; index += 1) {
    await page.goto(`/plans/${stage11Ids.currentPlan}`);
    await expect(
      page.getByRole("heading", {
        name: "阶段十一北京十四日大数据稳定性方案 v20",
        level: 1,
      }),
    ).toBeVisible();
    await page.getByRole("combobox", { name: "版本历史" }).selectOption({
      label: "版本 19",
    });
    await expect(
      page.getByRole("heading", {
        name: "阶段十一北京十四日大数据稳定性方案 v19",
        level: 1,
      }),
    ).toBeVisible();
    await page.getByRole("button", { name: "打开证据抽屉" }).click();
    await expect(page.getByRole("dialog")).toBeVisible();
    await page.keyboard.press("Escape");
    await expect(page.getByRole("dialog")).toBeHidden();
    await page.goto("/system-status");
    await expect(page.getByRole("heading", { name: "系统状态", level: 1 })).toBeVisible();
    const sample = await page.evaluate(() => {
      const memory = (
        performance as Performance & {
          memory?: { usedJSHeapSize: number };
        }
      ).memory;
      return {
        heap: memory?.usedJSHeapSize ?? 0,
        maps: document.querySelectorAll(".maplibregl-map").length,
        canvases: document.querySelectorAll("canvas").length,
        dialogs: document.querySelectorAll('[role="dialog"]').length,
      };
    });
    expect(sample.maps).toBe(0);
    expect(sample.canvases).toBe(0);
    expect(sample.dialogs).toBe(0);
    if (sample.heap > 0) {
      heapSamples.push(sample.heap);
    }
  }

  await testInfo.attach("heap-samples", {
    body: JSON.stringify(heapSamples, null, 2),
    contentType: "application/json",
  });
  if (heapSamples.length > 1) {
    const first = heapSamples[0];
    const last = heapSamples.at(-1) ?? first;
    expect(last - first).toBeLessThan(25 * 1024 * 1024);
  }
});
