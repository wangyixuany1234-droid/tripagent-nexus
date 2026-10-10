import { cn } from "@/lib/utils";

export function Progress({
  value,
  className,
  label = "进度",
}: {
  value: number;
  className?: string;
  label?: string;
}) {
  const normalized = Math.min(100, Math.max(0, value));
  return (
    <div
      role="progressbar"
      aria-label={label}
      aria-valuemin={0}
      aria-valuemax={100}
      aria-valuenow={normalized}
      className={cn("h-1.5 overflow-hidden rounded-full bg-surface-subtle", className)}
    >
      <div
        className="h-full rounded-full bg-sky transition-[width] duration-300"
        style={{ width: `${normalized}%` }}
      />
    </div>
  );
}
