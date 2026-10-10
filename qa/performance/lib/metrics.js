import { Counter, Gauge, Rate, Trend } from "k6/metrics";
import exec from "k6/execution";

export const syncErrors = new Rate("sync_errors");
export const writeErrors = new Rate("write_errors");
export const asyncSubmitErrors = new Rate("async_submit_errors");
export const businessErrors = new Rate("business_errors");
export const degradedResponses = new Counter("degraded_responses");
export const dataConsistencyFailures = new Counter("data_consistency_failures");

export const syncDuration = new Trend("sync_duration", true);
export const writeDuration = new Trend("write_duration", true);
export const asyncSubmitDuration = new Trend("async_submit_duration", true);
export const asyncCompletionDuration = new Trend(
  "async_completion_duration",
  true,
);
export const taskPermanentStuck = new Rate("task_permanent_stuck");
export const duplicateBusinessResources = new Counter(
  "duplicate_business_resources",
);

export const sseConnectionSuccess = new Rate("sse_connection_success");
export const sseConnectDuration = new Trend("sse_connect_duration", true);
export const sseDuplicateEvents = new Counter("sse_duplicate_events");
export const sseLostEvents = new Counter("sse_lost_events");
export const sseReconnectSuccess = new Rate("sse_reconnect_success");
export const activeSseConnections = new Gauge("active_sse_connections");

export const idempotencyResponses = new Counter("idempotency_responses");
export const idempotencyUniqueResources = new Counter(
  "idempotency_unique_resources",
);
export const idempotencyResourceFingerprint = new Trend(
  "idempotency_resource_fingerprint",
);
export const optimisticLockConflicts = new Counter("optimistic_lock_conflicts");
export const silentOverwriteFailures = new Counter("silent_overwrite_failures");
export const raceSuccessfulWrites = new Counter("race_successful_writes");
export const raceRejectedWrites = new Counter("race_rejected_writes");

const capacityLatency = [
  [25, new Trend("capacity_latency_vus_001_025", true)],
  [50, new Trend("capacity_latency_vus_026_050", true)],
  [100, new Trend("capacity_latency_vus_051_100", true)],
  [200, new Trend("capacity_latency_vus_101_200", true)],
  [400, new Trend("capacity_latency_vus_201_400", true)],
];

export function recordCapacityLatency(duration, tags = {}) {
  const activeVus = exec.instance.vusActive;
  const selected =
    capacityLatency.find(([maximum]) => activeVus <= maximum) ||
    capacityLatency[capacityLatency.length - 1];
  selected[1].add(duration, tags);
}
