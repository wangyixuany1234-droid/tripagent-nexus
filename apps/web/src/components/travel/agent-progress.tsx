import type { AgentResult } from "@api-generated/models/AgentResult";
import {
  BedDouble,
  Bot,
  Calculator,
  CheckCircle2,
  ClipboardCheck,
  Compass,
  Route,
  Search,
  Sparkles,
} from "lucide-react";
import { StatusBadge } from "./status-badge";
import { cn } from "@/lib/utils";

const agentMeta: Record<string, { label: string; icon: typeof Bot }> = {
  RequirementAgent: { label: "需求理解", icon: ClipboardCheck },
  ResearchAgent: { label: "旅行研究", icon: Search },
  TransportAgent: { label: "交通规划", icon: Route },
  AccommodationAgent: { label: "住宿规划", icon: BedDouble },
  RecommendationAgent: { label: "探索推荐", icon: Compass },
  BudgetAgent: { label: "预算核算", icon: Calculator },
  ReviewAgent: { label: "行程审校", icon: CheckCircle2 },
  CoordinatorAgent: { label: "方案编排", icon: Sparkles },
};

export function AgentProgress({
  agents,
  title = "智能协作进度",
}: {
  agents: AgentResult[];
  title?: string;
}) {
  return (
    <section className="rounded-md border border-border bg-surface p-4">
      <div className="flex items-center gap-2">
        <span className="flex size-8 items-center justify-center rounded-md bg-sky-soft text-sky">
          <Bot className="size-4" />
        </span>
        <h2 className="text-sm font-bold text-navy-strong">{title}</h2>
      </div>

      <ol className="mt-4 space-y-1">
        {agents.map((agent, index) => {
          const meta = agentMeta[agent.agentType] ?? {
            label: "旅行安排",
            icon: Bot,
          };
          const Icon = meta.icon;
          const active = agent.status === "RUNNING";
          return (
            <li
              key={agent.agentResultId}
              className="relative flex min-h-12 items-center gap-3 py-1"
            >
              {index < agents.length - 1 && (
                <span className="absolute top-9 bottom-[-9px] left-[15px] w-px bg-border" />
              )}
              <span
                className={cn(
                  "relative z-10 flex size-8 shrink-0 items-center justify-center rounded-full border bg-surface text-muted",
                  active && "border-sky bg-sky-soft text-sky",
                  agent.status === "SUCCEEDED" && "border-success/30 text-success",
                  agent.status === "FAILED" && "border-danger/30 text-danger",
                )}
              >
                <Icon className="size-3.5" />
              </span>
              <div className="min-w-0 flex-1">
                <p className="truncate text-sm font-semibold">{meta.label}</p>
                <p className="mt-0.5 truncate text-xs text-muted">
                  {agent.evidenceIds.length} 条证据
                </p>
              </div>
              <StatusBadge status={agent.status} showDot={active} />
            </li>
          );
        })}
      </ol>
    </section>
  );
}
