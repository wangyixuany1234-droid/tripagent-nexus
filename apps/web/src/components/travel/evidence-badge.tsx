import type { Evidence } from "@api-generated/models/Evidence";
import { BookOpen, Database, FileCheck, Network, RadioTower } from "lucide-react";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";
import { cn } from "@/lib/utils";

const sourceMeta: Record<
  Evidence["sourceType"],
  { label: string; icon: typeof BookOpen; className: string }
> = {
  PUBLIC_KNOWLEDGE: {
    label: "公共知识",
    icon: BookOpen,
    className: "bg-sky-soft text-sky",
  },
  USER_MEMORY: {
    label: "用户记忆",
    icon: Database,
    className: "bg-success-soft text-success",
  },
  DOCUMENT: {
    label: "订单文档",
    icon: FileCheck,
    className: "bg-warning-soft text-warning",
  },
  PROVIDER: {
    label: "实时来源",
    icon: RadioTower,
    className: "bg-coral-soft text-coral",
  },
  GRAPH: {
    label: "地点关联",
    icon: Network,
    className: "bg-surface-subtle text-navy-strong",
  },
};

function trustedEvidenceUrl(url?: string) {
  if (!url) {
    return undefined;
  }
  try {
    const parsed = new URL(url);
    return parsed.protocol === "https:" || parsed.protocol === "http:" ? url : undefined;
  } catch {
    return undefined;
  }
}

export function EvidenceBadge({
  evidence,
  className,
}: {
  evidence: Evidence;
  className?: string;
}) {
  const meta = sourceMeta[evidence.sourceType];
  const Icon = meta.icon;
  const url = trustedEvidenceUrl(evidence.url);
  const content = (
    <span
      className={cn(
        "inline-flex h-7 max-w-full items-center gap-1.5 rounded-full px-2.5 text-xs font-semibold",
        meta.className,
        className,
      )}
    >
      <Icon className="size-3.5 shrink-0" />
      <span className="truncate">{meta.label}</span>
    </span>
  );

  return (
    <Tooltip>
      <TooltipTrigger asChild>
        {url ? (
          <a href={url} target="_blank" rel="noopener noreferrer">
            {content}
          </a>
        ) : (
          <button type="button">{content}</button>
        )}
      </TooltipTrigger>
      <TooltipContent className="max-w-72">
        <p className="font-semibold">{evidence.title}</p>
        <p className="mt-1 text-white/75">{evidence.excerpt}</p>
      </TooltipContent>
    </Tooltip>
  );
}
