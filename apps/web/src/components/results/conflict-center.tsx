import { useEffect, useState } from "react";
import type { Conflict } from "@api-generated/models/Conflict";
import { AlertTriangle, CheckCircle2, History, WandSparkles } from "lucide-react";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";

const conflictPageSize = 10;

const severityMeta: Record<
  Conflict["severity"],
  { label: string; className: string }
> = {
  ERROR: { label: "严重", className: "bg-danger-soft text-danger" },
  WARNING: { label: "警告", className: "bg-warning-soft text-warning" },
  INFO: { label: "提示", className: "bg-sky-soft text-sky" },
};

const typeLabels: Record<Conflict["type"], string> = {
  TIME: "时间规则",
  BUDGET: "预算规则",
  LOCATION: "地点规则",
  AVAILABILITY: "可用性规则",
  PREFERENCE: "偏好约束",
  EVIDENCE: "证据规则",
};

function suggestion(conflict: Conflict) {
  return conflict.autoFixable
    ? "可在保留其他安排的前提下局部调整行程。"
    : "需要调整约束或接受风险后重新生成方案。";
}

export interface HistoricalConflict {
  conflict: Conflict;
  version: number;
}

export function ConflictCenter({
  conflicts,
  history,
  onReplan,
}: {
  conflicts: Conflict[];
  history: HistoricalConflict[];
  onReplan: (conflict: Conflict) => void;
}) {
  const [visibleConflictLimit, setVisibleConflictLimit] = useState(conflictPageSize);
  const open = conflicts.filter((conflict) => conflict.status === "OPEN");
  const visibleOpen = open.slice(0, visibleConflictLimit);
  const hiddenOpenCount = Math.max(open.length - visibleOpen.length, 0);

  useEffect(() => {
    setVisibleConflictLimit(conflictPageSize);
  }, [conflicts]);

  return (
    <section aria-labelledby="conflict-center-title">
      <div className="flex items-center justify-between gap-3">
        <div>
          <p className="text-xs font-semibold text-muted">行程约束核对</p>
          <h2 id="conflict-center-title" className="mt-1 text-base font-bold">
            冲突中心
          </h2>
        </div>
        <span
          className={cn(
            "rounded px-2 py-1 text-xs font-bold",
            open.length > 0 ? "bg-warning-soft text-warning" : "bg-success-soft text-success",
          )}
        >
          {open.length} 个待处理
        </span>
      </div>

      {open.length === 0 ? (
        <div className="mt-4 flex items-center gap-2 border-y border-border py-4 text-sm text-success">
          <CheckCircle2 className="size-4" />
          当前版本没有待处理冲突
        </div>
      ) : (
        <div className="mt-4 divide-y divide-border border-y border-border">
          {visibleOpen.map((conflict) => {
            const severity = severityMeta[conflict.severity];
            return (
              <article key={conflict.conflictId} className="py-4">
                <div className="flex items-start justify-between gap-3">
                  <div className="min-w-0">
                    <p className="flex items-center gap-2 text-sm font-bold">
                      <AlertTriangle className="size-4 shrink-0 text-warning" />
                      <span className="truncate">{conflict.message}</span>
                    </p>
                    <p className="mt-2 text-xs text-muted">
                      冲突对象：
                      {conflict.relatedItemIds.size > 0
                        ? `${conflict.relatedItemIds.size} 个行程项`
                        : "方案整体"}
                    </p>
                  </div>
                  <span className={cn("shrink-0 rounded px-2 py-1 text-[10px] font-bold", severity.className)}>
                    {severity.label}
                  </span>
                </div>
                <dl className="mt-3 grid gap-2 text-xs">
                  <div>
                    <dt className="inline text-muted">规则依据：</dt>
                    <dd className="inline">{typeLabels[conflict.type]}</dd>
                  </div>
                  <div>
                    <dt className="inline text-muted">修复建议：</dt>
                    <dd className="inline">{suggestion(conflict)}</dd>
                  </div>
                </dl>
                <Button
                  type="button"
                  variant="outline"
                  size="sm"
                  className="mt-3"
                  onClick={() => onReplan(conflict)}
                >
                  <WandSparkles />
                  带入重规划
                </Button>
              </article>
            );
          })}
          {hiddenOpenCount > 0 && (
            <div className="py-3">
              <Button
                type="button"
                variant="outline"
                size="sm"
                className="w-full"
                onClick={() =>
                  setVisibleConflictLimit((current) =>
                    Math.min(current + conflictPageSize, open.length),
                  )
                }
              >
                显示更多冲突
                <span className="text-xs text-muted">剩余 {hiddenOpenCount} 条</span>
              </Button>
            </div>
          )}
        </div>
      )}

      {history.length > 0 && (
        <details className="mt-4">
          <summary className="flex cursor-pointer list-none items-center gap-2 text-xs font-bold text-muted">
            <History className="size-3.5" />
            已解决历史 {history.length}
          </summary>
          <div className="mt-2 divide-y divide-border border-y border-border">
            {history.map(({ conflict, version }) => (
              <div key={`${conflict.conflictId}-${version}`} className="py-3 text-xs">
                <p className="font-semibold">{conflict.message}</p>
                <p className="mt-1 text-muted">
                  v{version} · {typeLabels[conflict.type]} ·{" "}
                  {conflict.status === "RESOLVED" ? "已解决" : "已接受"}
                </p>
              </div>
            ))}
          </div>
        </details>
      )}
    </section>
  );
}
