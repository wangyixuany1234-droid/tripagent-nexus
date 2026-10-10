import { useMemo, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import type { TaskStatus } from "@api-generated/models/TaskStatus";
import type { WorkbenchTask } from "@api-generated/models/WorkbenchTask";
import {
  CalendarDays,
  CheckCircle2,
  CircleAlert,
  Clock3,
  MapPin,
  PauseCircle,
  Play,
  RotateCcw,
  Search,
  XCircle,
} from "lucide-react";
import { Link } from "react-router-dom";
import { apiAdapter } from "@/api/client";
import { PageHeader } from "@/components/app/page-header";
import { EmptyState, ErrorState } from "@/components/states/state-panel";
import { Button } from "@/components/ui/button";
import { FormField, Input } from "@/components/ui/form-control";
import { Progress } from "@/components/ui/progress";
import { Skeleton } from "@/components/ui/skeleton";
import { StatusBadge } from "@/components/travel/status-badge";
import { cn } from "@/lib/utils";
import { useAppStore } from "@/stores/app-store";
import { useTripFlowStore } from "@/stores/trip-flow-store";

type TaskGroup = "ALL" | "ACTIVE" | "WAITING" | "COMPLETED" | "FAILED";

const taskGroups: Array<{
  value: TaskGroup;
  label: string;
  icon: typeof Clock3;
}> = [
  { value: "ALL", label: "全部", icon: Clock3 },
  { value: "ACTIVE", label: "进行中", icon: Play },
  { value: "WAITING", label: "待确认", icon: PauseCircle },
  { value: "COMPLETED", label: "已完成", icon: CheckCircle2 },
  { value: "FAILED", label: "失败任务", icon: XCircle },
];

const activeStatuses = new Set<TaskStatus>(["CREATED", "QUEUED", "RUNNING"]);
const completedStatuses = new Set<TaskStatus>(["SUCCEEDED", "PARTIAL_SUCCESS"]);
const failedStatuses = new Set<TaskStatus>(["FAILED", "CANCELLED"]);

const taskTypeLabels: Record<string, string> = {
  DOCUMENT_PARSE: "订单资料整理",
  PLAN_GENERATION: "旅行方案生成",
  PLAN_REPLAN: "旅行方案调整",
  EVALUATION_REPLAY: "方案质量复核",
};

function matchesGroup(status: TaskStatus, group: TaskGroup) {
  if (group === "ACTIVE") return activeStatuses.has(status);
  if (group === "WAITING") return status === "WAITING_CONFIRMATION";
  if (group === "COMPLETED") return completedStatuses.has(status);
  if (group === "FAILED") return failedStatuses.has(status);
  return true;
}

function formatDateRange(startDate: Date | null, endDate: Date | null) {
  if (!startDate || !endDate) return "日期待补充";
  const formatter = new Intl.DateTimeFormat("zh-CN", {
    month: "short",
    day: "numeric",
  });
  return `${formatter.format(startDate)}至${formatter.format(endDate)}`;
}

function continuePath(task: WorkbenchTask) {
  if (task.resultPlanId) return `/plans/${task.resultPlanId}`;
  if (task.type === "DOCUMENT_PARSE") return `/trips/${task.tripId}/documents`;
  return `/tasks/${task.taskId}`;
}

function toDate(value: string) {
  return value ? new Date(`${value}T12:00:00`) : undefined;
}

export function WorkbenchPage() {
  const userId = useTripFlowStore((state) => state.userId);
  const rememberTrip = useTripFlowStore((state) => state.rememberTrip);
  const addNotification = useAppStore((state) => state.addNotification);
  const queryClient = useQueryClient();
  const [destination, setDestination] = useState("");
  const [startDate, setStartDate] = useState("");
  const [endDate, setEndDate] = useState("");
  const [group, setGroup] = useState<TaskGroup>("ALL");
  const invalidDateRange = Boolean(startDate && endDate && endDate < startDate);

  const workbenchQuery = useQuery({
    queryKey: ["workbench", userId, destination, startDate, endDate],
    queryFn: () =>
      apiAdapter.workbench.list({
        userId,
        destination: destination.trim() || undefined,
        startDate: toDate(startDate),
        endDate: toDate(endDate),
      }),
    enabled: !invalidDateRange,
  });

  const cancelTask = useMutation({
    mutationFn: (taskId: string) =>
      apiAdapter.tasks.cancel(taskId, `task-cancel-${crypto.randomUUID()}`),
    onSuccess: () => {
      addNotification({
        tone: "success",
        title: "任务已取消",
        message: "相关进度已停止，已完成的内容仍会保留。",
      });
      void queryClient.invalidateQueries({ queryKey: ["workbench", userId] });
    },
  });

  const tasks = workbenchQuery.data?.items ?? [];
  const visibleTasks = useMemo(
    () => tasks.filter((task) => matchesGroup(task.status, group)),
    [group, tasks],
  );
  const counts = useMemo(
    () =>
      Object.fromEntries(
        taskGroups.map((item) => [
          item.value,
          tasks.filter((task) => matchesGroup(task.status, item.value)).length,
        ]),
      ) as Record<TaskGroup, number>,
    [tasks],
  );

  const clearFilters = () => {
    setDestination("");
    setStartDate("");
    setEndDate("");
    setGroup("ALL");
  };

  return (
    <>
      <PageHeader
        eyebrow="行程管理"
        title="行程工作台"
        description="集中查看旅行资料整理、方案生成与调整进度，并继续尚未完成的任务。"
        breadcrumbs={[{ label: "行程工作台" }]}
        actions={
          <Button asChild>
            <Link to="/trips/new">
              <Play />
              创建旅行
            </Link>
          </Button>
        }
      />

      <section
        aria-label="任务筛选"
        className="border-y border-border bg-surface px-4 py-4 sm:px-5"
      >
        <div className="grid gap-3 md:grid-cols-4">
          <FormField label="目的地" htmlFor="workbench-destination">
            <div className="relative">
              <Search className="pointer-events-none absolute top-3 left-3 size-4 text-muted" />
              <Input
                id="workbench-destination"
                value={destination}
                onChange={(event) => setDestination(event.target.value)}
                placeholder="例如：北京"
                className="pl-9"
              />
            </div>
          </FormField>
          <FormField label="最早出发日期" htmlFor="workbench-start-date">
            <Input
              id="workbench-start-date"
              type="date"
              value={startDate}
              onChange={(event) => setStartDate(event.target.value)}
            />
          </FormField>
          <FormField
            label="最晚返程日期"
            htmlFor="workbench-end-date"
            error={invalidDateRange ? "返程日期不能早于出发日期" : undefined}
          >
            <Input
              id="workbench-end-date"
              type="date"
              value={endDate}
              min={startDate || undefined}
              onChange={(event) => setEndDate(event.target.value)}
            />
          </FormField>
          <div className="flex items-end">
            <Button
              type="button"
              variant="outline"
              className="w-full"
              onClick={clearFilters}
              disabled={!destination && !startDate && !endDate && group === "ALL"}
            >
              <RotateCcw />
              清除筛选
            </Button>
          </div>
        </div>
      </section>

      <div
        className="mt-5 grid grid-cols-2 gap-2 sm:grid-cols-5"
        role="tablist"
        aria-label="任务状态"
      >
        {taskGroups.map((item) => (
          <button
            key={item.value}
            type="button"
            role="tab"
            aria-selected={group === item.value}
            onClick={() => setGroup(item.value)}
            className={cn(
              "flex min-h-16 items-center gap-3 rounded-md border px-3 text-left transition-colors",
              group === item.value
                ? "border-sky bg-sky-soft text-navy-strong"
                : "border-border bg-surface hover:bg-surface-subtle",
            )}
          >
            <item.icon className="size-4 shrink-0" />
            <span>
              <span className="block text-xs font-semibold">{item.label}</span>
              <span className="mt-0.5 block text-lg font-bold tabular-nums">
                {counts[item.value] ?? 0}
              </span>
            </span>
          </button>
        ))}
      </div>

      <section className="mt-6" aria-labelledby="workbench-list-title">
        <div className="mb-3 flex items-center justify-between gap-3">
          <h2 id="workbench-list-title" className="text-base font-bold text-navy-strong">
            {taskGroups.find((item) => item.value === group)?.label}任务
          </h2>
          <span className="text-xs text-muted">共 {visibleTasks.length} 项</span>
        </div>

        {invalidDateRange ? (
          <ErrorState
            compact
            title="日期范围需要调整"
            description="请确认返程日期不早于出发日期。"
          />
        ) : workbenchQuery.isLoading ? (
          <div className="space-y-3">
            <Skeleton className="h-36" />
            <Skeleton className="h-36" />
            <Skeleton className="h-36" />
          </div>
        ) : workbenchQuery.isError ? (
          <ErrorState
            compact
            title="暂时无法读取任务"
            description="行程记录暂未加载，请稍后重试。"
            onAction={() => void workbenchQuery.refetch()}
          />
        ) : visibleTasks.length === 0 ? (
          <EmptyState
            compact
            icon={group === "FAILED" ? CircleAlert : CalendarDays}
            title="没有符合条件的任务"
            description="调整目的地、日期或状态筛选后再查看。"
            actionLabel="清除筛选"
            onAction={clearFilters}
          />
        ) : (
          <div className="grid gap-3 xl:grid-cols-2">
            {visibleTasks.map((task) => {
              const cancellable = activeStatuses.has(task.status);
              return (
                <article
                  key={task.taskId}
                  className="rounded-md border border-border bg-surface p-4 shadow-[var(--shadow)]"
                >
                  <div className="flex items-start justify-between gap-4">
                    <div className="min-w-0">
                      <p className="text-xs font-semibold text-coral">
                        {taskTypeLabels[task.type] ?? "旅行任务"}
                      </p>
                      <h3 className="mt-1 truncate text-base font-bold">{task.title}</h3>
                    </div>
                    <StatusBadge status={task.status} />
                  </div>
                  <div className="mt-4 flex flex-wrap gap-x-5 gap-y-2 text-xs text-muted">
                    <span className="flex items-center gap-1.5">
                      <MapPin className="size-3.5" />
                      {task.destination || "目的地待补充"}
                    </span>
                    <span className="flex items-center gap-1.5">
                      <CalendarDays className="size-3.5" />
                      {formatDateRange(task.startDate, task.endDate)}
                    </span>
                  </div>
                  <div className="mt-4">
                    <div className="mb-1.5 flex justify-between text-xs text-muted">
                      <span>完成进度</span>
                      <span className="font-semibold tabular-nums">{task.progress}%</span>
                    </div>
                    <Progress value={task.progress} label={`${task.title}完成进度`} />
                  </div>
                  <div className="mt-4 flex justify-end gap-2 border-t border-border pt-3">
                    {cancellable && (
                      <Button
                        type="button"
                        size="sm"
                        variant="ghost"
                        disabled={
                          cancelTask.isPending && cancelTask.variables === task.taskId
                        }
                        onClick={() => cancelTask.mutate(task.taskId)}
                      >
                        <XCircle />
                        取消
                      </Button>
                    )}
                    <Button asChild size="sm">
                      <Link
                        to={continuePath(task)}
                        onClick={() => rememberTrip(task.tripId)}
                      >
                        {completedStatuses.has(task.status) ? "查看结果" : "继续任务"}
                        <Play />
                      </Link>
                    </Button>
                  </div>
                </article>
              );
            })}
          </div>
        )}
      </section>
    </>
  );
}
