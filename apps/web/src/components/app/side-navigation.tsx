import {
  Activity,
  BriefcaseBusiness,
  ClipboardCheck,
  Command,
  FileScan,
  Gauge,
  GitBranch,
  HeartHandshake,
  Map,
  Route,
  Settings,
  ShieldCheck,
  Sparkles,
  type LucideIcon,
} from "lucide-react";
import { NavLink, useLocation } from "react-router-dom";
import { useSystemStatus } from "@/hooks/use-system-status";
import { cn } from "@/lib/utils";

interface NavigationItem {
  label: string;
  to: string;
  icon: LucideIcon;
  developerOnly?: boolean;
}

const navigation: Array<{ label: string; items: NavigationItem[] }> = [
  {
    label: "工作区",
    items: [
      { label: "首页", to: "/", icon: Gauge },
      { label: "创建旅行", to: "/trips/new", icon: Route },
      { label: "行程工作台", to: "/workbench", icon: BriefcaseBusiness },
      { label: "城市探索", to: "/explore", icon: Map },
    ],
  },
  {
    label: "智能协作",
    items: [
      { label: "订单资料", to: "/documents", icon: FileScan },
      { label: "规划进度", to: "/tasks", icon: Sparkles },
      { label: "人工确认", to: "/actions", icon: ShieldCheck },
      { label: "反馈中心", to: "/feedback", icon: HeartHandshake },
    ],
  },
  {
    label: "设置与状态",
    items: [
      { label: "偏好与记忆", to: "/preferences", icon: Settings },
      { label: "系统状态", to: "/system-status", icon: Activity },
      {
        label: "开发者追踪",
        to: "/developer/traces",
        icon: GitBranch,
        developerOnly: true,
      },
      { label: "质量记录", to: "/evaluations", icon: ClipboardCheck },
      { label: "界面预览", to: "/design-system", icon: Sparkles },
    ],
  },
];

interface SideNavigationProps {
  onNavigate?: () => void;
  compact?: boolean;
}

export function BrandMark({ compact = false }: { compact?: boolean }) {
  return (
    <NavLink
      to="/"
      className={cn(
        "flex h-16 items-center gap-3 border-b border-border px-4",
        compact && "justify-center px-2",
      )}
      aria-label="TripAgent Nexus 首页"
    >
      <span className="flex size-9 shrink-0 items-center justify-center rounded-md bg-navy-strong text-white dark:text-background">
        <Command className="size-5" strokeWidth={2.4} />
      </span>
      {!compact && (
        <span className="min-w-0">
          <span className="block truncate text-[15px] font-bold text-navy-strong">
            TripAgent
          </span>
          <span className="block text-[10px] font-semibold tracking-[0.16em] text-coral">
            NEXUS
          </span>
        </span>
      )}
    </NavLink>
  );
}

export function SideNavigation({ onNavigate, compact = false }: SideNavigationProps) {
  const location = useLocation();
  const systemStatus = useSystemStatus();
  return (
    <nav className="flex min-h-0 flex-1 flex-col overflow-y-auto px-3 py-4" aria-label="主导航">
      {navigation.map((section) => (
        <div key={section.label} className="mb-5">
          {!compact && (
            <p className="mb-2 px-2 text-[11px] font-bold text-muted uppercase">
              {section.label}
            </p>
          )}
          <div className="space-y-1">
            {section.items
              .filter(
                (item) =>
                  !item.developerOnly ||
                  systemStatus.data?.developerToolsEnabled === true,
              )
              .map((item) => (
              <NavLink
                key={item.to}
                to={item.to}
                onClick={onNavigate}
                end={item.to === "/"}
                title={compact ? item.label : undefined}
                className={({ isActive }) => {
                  const nestedActive =
                    (item.to === "/documents" &&
                      location.pathname.endsWith("/documents")) ||
                    (item.to === "/explore" &&
                      location.pathname.startsWith("/plans/")) ||
                    (item.to === "/tasks" &&
                      location.pathname.startsWith("/tasks/"));
                  return cn(
                    "flex h-10 items-center gap-3 rounded-md px-2.5 text-sm font-medium text-muted transition-colors hover:bg-surface-subtle hover:text-foreground",
                    compact && "justify-center px-0",
                    (isActive || nestedActive) &&
                      "bg-sky-soft text-navy-strong before:h-5 before:w-0.5 before:rounded-full before:bg-sky",
                  );
                }}
              >
                <item.icon className="size-[18px] shrink-0" />
                {!compact && <span>{item.label}</span>}
              </NavLink>
              ))}
          </div>
        </div>
      ))}
    </nav>
  );
}
