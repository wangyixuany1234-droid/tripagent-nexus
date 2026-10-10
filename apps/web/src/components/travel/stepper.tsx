import { Check, CircleAlert } from "lucide-react";
import { cn } from "@/lib/utils";

export type StepState = "complete" | "current" | "upcoming" | "error";

export interface StepperItem {
  id: string;
  label: string;
  description?: string;
  state: StepState;
}

export function Stepper({ items }: { items: StepperItem[] }) {
  return (
    <ol
      className="grid min-w-0 grid-cols-[repeat(var(--steps),minmax(0,1fr))] gap-1 sm:gap-2"
      style={{ "--steps": items.length } as React.CSSProperties}
    >
      {items.map((item, index) => (
        <li
          key={item.id}
          className="relative min-w-0"
          aria-current={item.state === "current" ? "step" : undefined}
          aria-label={`${item.label}：${
            item.state === "complete"
              ? "已完成"
              : item.state === "current"
                ? "当前步骤"
                : item.state === "error"
                  ? "存在错误"
                  : "未开始"
          }`}
        >
          {index < items.length - 1 && (
            <span
              aria-hidden="true"
              className={cn(
                "absolute top-4 right-0 left-8 h-px bg-border",
                item.state === "complete" && "bg-success",
              )}
            />
          )}
          <span
            className={cn(
              "relative z-10 flex size-8 items-center justify-center rounded-full border-2 bg-surface text-xs font-bold",
              item.state === "complete" && "border-success bg-success text-white",
              item.state === "current" && "border-sky bg-sky-soft text-navy-strong",
              item.state === "upcoming" && "border-border text-muted",
              item.state === "error" && "border-danger bg-danger-soft text-danger",
            )}
          >
            {item.state === "complete" ? (
              <Check className="size-4" />
            ) : item.state === "error" ? (
              <CircleAlert className="size-4" />
            ) : (
              index + 1
            )}
          </span>
          <div className="mt-3 hidden min-w-0 pr-3 sm:block">
            <p className="truncate text-sm font-semibold">{item.label}</p>
            {item.description && (
              <p className="mt-1 text-xs leading-5 text-muted">{item.description}</p>
            )}
          </div>
        </li>
      ))}
    </ol>
  );
}
