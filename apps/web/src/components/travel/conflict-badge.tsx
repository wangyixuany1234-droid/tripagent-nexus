import type { Conflict } from "@api-generated/models/Conflict";
import { CircleAlert, Info, TriangleAlert, WandSparkles } from "lucide-react";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";
import { cn } from "@/lib/utils";

const severityMeta: Record<
  Conflict["severity"],
  { label: string; icon: typeof Info; className: string }
> = {
  ERROR: {
    label: "阻塞冲突",
    icon: CircleAlert,
    className: "border-danger/20 bg-danger-soft text-danger",
  },
  WARNING: {
    label: "需要关注",
    icon: TriangleAlert,
    className: "border-warning/20 bg-warning-soft text-warning",
  },
  INFO: {
    label: "规则提示",
    icon: Info,
    className: "border-sky/20 bg-sky-soft text-sky",
  },
};

export function ConflictBadge({
  conflict,
  className,
}: {
  conflict: Conflict;
  className?: string;
}) {
  const meta = severityMeta[conflict.severity];
  const Icon = meta.icon;

  return (
    <Tooltip>
      <TooltipTrigger asChild>
        <button
          type="button"
          className={cn(
            "inline-flex h-7 max-w-full items-center gap-1.5 rounded-full border px-2.5 text-xs font-bold",
            meta.className,
            conflict.status === "RESOLVED" && "opacity-60 line-through",
            className,
          )}
        >
          <Icon className="size-3.5 shrink-0" />
          <span className="truncate">{meta.label}</span>
          {conflict.autoFixable && <WandSparkles className="size-3 shrink-0" />}
        </button>
      </TooltipTrigger>
      <TooltipContent className="max-w-80">
        <p className="font-semibold">{meta.label}</p>
        <p className="mt-1 text-white/75">{conflict.message}</p>
        {conflict.autoFixable && (
          <p className="mt-1 text-white/75">可在重规划中快速调整</p>
        )}
      </TooltipContent>
    </Tooltip>
  );
}
