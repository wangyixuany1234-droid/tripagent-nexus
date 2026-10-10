import type { AvailabilityStatus } from "@api-generated/models/AvailabilityStatus";
import {
  BrainCircuit,
  CloudSun,
  Database,
  HardDrive,
  Map,
  MessagesSquare,
  RefreshCcw,
  Search,
  ServerCog,
} from "lucide-react";
import { PageHeader } from "@/components/app/page-header";
import { ErrorState } from "@/components/states/state-panel";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { StatusBadge } from "@/components/travel/status-badge";
import { useSystemStatus } from "@/hooks/use-system-status";
import { cn } from "@/lib/utils";

const icons: Record<string, typeof Database> = {
  database: Database,
  cache: HardDrive,
  messaging: MessagesSquare,
  retrieval: Search,
  model: BrainCircuit,
  map: Map,
  weather: CloudSun,
};

const statusDescriptions: Record<AvailabilityStatus, string> = {
  AVAILABLE: "可正常使用",
  DEGRADED: "部分能力受限",
  UNAVAILABLE: "暂时不可使用",
  DISABLED: "当前未启用",
};

function providerLabel(provider: string) {
  const normalized = provider.toUpperCase();
  if (normalized === "DEEPSEEK") return "模型服务";
  if (normalized === "MOCK") return "演示规划服务";
  if (normalized === "NONE") return "未启用";
  return "已配置的规划服务";
}

function formatCheckedAt(date: Date) {
  return new Intl.DateTimeFormat("zh-CN", {
    month: "short",
    day: "numeric",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
  }).format(date);
}

export function SystemStatusPage() {
  const statusQuery = useSystemStatus();

  if (statusQuery.isLoading) {
    return (
      <div className="space-y-4">
        <Skeleton className="h-24" />
        <Skeleton className="h-44" />
        <Skeleton className="h-44" />
      </div>
    );
  }

  if (statusQuery.isError || !statusQuery.data) {
    return (
      <>
        <PageHeader title="系统状态" breadcrumbs={[{ label: "系统状态" }]} />
        <ErrorState
          title="暂时无法获取系统状态"
          description="状态信息暂未更新，不影响已保存的行程内容。"
          onAction={() => void statusQuery.refetch()}
        />
      </>
    );
  }

  const status = statusQuery.data;
  const demoMode = status.providerMode === "MOCK";

  return (
    <>
      <PageHeader
        eyebrow="服务可用性"
        title="系统状态"
        description="查看规划、地图、天气和资料检索能力的当前可用情况。"
        breadcrumbs={[{ label: "系统状态" }]}
        actions={
          <Button
            type="button"
            variant="outline"
            onClick={() => void statusQuery.refetch()}
            disabled={statusQuery.isFetching}
          >
            <RefreshCcw className={cn(statusQuery.isFetching && "animate-spin")} />
            {statusQuery.isFetching ? "更新中" : "刷新状态"}
          </Button>
        }
      />

      <section
        className={cn(
          "grid gap-5 border-y px-4 py-5 sm:grid-cols-3 sm:px-5",
          status.status === "AVAILABLE"
            ? "border-success/30 bg-success-soft/35"
            : "border-warning/35 bg-warning-soft/35",
        )}
        aria-label="系统概览"
      >
        <div>
          <p className="text-xs font-semibold text-muted">整体状态</p>
          <div className="mt-2">
            <StatusBadge status={status.status} />
          </div>
        </div>
        <div>
          <p className="text-xs font-semibold text-muted">运行模式</p>
          <p className="mt-2 font-bold text-navy-strong">
            {demoMode ? "当前展示演示数据" : "真实服务"}
          </p>
        </div>
        <div>
          <p className="text-xs font-semibold text-muted">智能规划服务</p>
          <p className="mt-2 font-bold text-navy-strong">
            {providerLabel(status.modelProvider)}
          </p>
        </div>
      </section>

      <div className="mt-7 grid gap-8 xl:grid-cols-2">
        <section aria-labelledby="dependencies-title">
          <div className="mb-3 flex items-center gap-2">
            <ServerCog className="size-4 text-sky" />
            <h2 id="dependencies-title" className="text-base font-bold text-navy-strong">
              基础服务
            </h2>
          </div>
          <div className="divide-y divide-border border-y border-border bg-surface">
            {status.dependencies.map((dependency) => {
              const Icon = icons[dependency.key] ?? ServerCog;
              return (
                <div
                  key={dependency.key}
                  className="flex min-h-20 items-center gap-3 px-4 py-3"
                >
                  <span className="flex size-9 shrink-0 items-center justify-center rounded-md bg-surface-subtle text-muted">
                    <Icon className="size-4" />
                  </span>
                  <div className="min-w-0 flex-1">
                    <h3 className="font-bold">{dependency.label}</h3>
                    <p className="mt-1 text-xs text-muted">
                      {statusDescriptions[dependency.status]}
                    </p>
                  </div>
                  <StatusBadge status={dependency.status} />
                </div>
              );
            })}
          </div>
        </section>

        <section aria-labelledby="capabilities-title">
          <div className="mb-3 flex items-center gap-2">
            <BrainCircuit className="size-4 text-coral" />
            <h2 id="capabilities-title" className="text-base font-bold text-navy-strong">
              旅行能力
            </h2>
          </div>
          <div className="divide-y divide-border border-y border-border bg-surface">
            {status.capabilities.map((capability) => {
              const Icon = icons[capability.key] ?? BrainCircuit;
              return (
                <div
                  key={capability.key}
                  className="flex min-h-20 items-center gap-3 px-4 py-3"
                >
                  <span className="flex size-9 shrink-0 items-center justify-center rounded-md bg-surface-subtle text-muted">
                    <Icon className="size-4" />
                  </span>
                  <div className="min-w-0 flex-1">
                    <h3 className="font-bold">{capability.label}</h3>
                    <p className="mt-1 text-xs text-muted">
                      {statusDescriptions[capability.status]}
                    </p>
                  </div>
                  <StatusBadge status={capability.status} />
                </div>
              );
            })}
          </div>
        </section>
      </div>

      <p className="mt-6 text-right text-xs text-muted">
        最近更新：{formatCheckedAt(status.checkedAt)}
      </p>
    </>
  );
}
