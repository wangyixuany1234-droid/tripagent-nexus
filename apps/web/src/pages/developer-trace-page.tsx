import { useMemo, useState, type FormEvent } from "react";
import { useQuery } from "@tanstack/react-query";
import type { TraceSpanCategory } from "@api-generated/models/TraceSpanCategory";
import {
  Bot,
  Boxes,
  Braces,
  Cable,
  Database,
  EyeOff,
  GitBranch,
  Search,
  ShieldAlert,
  Sparkles,
} from "lucide-react";
import { apiAdapter } from "@/api/client";
import { PageHeader } from "@/components/app/page-header";
import { EmptyState, ErrorState } from "@/components/states/state-panel";
import { Button } from "@/components/ui/button";
import { FormField, Input } from "@/components/ui/form-control";
import { Skeleton } from "@/components/ui/skeleton";
import { cn } from "@/lib/utils";
import { useAppStore } from "@/stores/app-store";
import { useSystemStatus } from "@/hooks/use-system-status";

type CategoryFilter = "ALL" | TraceSpanCategory;

const categories: Array<{
  value: CategoryFilter;
  label: string;
  icon: typeof Bot;
}> = [
  { value: "ALL", label: "全部", icon: GitBranch },
  { value: "MODEL", label: "模型", icon: Sparkles },
  { value: "RAG", label: "知识检索", icon: Database },
  { value: "AGENT", label: "智能协作", icon: Bot },
  { value: "MCP", label: "工具调用", icon: Cable },
  { value: "A2A", label: "任务委派", icon: Boxes },
  { value: "KAFKA", label: "异步消息", icon: Braces },
];

const statusLabels: Record<string, string> = {
  OK: "正常",
  ERROR: "异常",
  PARTIAL: "部分完成",
  UNSET: "未标记",
};

function isSensitiveAttribute(name: string) {
  const normalized = name.toLowerCase();
  return (
    normalized.includes("prompt") ||
    normalized.includes("authorization") ||
    normalized.includes("api.key") ||
    normalized.includes("user.")
  );
}

function formatTime(date: Date) {
  return new Intl.DateTimeFormat("zh-CN", {
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
    fractionalSecondDigits: 3,
  }).format(date);
}

export function DeveloperTracePage() {
  const systemStatus = useSystemStatus();
  const lastTraceId = useAppStore((state) => state.lastTraceId);
  const [traceInput, setTraceInput] = useState(lastTraceId ?? "");
  const [traceId, setTraceId] = useState("");
  const [category, setCategory] = useState<CategoryFilter>("ALL");
  const [validationError, setValidationError] = useState("");
  const enabled = systemStatus.data?.developerToolsEnabled === true;

  const traceQuery = useQuery({
    queryKey: ["developer-trace", traceId],
    queryFn: () => apiAdapter.developer.getTrace(traceId),
    enabled: enabled && Boolean(traceId),
    retry: false,
  });

  const visibleSpans = useMemo(
    () =>
      traceQuery.data?.spans.filter(
        (span) => category === "ALL" || span.category === category,
      ) ?? [],
    [category, traceQuery.data],
  );

  const submit = (event: FormEvent) => {
    event.preventDefault();
    const normalized = traceInput.trim().toLowerCase();
    if (!/^[0-9a-f]{32}$/.test(normalized)) {
      setValidationError("请输入 32 位小写十六进制追踪编号。");
      return;
    }
    setValidationError("");
    setTraceId(normalized);
  };

  if (systemStatus.isLoading) {
    return <Skeleton className="h-[520px] w-full" />;
  }

  if (!enabled) {
    return (
      <>
        <PageHeader title="开发者追踪" breadcrumbs={[{ label: "开发者追踪" }]} />
        <EmptyState
          icon={ShieldAlert}
          title="当前账号无法查看追踪信息"
          description="该页面仅在受控开发环境或具备管理权限时开放。"
        />
      </>
    );
  }

  return (
    <>
      <PageHeader
        eyebrow="受控诊断"
        title="开发者追踪"
        description="按追踪编号查看模型、知识检索、智能协作、工具调用、任务委派和异步消息链路。敏感内容默认隐藏。"
        breadcrumbs={[{ label: "开发者追踪" }]}
      />

      <form
        onSubmit={submit}
        className="border-y border-border bg-surface px-4 py-5 sm:px-5"
      >
        <div className="grid gap-3 sm:grid-cols-[minmax(0,1fr)_auto] sm:items-end">
          <FormField
            label="追踪编号"
            htmlFor="trace-id"
            error={validationError || undefined}
            hint="可从本次会话最近一次请求中带入。"
          >
            <div className="relative">
              <Search className="pointer-events-none absolute top-3 left-3 size-4 text-muted" />
              <Input
                id="trace-id"
                value={traceInput}
                inputMode="text"
                autoComplete="off"
                spellCheck={false}
                maxLength={32}
                onChange={(event) => setTraceInput(event.target.value)}
                className="pl-9 font-mono"
                placeholder="32 位追踪编号"
              />
            </div>
          </FormField>
          <Button type="submit">
            <Search />
            查询链路
          </Button>
        </div>
      </form>

      {!traceId ? (
        <div className="mt-6">
          <EmptyState
            compact
            icon={GitBranch}
            title="输入追踪编号开始查询"
            description="查询结果只展示经过服务端筛选和脱敏的诊断信息。"
          />
        </div>
      ) : traceQuery.isLoading ? (
        <div className="mt-6 space-y-3">
          <Skeleton className="h-24" />
          <Skeleton className="h-40" />
          <Skeleton className="h-40" />
        </div>
      ) : traceQuery.isError ? (
        <div className="mt-6">
          <ErrorState
            compact
            title="暂时无法读取这条追踪"
            description="请确认编号有效且当前账号具备访问权限。"
            onAction={() => void traceQuery.refetch()}
          />
        </div>
      ) : traceQuery.data ? (
        <>
          <section className="mt-6 grid gap-3 sm:grid-cols-3" aria-label="链路摘要">
            <div className="border-l-2 border-sky bg-surface px-4 py-3">
              <p className="text-xs text-muted">链路状态</p>
              <p className="mt-1 text-lg font-bold text-navy-strong">
                {statusLabels[traceQuery.data.status]}
              </p>
            </div>
            <div className="border-l-2 border-coral bg-surface px-4 py-3">
              <p className="text-xs text-muted">总耗时</p>
              <p className="mt-1 text-lg font-bold text-navy-strong">
                {traceQuery.data.durationMs.toLocaleString("zh-CN")} 毫秒
              </p>
            </div>
            <div className="border-l-2 border-success bg-surface px-4 py-3">
              <p className="text-xs text-muted">链路节点</p>
              <p className="mt-1 text-lg font-bold text-navy-strong">
                {traceQuery.data.spans.length} 个
              </p>
            </div>
          </section>

          <div
            className="mt-5 flex gap-2 overflow-x-auto pb-1"
            role="tablist"
            aria-label="链路分类"
          >
            {categories.map((item) => {
              const count =
                item.value === "ALL"
                  ? traceQuery.data.spans.length
                  : traceQuery.data.spans.filter(
                      (span) => span.category === item.value,
                    ).length;
              return (
                <button
                  key={item.value}
                  type="button"
                  role="tab"
                  aria-selected={category === item.value}
                  onClick={() => setCategory(item.value)}
                  className={cn(
                    "flex h-9 shrink-0 items-center gap-2 rounded-md border px-3 text-xs font-semibold",
                    category === item.value
                      ? "border-sky bg-sky-soft text-navy-strong"
                      : "border-border bg-surface text-muted hover:bg-surface-subtle",
                  )}
                >
                  <item.icon className="size-3.5" />
                  {item.label}
                  <span className="tabular-nums">{count}</span>
                </button>
              );
            })}
          </div>

          <section className="mt-4" aria-labelledby="trace-timeline-title">
            <div className="mb-3 flex items-center justify-between gap-3">
              <h2
                id="trace-timeline-title"
                className="text-base font-bold text-navy-strong"
              >
                执行链路
              </h2>
              <div className="flex items-center gap-1.5 text-xs text-muted">
                <EyeOff className="size-3.5" />
                敏感内容已隐藏
              </div>
            </div>

            {visibleSpans.length === 0 ? (
              <EmptyState
                compact
                icon={GitBranch}
                title="该分类没有链路节点"
                description="切换其他分类查看完整执行过程。"
              />
            ) : (
              <ol className="relative border-l border-border pl-5">
                {visibleSpans.map((span) => (
                  <li key={span.spanId} className="relative pb-5 last:pb-0">
                    <span
                      className={cn(
                        "absolute top-4 -left-[25px] size-2.5 rounded-full border-2 border-background",
                        span.status === "ERROR" ? "bg-danger" : "bg-sky",
                      )}
                    />
                    <article className="rounded-md border border-border bg-surface p-4">
                      <div className="flex flex-wrap items-start justify-between gap-3">
                        <div className="min-w-0">
                          <p className="text-[11px] font-bold text-coral">
                            {categories.find((item) => item.value === span.category)
                              ?.label ?? "智能协作"}
                          </p>
                          <h3 className="mt-1 break-words font-mono text-sm font-bold">
                            {span.name}
                          </h3>
                        </div>
                        <span
                          className={cn(
                            "rounded-full px-2 py-1 text-[11px] font-bold",
                            span.status === "ERROR"
                              ? "bg-danger-soft text-danger"
                              : "bg-success-soft text-success",
                          )}
                        >
                          {statusLabels[span.status]}
                        </span>
                      </div>
                      <div className="mt-3 flex flex-wrap gap-x-5 gap-y-1 text-xs text-muted">
                        <time>{formatTime(span.startedAt)}</time>
                        <span>{span.durationMs.toLocaleString("zh-CN")} 毫秒</span>
                      </div>
                      {Object.keys(span.attributes).length > 0 && (
                        <dl className="mt-4 grid gap-x-5 gap-y-2 border-t border-border pt-3 sm:grid-cols-2">
                          {Object.entries(span.attributes).map(([name, value]) => (
                            <div key={name} className="min-w-0">
                              <dt className="truncate font-mono text-[11px] text-muted">
                                {name}
                              </dt>
                              <dd className="mt-0.5 break-words font-mono text-xs">
                                {isSensitiveAttribute(name) ? "***" : value}
                              </dd>
                            </div>
                          ))}
                        </dl>
                      )}
                    </article>
                  </li>
                ))}
              </ol>
            )}
          </section>
        </>
      ) : null}
    </>
  );
}
