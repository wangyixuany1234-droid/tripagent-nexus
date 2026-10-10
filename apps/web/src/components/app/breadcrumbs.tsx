import { ChevronRight, Home } from "lucide-react";
import { Link } from "react-router-dom";
import { cn } from "@/lib/utils";

export interface BreadcrumbItem {
  label: string;
  to?: string;
}

export function Breadcrumbs({
  items,
  className,
}: {
  items: BreadcrumbItem[];
  className?: string;
}) {
  return (
    <nav aria-label="面包屑" className={cn("overflow-hidden", className)}>
      <ol className="flex min-w-0 items-center gap-1 text-xs text-muted">
        <li>
          <Link
            to="/"
            className="flex size-7 items-center justify-center rounded hover:bg-surface-subtle hover:text-foreground"
            aria-label="首页"
          >
            <Home className="size-3.5" />
          </Link>
        </li>
        {items.map((item, index) => (
          <li key={`${item.label}-${index}`} className="flex min-w-0 items-center gap-1">
            <ChevronRight className="size-3.5 shrink-0 text-border" />
            {item.to ? (
              <Link
                to={item.to}
                className="truncate rounded px-1 py-1 hover:bg-surface-subtle hover:text-foreground"
              >
                {item.label}
              </Link>
            ) : (
              <span aria-current="page" className="truncate px-1 py-1 text-foreground">
                {item.label}
              </span>
            )}
          </li>
        ))}
      </ol>
    </nav>
  );
}
