import { defineConfig } from "@playwright/test";
import security from "./playwright.security.config";

export default defineConfig({
  ...security,
  testMatch: ["stage8-security.spec.ts", "stage9-security.spec.ts"],
});
