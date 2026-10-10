import { useMemo } from "react";
import { useQuery } from "@tanstack/react-query";
import type { AgentExecution } from "@api-generated/models/AgentExecution";
import type { TaskEvent } from "@api-generated/models/TaskEvent";
import {
  Activity,
  Bot,
  Check,
  Circle,
  CircleDot,
  Database,
  RefreshCcw,
  Search,
  ShieldCheck,
  Sparkles,
  Wrench,
} from "lucide-react";
import { Link, useParams } from "react-router-dom";
import { apiAdapter } from "@/api/client";
import { PageHeader } from "@/components/app/page-header";
import { EmptyState, ErrorState } from "@/components/states/state-panel";
import { Button } from "@/components/ui/button";
import { Progress } from "@/components/ui/progress";
import { Skeleton } from "@/components/ui/skeleton";
import { StatusBadge } from "@/components/travel/status-badge";
import {
  agentExecutionsQueryKey,
  useTaskMonitor,
} from "@/hooks/use-task-monitor";
import { cn } from "@/lib/utils";
import { useTripFlowStore } from "@/stores/trip-flow-store";

const agentLabels: Record<string, string> = {
  RequirementAgent: "需求理解",
  ResearchAgent: "旅行研究",
  TransportAgent: "交通规划",
  AccommodationAgent: "住宿规划",
  RecommendationAgent: "景点与餐饮",
  BudgetAgent: "预算核算",
  ReviewAgent: "行程审校",
  CoordinatorAgent: "方案编排",
};

const agentSummaries: Record<string, string> = {
  RequirementAgent: "整理旅行需求与同行人约束",
  ResearchAgent: "汇总目的地信息与参考依据",
  TransportAgent: "安排城际与市内交通",
  AccommodationAgent: "核对住宿与入住安排",
  RecommendationAgent: "组织景点、餐饮与停留顺序",
  BudgetAgent: "汇总费用并检查预算",
  ReviewAgent: "检查时间、地点与行程冲突",
  CoordinatorAgent: "汇总并生成候选方案",
};

const pipeline = [
  { stage: "REQUIREMENTS_LOADED", label: "资料准备", icon: Database },
  { stage: "RESEARCH_READY", label: "信息汇总", icon: Search },
  { stage: "AGENTS_RUNNING", label: "智能协作", icon: Wrench },
  { stage: "CANDIDATES_ASSEMBLED", label: "候选方案", icon: Sparkles },
  { stage: "RULE_VALIDATION", label: "规则审校", icon: ShieldCheck },
  { stage: "LOCAL_REPLAN", label: "局部重规划", icon: RefreshCcw },
  { stage: "COMPLETED", label: "生成完成", icon: Check },
] as const;

const stageDescriptions: Record<string, string> = {
  CREATED: "任务已创建",
  QUEUED: "正在等待开始",
  REQUIREMENTS_LOADED: "旅行需求与已确认订单已载入",
  RESEARCH_READY: "目的地信息与个人偏好已汇总",
  AGENTS_RUNNING: "各项旅行安排正在协同生成",
  CANDIDATES_ASSEMBLED: "候选行程已经汇总",
  RULE_VALIDATION: "正在核对预算、时间与地点安排",
  LOCAL_REPLAN: "正在调整可直接处理的冲突",
  COMPLETED: "旅行方案生成完成",
  PARTIAL_SUCCESS: "方案已生成，部分实时信息暂不可用",
  FAILED: "本次方案未能完成",
  CANCELLED: "任务已取消",
};

function formatDuration(milliseconds?: number) {
  if (milliseconds == null) {
    return "等待执行";
  }
  if (milliseconds < 1000) {
    return "不到 1 秒";
  }
  return `${(milliseconds / 1000).toFixed(1)} 秒`;
}

function friendlyWarning(code: string, message: string) {
  if (code.includes("MOCK") || message.toLowerCase().includes("mock")) {
    return "部分内容为演示数据，请勿直接用于实际预订";
  }
  if (code.includes("UNAVAILABLE") || message.toLowerCase().includes("live result")) {
    return "部分实时信息暂不可用，方案已保留可确认内容";
  }
  if (code.includes("TIMEOUT")) {
    return "部分实时信息获取较慢";
  }
  return "该步骤有信息需要稍后确认";
}

function executionSummary(execution: AgentExecution) {
  const summary = agentSummaries[execution.agentName] ?? "处理当前旅行安排";
  if (execution.status === "QUEUED") {
    return "等待开始";
  }
  if (execution.status === "RUNNING") {
    return `${summary}，正在进行`;
  }
  if (execution.status === "FAILED") {
    return "该步骤暂未完成";
  }
  if (execution.status === "SKIPPED") {
    return "当前方案无需执行此步骤";
  }
  return `${summary}，已完成`;
}

function AgentExecutionList({ executions }: { executions: AgentExecution[] }) {
  return (
    <div className="divide-y divide-border border-y border-border">
      {executions.map((execution) => (
        <article
          key={execution.agentExecutionId}
          className="grid min-w-0 gap-3 py-4 sm:grid-cols-[180px_minmax(0,1fr)_auto] sm:items-center"
        >
          <div className="flex min-w-0 items-center gap-3">
            <span
              className={cn(
                "flex size-8 shrink-0 items-center justify-center rounded-full border border-border bg-surface text-muted",
                execution.status === "RUNNING" && "border-sky bg-sky-soft text-sky",
                execution.status === "SUCCEEDED" && "border-success/30 text-success",
              )}
            >
              <Bot className={cn("size-3.5", execution.status === "RUNNING" && "animate-pulse")} />
            </span>
            <p className="truncate text-sm font-bold">
              {agentLabels[execution.agentName] ?? "旅行安排"}
            </p>
          </div>
          <div className="min-w-0 pl-11 sm:pl-0">
            <p className="truncate text-sm text-foreground">
              {executionSummary(execution)}
            </p>
            <p className="mt-1 text-xs text-muted">
              {formatDuration(execution.durationMs)}
              {execution.evidenceCount > 0 && ` · ${execution.evidenceCount} 条证据`}
            </p>
            {execution.warnings.length > 0 && (
              <p className="mt-1 truncate text-xs text-warning">
                {execution.warnings
                  .map((warning) => friendlyWarning(warning.code, warning.message))
                  .join("；")}
              </p>
            )}
          </div>
          <div className="pl-11 sm:pl-0">
            <StatusBadge status={execution.status} />
          </div>
        </article>
      ))}
    </div>
  );
}

function Pipeline({
  currentStage,
  taskStatus,
  events,
}: {
  currentStage: string;
  taskStatus: string;
  events: TaskEvent[];
}) {
  const observed = new Set(events.map((event) => event.stage));
  observed.add(currentStage);
  const terminal = ["SUCCEEDED", "PARTIAL_SUCCESS", "FAILED", "CANCELLED"].includes(
    taskStatus,
  );

  return (
    <ol className="grid gap-2 md:grid-cols-4 xl:grid-cols-7">
      {pipeline.map((item) => {
        const seen =
          observed.has(item.stage) ||
          (item.stage === "COMPLETED" && terminal && taskStatus !== "FAILED");
        const current = item.stage === currentStage && !terminal;
        const skipped = terminal && !seen;
        const complete = seen && !current;
        return (
          <li
            key={item.stage}
            className={cn(
              "min-h-24 border-t-2 px-1 pt-3",
              current && "border-sky",
              complete && "border-success",
              !current && !complete && "border-border",
            )}
          >
            <div className="flex items-center gap-2">
              {complete ? (
                <Check className="size-4 text-success" />
              ) : current ? (
                <CircleDot className="size-4 animate-pulse text-sky" />
              ) : (
                <Circle className="size-4 text-border" />
              )}
              <item.icon className="size-4 text-muted" />
            </div>
            <p className="mt-2 text-xs font-bold">{item.label}</p>
            <p className="mt-1 text-[11px] text-muted">
              {skipped ? "无需执行" : current ? "进行中" : complete ? "已完成" : "等待"}
            </p>
          </li>
        );
      })}
    </ol>
  );
}

export function TaskConsolePage() {
  const params = useParams<{ taskId?: string }>();
  const planningTasks = useTripFlowStore((state) => state.planningTasks);
  const rememberPlan = useTripFlowStore((state) => state.rememberPlan);
  const taskId = params.taskId ?? planningTasks[0]?.taskId;
  const storedTask = planningTasks.find((item) => item.taskId === taskId);
  const monitor = useTaskMonitor(taskId);
  const agentsQuery = useQuery({
    queryKey: agentExecutionsQueryKey(taskId ?? ""),
    queryFn: ({ signal }) => apiAdapter.tasks.listAgents(taskId!, signal),
    enabled: Boolean(taskId && monitor.data?.type !== "DOCUMENT_PARSE"),
    staleTime: 500,
    refetchInterval: monitor.isTerminal ? false : 1000,
  });

  const latestMessages = useMemo(
    () => [...monitor.events].reverse().slice(0, 6),
    [monitor.events],
  );

  if (!taskId) {
    return (
      <>
        <PageHeader title="方案生成进度" breadcrumbs={[{ label: "规划进度" }]} />
        <EmptyState
          icon={Bot}
          title="暂无规划任务"
          description="完成旅行需求后，可从订单资料中心生成旅行方案。"
        />
      </>
    );
  }

  if (monitor.isLoading) {
    return <Skeleton className="h-[520px] w-full" />;
  }

  if (monitor.isError || !monitor.data) {
    return (
      <ErrorState
        title="暂时无法查看进度"
        description="当前内容可能已更新，请稍后重试。"
        onAction={() => void monitor.refetch()}
      />
    );
  }

  const task = monitor.data;

  return (
    <>
      <PageHeader
        eyebrow="规划进度"
        title="方案生成进度"
        description={stageDescriptions[task.stage] || "正在整理旅行方案。"}
        breadcrumbs={[
          { label: "规划进度", to: "/tasks" },
          { label: "当前任务" },
        ]}
        actions={<StatusBadge status={task.status} />}
      />

      <section className="rounded-md bg-navy-strong p-5 text-white dark:bg-surface">
        <div className="flex flex-wrap items-end justify-between gap-4">
          <div>
            <p className="text-xs text-white/65">当前阶段</p>
            <h2 className="mt-1 text-xl font-bold">
              {stageDescriptions[task.stage] || "处理中"}
            </h2>
          </div>
          <span className="text-3xl font-bold">{task.progress}%</span>
        </div>
        <Progress
          value={task.progress}
          label="方案生成总进度"
          className="mt-4 bg-white/15 [&>div]:bg-coral"
        />
      </section>

      <section className="mt-7" aria-labelledby="pipeline-title">
        <div className="mb-4 flex items-center gap-2">
          <Activity className="size-4 text-sky" />
          <h2 id="pipeline-title" className="text-base font-bold text-navy-strong">
            执行阶段
          </h2>
        </div>
        <Pipeline currentStage={task.stage} taskStatus={task.status} events={monitor.events} />
      </section>

      <section className="mt-8" aria-labelledby="agents-title">
        <div className="mb-4 flex items-center justify-between gap-3">
          <h2 id="agents-title" className="text-base font-bold text-navy-strong">
            协作进度
          </h2>
          <span className="text-xs text-muted">
            {agentsQuery.data?.items.filter(
              (item) => item.status !== "QUEUED" && item.status !== "RUNNING",
            ).length ?? 0}
            /8 已结束
          </span>
        </div>
        {agentsQuery.isLoading ? (
          <div className="space-y-2">
            <Skeleton className="h-16" />
            <Skeleton className="h-16" />
            <Skeleton className="h-16" />
          </div>
        ) : (
          <AgentExecutionList executions={agentsQuery.data?.items ?? []} />
        )}
      </section>

      {latestMessages.length > 0 && (
        <section className="mt-8" aria-labelledby="events-title">
          <h2 id="events-title" className="mb-4 text-base font-bold text-navy-strong">
            最新动态
          </h2>
          <div className="divide-y divide-border border-y border-border">
            {latestMessages.map((event) => (
              <div key={event.eventId} className="flex gap-3 py-3 text-sm">
                <time className="w-14 shrink-0 text-xs text-muted">
                  {event.timestamp.toLocaleTimeString("zh-CN", {
                    hour: "2-digit",
                    minute: "2-digit",
                  })}
                </time>
                <p className="min-w-0 flex-1">
                  {stageDescriptions[event.stage] || "行程正在更新"}
                </p>
                <span className="text-xs font-semibold text-muted">{event.progress}%</span>
              </div>
            ))}
          </div>
        </section>
      )}

      {monitor.isTerminal && task.resultResourceIds.size > 0 && (
        <div className="mt-8 flex justify-end">
          <Button asChild>
            <Link
              to={`/plans/${[...task.resultResourceIds][0]}`}
              onClick={() => {
                const planId = [...task.resultResourceIds][0];
                if (storedTask && planId) {
                  rememberPlan(storedTask.tripId, planId);
                }
              }}
            >
              查看旅行方案
            </Link>
          </Button>
        </div>
      )}
    </>
  );
}
