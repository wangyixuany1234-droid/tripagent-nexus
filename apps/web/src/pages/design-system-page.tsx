import { useState } from "react";
import { RotateCcw, Shapes } from "lucide-react";
import { TaskStatus } from "@api-generated/models/TaskStatus";
import { NotificationPreviewForm } from "@/components/app/notification-preview-form";
import { PageHeader } from "@/components/app/page-header";
import { EmptyState, ErrorState } from "@/components/states/state-panel";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { AgentProgress } from "@/components/travel/agent-progress";
import { BudgetCard } from "@/components/travel/budget-card";
import { ConflictBadge } from "@/components/travel/conflict-badge";
import { EvidenceBadge } from "@/components/travel/evidence-badge";
import { MapContainer } from "@/components/travel/map-container";
import { StatusBadge } from "@/components/travel/status-badge";
import { Stepper } from "@/components/travel/stepper";
import { Timeline } from "@/components/travel/timeline";
import {
  previewAgents,
  previewBudget,
  previewConflicts,
  previewDay,
  previewEvidence,
  previewPlaces,
} from "./preview-data";

function PreviewSection({
  title,
  children,
}: {
  title: string;
  children: React.ReactNode;
}) {
  return (
    <section className="border-t border-border py-7 first:border-t-0 first:pt-0">
      <div className="mb-5 flex items-center justify-between gap-3">
        <h2 className="text-base font-bold text-navy-strong">{title}</h2>
        <span className="rounded-full bg-surface-subtle px-2 py-1 text-[10px] font-bold text-muted">
          组件样例
        </span>
      </div>
      {children}
    </section>
  );
}

export function DesignSystemPage() {
  const [statePreview, setStatePreview] = useState<"empty" | "error">("empty");

  return (
    <>
      <PageHeader
        eyebrow="界面样式"
        title="界面预览"
        description="查看常用界面元素及其主要状态。"
        breadcrumbs={[{ label: "更多" }, { label: "界面预览" }]}
      />

      <PreviewSection title="步骤与状态">
        <Stepper
          items={[
            { id: "requirements", label: "需求理解", state: "complete" },
            { id: "research", label: "证据检索", state: "complete" },
            { id: "agents", label: "智能协作", state: "current" },
            { id: "review", label: "规则审校", state: "upcoming" },
          ]}
        />
        <div className="mt-6 flex flex-wrap gap-2">
          <StatusBadge status={TaskStatus.Queued} />
          <StatusBadge status={TaskStatus.Running} />
          <StatusBadge status={TaskStatus.WaitingConfirmation} />
          <StatusBadge status={TaskStatus.Succeeded} />
          <StatusBadge status={TaskStatus.PartialSuccess} />
          <StatusBadge status={TaskStatus.Failed} />
        </div>
      </PreviewSection>

      <PreviewSection title="证据与冲突">
        <div className="flex flex-wrap gap-2">
          {previewEvidence.map((evidence) => (
            <EvidenceBadge key={evidence.evidenceId} evidence={evidence} />
          ))}
          {previewConflicts.map((conflict) => (
            <ConflictBadge key={conflict.conflictId} conflict={conflict} />
          ))}
        </div>
      </PreviewSection>

      <PreviewSection title="规划状态">
        <div className="grid min-w-0 gap-5 lg:grid-cols-2">
          <BudgetCard budget={previewBudget} />
          <AgentProgress agents={previewAgents} />
        </div>
      </PreviewSection>

      <PreviewSection title="地图与时间线">
        <div className="grid min-w-0 gap-6 xl:grid-cols-[minmax(0,1.15fr)_minmax(340px,0.85fr)]">
          <MapContainer places={previewPlaces} />
          <div className="min-w-0 rounded-md border border-border bg-surface p-4">
            <Timeline day={previewDay} />
          </div>
        </div>
      </PreviewSection>

      <PreviewSection title="空状态与不可用状态">
        <div
          className="mb-4 inline-flex rounded-md border border-border bg-surface p-1"
          role="group"
          aria-label="状态组件预览"
        >
          <button
            type="button"
            aria-pressed={statePreview === "empty"}
            onClick={() => setStatePreview("empty")}
            className={`h-8 rounded px-3 text-xs font-semibold ${
              statePreview === "empty"
                ? "bg-navy-strong text-white dark:text-background"
                : "text-muted"
            }`}
          >
            空状态
          </button>
          <button
            type="button"
            aria-pressed={statePreview === "error"}
            onClick={() => setStatePreview("error")}
            className={`h-8 rounded px-3 text-xs font-semibold ${
              statePreview === "error"
                ? "bg-navy-strong text-white dark:text-background"
                : "text-muted"
            }`}
          >
            不可用状态
          </button>
        </div>
        <div
          id="state-preview-panel"
          className="min-w-0"
          aria-live="polite"
        >
          {statePreview === "empty" ? (
            <EmptyState
              icon={Shapes}
              title="暂无可展示内容"
              description="当前筛选条件下暂时没有内容。"
            />
          ) : (
            <ErrorState
              title="内容暂时无法显示"
              description="可以稍后重试，当前页面中的其他内容不受影响。"
            />
          )}
        </div>
      </PreviewSection>

      <PreviewSection title="加载状态">
        <div className="mt-5 grid gap-3 sm:grid-cols-3">
          <Skeleton className="h-20" />
          <Skeleton className="h-20" />
          <Skeleton className="h-20" />
        </div>
      </PreviewSection>

      <PreviewSection title="全局通知">
        <div className="max-w-xl">
          <NotificationPreviewForm />
          <Button
            variant="ghost"
            size="sm"
            className="mt-2"
            onClick={() => window.location.reload()}
          >
            <RotateCcw />
            重置预览
          </Button>
        </div>
      </PreviewSection>
    </>
  );
}
