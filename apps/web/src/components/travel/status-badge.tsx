import type { AgentExecutionStatus } from "@api-generated/models/AgentExecutionStatus";
import type { AvailabilityStatus } from "@api-generated/models/AvailabilityStatus";
import type { BookingReviewStatus } from "@api-generated/models/BookingReviewStatus";
import type { DocumentStatus } from "@api-generated/models/DocumentStatus";
import type { EvaluationRunStatus } from "@api-generated/models/EvaluationRunStatus";
import type { GuardedActionStatus } from "@api-generated/models/GuardedActionStatus";
import type { PlanStatus } from "@api-generated/models/PlanStatus";
import type { PreferenceStatus } from "@api-generated/models/PreferenceStatus";
import type { TaskStatus } from "@api-generated/models/TaskStatus";
import type { TripStatus } from "@api-generated/models/TripStatus";
import { cn } from "@/lib/utils";

type StatusValue =
  | AgentExecutionStatus
  | BookingReviewStatus
  | DocumentStatus
  | EvaluationRunStatus
  | GuardedActionStatus
  | PlanStatus
  | PreferenceStatus
  | TaskStatus
  | TripStatus
  | AvailabilityStatus;

const labels: Record<string, string> = {
  CREATED: "已创建",
  QUEUED: "排队中",
  RUNNING: "处理中",
  WAITING_CONFIRMATION: "待确认",
  SUCCEEDED: "已完成",
  PARTIAL_SUCCESS: "部分完成",
  FAILED: "未完成",
  CANCELLED: "已取消",
  DRAFT: "草稿",
  READY: "就绪",
  ARCHIVED: "已归档",
  GENERATING: "生成中",
  PARTIAL: "部分可用",
  CONFIRMED: "已确认",
  PARSING: "识别中",
  NEEDS_REVIEW: "待复核",
  PARSED: "已识别",
  PENDING: "待处理",
  APPROVED: "已批准",
  REJECTED: "已拒绝",
  EXPIRED: "已过期",
  AUTO_ACCEPTED: "自动通过",
  CORRECTED: "已修正",
  DEGRADED: "部分完成",
  TIMEOUT: "暂未完成",
  SKIPPED: "未执行",
  ACTIVE: "使用中",
  AVAILABLE: "可用",
  UNAVAILABLE: "暂不可用",
  DISABLED: "未启用",
};

function statusTone(status: string) {
  if (
    ["SUCCEEDED", "READY", "CONFIRMED", "PARSED", "APPROVED", "AUTO_ACCEPTED", "CORRECTED", "ACTIVE", "AVAILABLE"].includes(
      status,
    )
  ) {
    return "bg-success-soft text-success";
  }
  if (["FAILED", "REJECTED", "TIMEOUT", "UNAVAILABLE"].includes(status)) {
    return "bg-danger-soft text-danger";
  }
  if (
    ["WAITING_CONFIRMATION", "NEEDS_REVIEW", "PARTIAL_SUCCESS", "PARTIAL", "DEGRADED"].includes(
      status,
    )
  ) {
    return "bg-warning-soft text-warning";
  }
  if (["RUNNING", "GENERATING", "PARSING", "QUEUED"].includes(status)) {
    return "bg-sky-soft text-sky";
  }
  return "bg-surface-subtle text-muted";
}

export function StatusBadge({
  status,
  showDot = true,
  className,
}: {
  status: StatusValue;
  showDot?: boolean;
  className?: string;
}) {
  return (
    <span
      className={cn(
        "inline-flex h-6 items-center gap-1.5 rounded-full px-2 text-[11px] font-bold whitespace-nowrap",
        statusTone(status),
        className,
      )}
    >
      {showDot && <span className="size-1.5 rounded-full bg-current" aria-hidden="true" />}
      {labels[status] ?? "状态更新"}
    </span>
  );
}
