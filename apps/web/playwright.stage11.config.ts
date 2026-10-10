import { defineConfig, devices } from "@playwright/test";
import path from "node:path";

const reportRoot = path.resolve(
  process.env.QA_RESULTS_ROOT ?? "../../output/test-results",
  process.env.QA_RUN_ID ?? "latest",
);
const port = Number(process.env.QA_STAGE11_PORT ?? "4174");
const baseURL = process.env.QA_WEB_BASE_URL ?? `http://127.0.0.1:${port}`;
const captureAll = process.env.QA_E2E_CAPTURE_ALL === "true";

const viewports = [
  { name: "375x667", width: 375, height: 667 },
  { name: "390x844", width: 390, height: 844 },
  { name: "768x1024", width: 768, height: 1024 },
  { name: "1366x768", width: 1366, height: 768 },
  { name: "1440x900", width: 1440, height: 900 },
  { name: "1920x1080", width: 1920, height: 1080 },
] as const;

const browserNames = ["chromium", "firefox", "webkit"] as const;

export default defineConfig({
  testDir: "./e2e",
  testMatch: /stage11-frontend\.spec\.ts/,
  fullyParallel: false,
  workers: Number(process.env.QA_STAGE11_WORKERS ?? "2"),
  forbidOnly: Boolean(process.env.CI),
  retries: process.env.CI ? 1 : 0,
  reporter: [
    ["list"],
    [
      "html",
      {
        outputFolder: `${reportRoot}/playwright-stage11`,
        open: "never",
      },
    ],
    ["junit", { outputFile: `${reportRoot}/junit/e2e/stage11-playwright.xml` }],
    ["json", { outputFile: `${reportRoot}/json/stage11-playwright.json` }],
  ],
  outputDir: `${reportRoot}/playwright-stage11-artifacts`,
  use: {
    baseURL,
    screenshot: captureAll ? "on" : "only-on-failure",
    trace: captureAll ? "on" : "retain-on-failure",
    video: captureAll ? "on" : "retain-on-failure",
    locale: "zh-CN",
    timezoneId: "Asia/Shanghai",
  },
  webServer: process.env.QA_REAL_E2E === "true"
    ? undefined
    : {
        command: `npm run preview -- --host 127.0.0.1 --port ${port}`,
        url: baseURL,
        reuseExistingServer: true,
        timeout: 120_000,
      },
  projects: browserNames.flatMap((browserName) =>
    viewports.map((viewport) => ({
      name: `${browserName}-${viewport.name}`,
      use: {
        ...(browserName === "chromium"
          ? devices["Desktop Chrome"]
          : browserName === "firefox"
            ? devices["Desktop Firefox"]
            : devices["Desktop Safari"]),
        browserName,
        viewport: { width: viewport.width, height: viewport.height },
        isMobile: viewport.width < 768,
        hasTouch: viewport.width < 768,
      },
    })),
  ),
});
