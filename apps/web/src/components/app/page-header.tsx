import type { ReactNode } from "react";
import { cn } from "@/lib/utils";
import { Breadcrumbs, type BreadcrumbItem } from "./breadcrumbs";

interface PageHeaderProps {
  title: string;
  description?: string;
  breadcrumbs?: BreadcrumbItem[];
  eyebrow?: string;
  actions?: ReactNode;
  compactOnMobile?: boolean;
}

export function PageHeader({
  title,
  description,
  breadcrumbs = [],
  eyebrow,
  actions,
  compactOnMobile = false,
}: PageHeaderProps) {
  return (
    <div className={cn("mb-6", compactOnMobile && "max-sm:mb-3")}>
      {breadcrumbs.length > 0 && (
        <Breadcrumbs
          items={breadcrumbs}
          className={cn("mb-4", compactOnMobile && "max-sm:mb-2")}
        />
      )}
      <div
        className={cn(
          "flex flex-col gap-4 sm:flex-row sm:items-end sm:justify-between",
          compactOnMobile && "max-sm:gap-2",
        )}
      >
        <div className="min-w-0">
          {eyebrow && (
            <p
              className={cn(
                "mb-1 text-[11px] font-bold text-coral uppercase",
                compactOnMobile && "max-sm:sr-only",
              )}
            >
              {eyebrow}
            </p>
          )}
          <h1 className="text-2xl font-bold text-navy-strong sm:text-[28px]">{title}</h1>
          {description && (
            <p
              className={cn(
                "mt-2 max-w-2xl text-sm leading-6 text-muted",
                compactOnMobile && "max-sm:line-clamp-2 max-sm:leading-5",
              )}
            >
              {description}
            </p>
          )}
        </div>
        {actions && (
          <div
            className={cn(
              "flex shrink-0 flex-wrap gap-2",
              compactOnMobile && "max-lg:hidden",
            )}
          >
            {actions}
          </div>
        )}
      </div>
    </div>
  );
}
