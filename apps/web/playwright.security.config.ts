import { defineConfig, devices } from "@playwright/test";
import path from "node:path";
import { fileURLToPath } from "node:url";

const webDirectory = path.dirname(fileURLToPath(import.meta.url));
const baseURL = process.env.QA_WEB_BASE_URL ?? "http://127.0.0.1:4188";
const target = new URL(baseURL);
if (target.hostname !== "127.0.0.1" || target.protocol !== "http:") {
  throw new Error("Security browser tests require a loopback origin");
}
const reports = path.resolve(webDirectory, "../../output/test-results", process.env.QA_RUN_ID ?? "stage8-security");

export default defineConfig({
  testDir: "./e2e",
  testMatch: "stage8-security.spec.ts",
  workers: 1,
  retries: 0,
  reporter: [
    ["list"],
    ["junit", { outputFile: path.join(reports, "junit/browser-security.xml") }],
    ["json", { outputFile: path.join(reports, "json/browser-security.json") }],
  ],
  outputDir: path.join(reports, "browser-security"),
  use: { baseURL, trace: "on", screenshot: "on", video: "off" },
  webServer: {
    cwd: webDirectory,
    command: `npm run preview -- --host 127.0.0.1 --port ${target.port} --strictPort`,
    url: baseURL,
    reuseExistingServer: false,
    timeout: 60_000,
  },
  projects: [
    { name: "desktop", use: { ...devices["Desktop Chrome"] } },
    { name: "mobile", use: { ...devices["Pixel 7"] } },
  ],
});
