import type { DayPlan } from "@api-generated/models/DayPlan";
import type { ItineraryItem } from "@api-generated/models/ItineraryItem";
import {
  BedDouble,
  Clock3,
  Coffee,
  MapPin,
  Route,
  Sparkles,
  Ticket,
} from "lucide-react";
import { formatCurrency, formatTime } from "@/lib/utils";

const itemMeta: Record<
  ItineraryItem["type"],
  { label: string; icon: typeof Route; className: string }
> = {
  TRANSPORT: { label: "交通", icon: Route, className: "bg-sky-soft text-sky" },
  ACCOMMODATION: {
    label: "住宿",
    icon: BedDouble,
    className: "bg-success-soft text-success",
  },
  ATTRACTION: {
    label: "景点",
    icon: Ticket,
    className: "bg-coral-soft text-coral",
  },
  DINING: { label: "餐饮", icon: Coffee, className: "bg-warning-soft text-warning" },
  FREE_TIME: {
    label: "自由活动",
    icon: Sparkles,
    className: "bg-surface-subtle text-muted",
  },
  OTHER: { label: "其他", icon: MapPin, className: "bg-surface-subtle text-muted" },
};

export function Timeline({ day }: { day: DayPlan }) {
  return (
    <section aria-labelledby={`day-${day.dayNumber}-title`}>
      <div className="mb-4 flex items-end justify-between gap-3">
        <div>
          <p className="text-xs font-bold text-coral">第 {day.dayNumber} 天</p>
          <h2 id={`day-${day.dayNumber}-title`} className="mt-1 text-base font-bold">
            {day.title}
          </h2>
        </div>
        <time className="text-xs text-muted">
          {new Intl.DateTimeFormat("zh-CN", {
            month: "long",
            day: "numeric",
            weekday: "short",
          }).format(day.date)}
        </time>
      </div>

      {day.items.length === 0 ? (
        <p className="border-l-2 border-dashed border-border py-5 pl-5 text-sm text-muted">
          当天尚未安排行程
        </p>
      ) : (
        <ol>
          {day.items.map((item, index) => {
            const meta = itemMeta[item.type];
            const Icon = meta.icon;
            return (
              <li
                key={item.itemId}
                className="relative grid min-w-0 grid-cols-[52px_32px_minmax(0,1fr)] gap-3 pb-5 last:pb-0 sm:grid-cols-[76px_36px_minmax(0,1fr)]"
              >
                {index < day.items.length - 1 && (
                  <span className="absolute top-8 bottom-0 left-[67px] w-px bg-border sm:left-[93px]" />
                )}
                <time className="pt-1 text-xs font-bold text-navy-strong sm:text-sm">
                  {formatTime(item.startTime)}
                </time>
                <span
                  className={`relative z-10 flex size-8 items-center justify-center rounded-full ${meta.className}`}
                  title={meta.label}
                >
                  <Icon className="size-3.5" />
                </span>
                <div className="min-w-0 border-b border-border pb-4 last:border-0">
                  <div className="flex min-w-0 flex-wrap items-start justify-between gap-2">
                    <div className="min-w-0">
                      <h3 className="truncate text-sm font-bold">{item.title}</h3>
                      <p className="mt-1 flex items-center gap-1 text-xs text-muted">
                        <MapPin className="size-3 shrink-0" />
                        <span className="truncate">{item.location.name}</span>
                      </p>
                    </div>
                    {item.amount > 0 && (
                      <span className="shrink-0 text-xs font-semibold">
                        {formatCurrency(item.amount, item.currency)}
                      </span>
                    )}
                  </div>
                  {item.description && (
                    <p className="mt-2 line-clamp-2 text-xs leading-5 text-muted">
                      {item.description}
                    </p>
                  )}
                  <div className="mt-2 flex items-center gap-1 text-[11px] text-muted">
                    <Clock3 className="size-3" />
                    {formatTime(item.startTime)}–{formatTime(item.endTime)}
                    {item.evidenceIds.size > 0 && (
                      <span className="ml-2">{item.evidenceIds.size} 条证据</span>
                    )}
                  </div>
                </div>
              </li>
            );
          })}
        </ol>
      )}
    </section>
  );
}
