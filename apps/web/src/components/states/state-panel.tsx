import type { LucideIcon } from "lucide-react";
import { CircleAlert, Compass } from "lucide-react";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";

interface StatePanelProps {
  title: string;
  description: string;
  icon?: LucideIcon;
  actionLabel?: string;
  onAction?: () => void;
  compact?: boolean;
}

export function EmptyState({
  title,
  description,
  icon: Icon = Compass,
  actionLabel,
  onAction,
  compact = false,
}: StatePanelProps) {
  return (
    <div
      className={cn(
        "flex flex-col items-center justify-center border border-dashed border-border bg-surface/60 px-5 text-center",
        compact ? "min-h-44 py-6" : "min-h-64 py-10",
      )}
    >
      <span className="mb-4 flex size-11 items-center justify-center rounded-full bg-sky-soft text-sky">
        <Icon className="size-5" />
      </span>
      <h2 className="text-base font-bold text-navy-strong">{title}</h2>
      <p className="mt-2 max-w-md text-sm leading-6 text-muted">{description}</p>
      {actionLabel && onAction && (
        <Button className="mt-5" onClick={onAction}>
          {actionLabel}
        </Button>
      )}
    </div>
  );
}

export function ErrorState({
  title,
  description,
  actionLabel = "重试",
  onAction,
  compact = false,
}: StatePanelProps) {
  return (
    <div
      role="alert"
      className={cn(
        "flex flex-col items-center justify-center border border-danger/25 bg-danger-soft/50 px-5 text-center",
        compact ? "min-h-44 py-6" : "min-h-64 py-10",
      )}
    >
      <span className="mb-4 flex size-11 items-center justify-center rounded-full bg-danger-soft text-danger">
        <CircleAlert className="size-5" />
      </span>
      <h2 className="text-base font-bold text-danger">{title}</h2>
      <p className="mt-2 max-w-md text-sm leading-6 text-muted">{description}</p>
      {onAction && (
        <Button variant="outline" className="mt-5" onClick={onAction}>
          {actionLabel}
        </Button>
      )}
    </div>
  );
}
