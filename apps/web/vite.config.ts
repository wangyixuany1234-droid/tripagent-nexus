import fs from "node:fs";
import path from "node:path";
import tailwindcss from "@tailwindcss/vite";
import react from "@vitejs/plugin-react";
import { defineConfig } from "vitest/config";

const thresholds = JSON.parse(
  fs.readFileSync(
    path.resolve(__dirname, "../../qa/config/thresholds.json"),
    "utf-8",
  ),
) as {
  coverage: {
    frontendBaseline: {
      lines: number;
      functions: number;
      branches: number;
      statements: number;
    };
  };
};
const reportRoot = path.resolve(
  process.env.QA_RESULTS_ROOT ??
    path.resolve(__dirname, "../../output/test-results"),
  process.env.QA_RUN_ID ?? "latest",
);

export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: {
    alias: {
      "@": path.resolve(__dirname, "src"),
      "@api-generated": path.resolve(__dirname, "src/api/generated/src"),
    },
  },
  server: {
    host: "127.0.0.1",
    port: 5173,
  },
  preview: {
    host: "127.0.0.1",
    port: 4173,
  },
  test: {
    environment: "jsdom",
    setupFiles: ["./src/test/setup.ts"],
    css: true,
    globals: true,
    include: ["src/**/*.test.{ts,tsx}"],
    sequence: {
      seed: Number(process.env.QA_RANDOM_SEED ?? "20260908"),
      shuffle: false,
    },
    reporters: ["default", "junit"],
    outputFile: {
      junit: path.resolve(reportRoot, "junit/frontend/vitest.xml"),
    },
    coverage: {
      provider: "v8",
      reporter: ["text", "json-summary", "lcov", "html"],
      reportsDirectory: path.resolve(reportRoot, "coverage/frontend"),
      include: ["src/**/*.{ts,tsx}"],
      exclude: [
        "src/api/generated/**",
        "src/**/*.d.ts",
        "src/main.tsx",
        "src/test/**",
      ],
      thresholds: thresholds.coverage.frontendBaseline,
    },
  },
});
