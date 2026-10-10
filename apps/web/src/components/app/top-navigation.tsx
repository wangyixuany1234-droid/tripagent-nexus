import { Menu, Plus } from "lucide-react";
import { Link } from "react-router-dom";
import { NotificationCenter } from "./notification-center";
import { UserMenu } from "./user-menu";
import { Button } from "@/components/ui/button";
import { useSystemHealth } from "@/hooks/use-system-health";
import { cn } from "@/lib/utils";

interface TopNavigationProps {
  onOpenNavigation: () => void;
}

export function TopNavigation({ onOpenNavigation }: TopNavigationProps) {
  const health = useSystemHealth();
  const demoMode = health.data?.providerMode === "MOCK";

  return (
    <header className="sticky top-0 z-40 flex h-16 items-center border-b border-border bg-surface/95 px-3 backdrop-blur-md sm:px-5">
      <Button
        variant="ghost"
        size="icon"
        className="mr-1 lg:hidden"
        onClick={onOpenNavigation}
        aria-label="打开主导航"
      >
        <Menu />
      </Button>

      <div className="min-w-0 flex-1">
        <p className="truncate text-sm font-bold text-navy-strong sm:text-base">旅行指挥舱</p>
        <div className="mt-0.5 flex items-center gap-1.5 text-[11px] text-muted">
          <span>{demoMode ? "当前展示演示数据" : "规划与行程管理"}</span>
        </div>
      </div>

      <div className="flex items-center gap-1 sm:gap-2">
        <Button asChild size="sm" className="hidden sm:inline-flex">
          <Link to="/trips/new">
            <Plus />
            新建旅行
          </Link>
        </Button>
        <NotificationCenter />
        <div className={cn("ml-1 border-l border-border pl-2 sm:ml-2 sm:pl-3")}>
          <UserMenu />
        </div>
      </div>
    </header>
  );
}
