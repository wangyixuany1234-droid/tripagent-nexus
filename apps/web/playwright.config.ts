import { defineConfig, devices } from "@playwright/test";
import path from "node:path";

const reportRoot = path.resolve(
  process.env.QA_RESULTS_ROOT ?? "../../output/test-results",
  process.env.QA_RUN_ID ?? "latest",
);
const captureAll = process.env.QA_E2E_CAPTURE_ALL === "true";
const realIntegration = process.env.QA_REAL_E2E === "true";

export default defineConfig({
  testDir: "./e2e",
  fullyParallel: !realIntegration,
  workers: realIntegration ? 1 : undefined,
  forbidOnly: Boolean(process.env.CI),
  retries: process.env.CI ? 2 : 0,
  reporter: [
    ["list"],
    [
      "html",
      {
        outputFolder: `${reportRoot}/playwright`,
        open: "never",
      },
    ],
    ["junit", { outputFile: `${reportRoot}/junit/e2e/playwright.xml` }],
    ["json", { outputFile: `${reportRoot}/json/playwright.json` }],
  ],
  outputDir: `${reportRoot}/playwright-artifacts`,
  use: {
    baseURL: process.env.QA_WEB_BASE_URL ?? "http://127.0.0.1:4173",
    screenshot: captureAll ? "on" : "only-on-failure",
    trace: captureAll ? "on" : "retain-on-failure",
    video: captureAll ? "on" : "retain-on-failure",
  },
  webServer: realIntegration
    ? undefined
    : {
        command: "npm run preview -- --host 127.0.0.1 --port 4173",
        url: "http://127.0.0.1:4173",
        reuseExistingServer: true,
        timeout: 120_000,
      },
  projects: [
    {
      name: "desktop-chromium",
      use: { ...devices["Desktop Chrome"], viewport: { width: 1440, height: 1000 } },
    },
    ...(
      realIntegration
        ? []
        : [
            {
              name: "mobile-chromium",
              use: { ...devices["Pixel 7"] },
            },
          ]
    ),
  ],
});
