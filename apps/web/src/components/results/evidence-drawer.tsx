import { useEffect, useState } from "react";
import type { Evidence } from "@api-generated/models/Evidence";
import { BookOpen, ExternalLink, ShieldAlert } from "lucide-react";
import {
  Sheet,
  SheetContent,
  SheetDescription,
  SheetTitle,
} from "@/components/ui/sheet";
import { cn } from "@/lib/utils";
import { evidenceSourceMeta } from "@/lib/plan-view";

const evidencePageSize = 20;

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

export function EvidenceDrawer({
  open,
  onOpenChange,
  evidence,
  focusedEvidenceId,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  evidence: Evidence[];
  focusedEvidenceId?: string;
}) {
  const [visibleEvidenceLimit, setVisibleEvidenceLimit] = useState(evidencePageSize);
  const focusedIndex = evidence.findIndex(
    (item) => item.evidenceId === focusedEvidenceId,
  );
  const effectiveVisibleLimit =
    focusedIndex >= visibleEvidenceLimit ? focusedIndex + 1 : visibleEvidenceLimit;
  const visibleEvidence = evidence.slice(0, effectiveVisibleLimit);
  const hiddenEvidenceCount = Math.max(evidence.length - visibleEvidence.length, 0);

  useEffect(() => {
    if (open) {
      setVisibleEvidenceLimit(evidencePageSize);
    }
  }, [evidence, open]);

  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent side="right" className="max-w-[680px] overflow-y-auto p-5 sm:p-7">
        <div className="pr-10">
          <p className="text-xs font-bold text-coral">参考依据</p>
          <SheetTitle className="mt-1 flex items-center gap-2 text-xl font-bold text-navy-strong">
            <BookOpen className="size-5 text-sky" />
            证据抽屉
          </SheetTitle>
          <SheetDescription className="mt-2 text-sm leading-6 text-muted">
            仅展示当前方案已有的参考依据，不补充或推测外部来源。
          </SheetDescription>
        </div>

        {evidence.length === 0 ? (
          <div className="mt-8 border-y border-warning/30 bg-warning-soft/50 px-4 py-6 text-center">
            <ShieldAlert className="mx-auto size-5 text-warning" />
            <p className="mt-2 text-sm font-bold text-warning">当前方案证据不足</p>
            <p className="mt-1 text-xs leading-5 text-muted">
              方案仍可查看，但推荐依据和实时信息需要进一步补充。
            </p>
          </div>
        ) : (
          <div className="mt-6 divide-y divide-border border-y border-border">
            {visibleEvidence.map((item) => {
              const source = evidenceSourceMeta[item.sourceType];
              const url = trustedEvidenceUrl(item.url);
              return (
                <article
                  key={item.evidenceId}
                  className={cn(
                    "scroll-mt-6 py-5",
                    item.evidenceId === focusedEvidenceId && "bg-sky-soft/45 px-3",
                  )}
                >
                  <div className="flex flex-wrap items-center gap-2">
                    <span className={cn("rounded px-2 py-1 text-[10px] font-bold", source.tone)}>
                      {source.label}
                    </span>
                    <span className="text-[10px] font-semibold text-muted">
                      {source.strategy}
                    </span>
                  </div>
                  <h3 className="mt-3 text-sm font-bold text-navy-strong">{item.title}</h3>
                  <p className="mt-2 text-xs leading-6 text-muted">{item.excerpt}</p>
                  <div className="mt-3 flex flex-wrap items-center justify-between gap-2 text-[11px] text-muted">
                    <time>
                      更新时间：
                      {new Intl.DateTimeFormat("zh-CN", {
                        dateStyle: "medium",
                        timeStyle: "short",
                      }).format(item.retrievedAt)}
                    </time>
                    {url && (
                      <a
                        href={url}
                        target="_blank"
                        rel="noreferrer"
                        className="inline-flex items-center gap-1 font-semibold text-sky hover:underline"
                      >
                        查看原始来源
                        <ExternalLink className="size-3" />
                      </a>
                    )}
                  </div>
                </article>
              );
            })}
            {hiddenEvidenceCount > 0 && (
              <div className="py-4">
                <button
                  type="button"
                  className="flex h-10 w-full items-center justify-center gap-2 rounded-md border border-border bg-surface px-3 text-xs font-bold text-navy-strong hover:bg-surface-subtle"
                  onClick={() =>
                    setVisibleEvidenceLimit((current) =>
                      Math.min(current + evidencePageSize, evidence.length),
                    )
                  }
                >
                  显示更多证据
                  <span className="text-muted">剩余 {hiddenEvidenceCount} 条</span>
                </button>
              </div>
            )}
          </div>
        )}
      </SheetContent>
    </Sheet>
  );
}
