import exec from "k6/execution";
import { baseUrl, profileName, workloadOptions } from "./lib/config.js";
import {
  createVuState,
  runWeightedIteration,
  verifyMockEnvironment,
} from "./lib/workflows.js";
import { summaryOutputs } from "./lib/summary.js";

const documentFixture = open("../fixtures/documents/low-confidence.png", "b");
let state;

export const options = workloadOptions(profileName);

export function setup() {
  return verifyMockEnvironment(baseUrl);
}

export default function () {
  if (!state) {
    state = createVuState(profileName, __VU, documentFixture);
  }
  runWeightedIteration(
    state,
    baseUrl,
    exec.scenario.iterationInTest,
  );
}

export function handleSummary(data) {
  return summaryOutputs(data, profileName);
}
