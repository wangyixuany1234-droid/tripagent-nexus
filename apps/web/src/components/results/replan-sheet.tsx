import { useEffect, useRef, useState } from "react";
import { zodResolver } from "@hookform/resolvers/zod";
import { useMutation } from "@tanstack/react-query";
import type { ReplanRequest } from "@api-generated/models/ReplanRequest";
import type { TravelPlan } from "@api-generated/models/TravelPlan";
import { AlertCircle, LoaderCircle, RefreshCcw, WandSparkles } from "lucide-react";
import { useForm } from "react-hook-form";
import { useNavigate } from "react-router-dom";
import { z } from "zod";
import { apiAdapter } from "@/api/client";
import { Button } from "@/components/ui/button";
import {
  Sheet,
  SheetContent,
  SheetDescription,
  SheetTitle,
} from "@/components/ui/sheet";
import { mutationErrorMessage } from "@/lib/plan-view";
import { useAppStore } from "@/stores/app-store";
import { useTripFlowStore } from "@/stores/trip-flow-store";

const replanSchema = z.object({
  reason: z.string().trim().min(1, "请填写本次修改原因").max(1000),
  naturalLanguageRequest: z.string().trim().max(5000),
  adjustedTotalBudget: z
    .string()
    .trim()
    .refine(
      (value) => value === "" || (Number.isFinite(Number(value)) && Number(value) > 0),
      "预算必须大于 0",
    ),
  adjustedPace: z.enum(["UNCHANGED", "RELAXED", "BALANCED", "INTENSIVE"]),
  addedPreferences: z.string().trim().max(1000),
});

type ReplanFormValues = z.infer<typeof replanSchema>;

export function ReplanSheet({
  open,
  onOpenChange,
  plan,
  lockedItemIds,
  removedItemIds,
  suggestedRequest,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  plan: TravelPlan;
  lockedItemIds: Set<string>;
  removedItemIds: Set<string>;
  suggestedRequest?: string;
}) {
  const navigate = useNavigate();
  const rememberPlanningTask = useTripFlowStore((state) => state.rememberPlanningTask);
  const addNotification = useAppStore((state) => state.addNotification);
  const submittingRef = useRef(false);
  const [idempotencyKey, setIdempotencyKey] = useState(
    () => `plan-replan-${crypto.randomUUID()}`,
  );
  const form = useForm<ReplanFormValues>({
    resolver: zodResolver(replanSchema),
    defaultValues: {
      reason: "调整当前行程",
      naturalLanguageRequest: "",
      adjustedTotalBudget: String(plan.budgetSummary.totalBudget),
      adjustedPace: "UNCHANGED",
      addedPreferences: "",
    },
  });

  useEffect(() => {
    form.reset({
      reason: "调整当前行程",
      naturalLanguageRequest: suggestedRequest ?? "",
      adjustedTotalBudget: String(plan.budgetSummary.totalBudget),
      adjustedPace: "UNCHANGED",
      addedPreferences: "",
    });
    setIdempotencyKey(`plan-replan-${crypto.randomUUID()}`);
  }, [form, plan.planId, plan.budgetSummary.totalBudget, suggestedRequest]);

  const mutation = useMutation({
    mutationFn: (request: ReplanRequest) =>
      apiAdapter.plans.replan(plan.planId, request, idempotencyKey),
    onSuccess: (accepted) => {
      rememberPlanningTask({
        taskId: accepted.taskId,
        resourceId: accepted.resourceId,
        traceId: accepted.traceId,
        tripId: plan.tripId,
        type: "PLAN_REPLAN",
        createdAt: accepted.createdAt.toISOString(),
      });
      addNotification({
        tone: "success",
        title: "行程调整已提交",
        message: "旧版本保持不变，完成后会生成一个新版本。",
      });
      onOpenChange(false);
      void navigate(`/tasks/${accepted.taskId}`);
    },
  });

  const submit = (values: ReplanFormValues) => {
    if (submittingRef.current || mutation.isPending) {
      return;
    }
    submittingRef.current = true;
    mutation.mutate(
      {
        reason: values.reason,
        naturalLanguageRequest: values.naturalLanguageRequest || undefined,
        lockedItemIds: new Set(lockedItemIds),
        removedItemIds: new Set(removedItemIds),
        adjustedTotalBudget: values.adjustedTotalBudget
          ? Number(values.adjustedTotalBudget)
          : undefined,
        adjustedPace:
          values.adjustedPace === "UNCHANGED" ? undefined : values.adjustedPace,
        addedPreferences: new Set(
          values.addedPreferences
            .split(/[，,]/)
            .map((item) => item.trim())
            .filter(Boolean),
        ),
      },
      {
        onSettled: () => {
          submittingRef.current = false;
        },
      },
    );
  };

  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent side="right" className="max-w-[620px] overflow-y-auto p-5 sm:p-7">
        <div className="pr-10">
          <p className="text-xs font-bold text-coral">调整行程</p>
          <SheetTitle className="mt-1 flex items-center gap-2 text-xl font-bold text-navy-strong">
            <WandSparkles className="size-5 text-sky" />
            生成新版本
          </SheetTitle>
          <SheetDescription className="mt-2 text-sm leading-6 text-muted">
            当前版本 {plan.version} 不会被覆盖，调整完成后会保留为一个新版本。
          </SheetDescription>
        </div>

        <div className="mt-5 grid grid-cols-2 divide-x divide-border border-y border-border py-3 text-center">
          <div>
            <p className="text-lg font-bold">{lockedItemIds.size}</p>
            <p className="text-xs text-muted">锁定行程项</p>
          </div>
          <div>
            <p className="text-lg font-bold">{removedItemIds.size}</p>
            <p className="text-xs text-muted">待删除行程项</p>
          </div>
        </div>

        <form className="mt-5 space-y-5" onSubmit={form.handleSubmit(submit)}>
          <label className="block">
            <span className="text-xs font-bold">修改原因</span>
            <input
              {...form.register("reason")}
              className="mt-2 h-10 w-full rounded-md border border-border bg-surface px-3 text-sm"
            />
            {form.formState.errors.reason && (
              <span className="mt-1 block text-xs text-danger">
                {form.formState.errors.reason.message}
              </span>
            )}
          </label>

          <label className="block">
            <span className="text-xs font-bold">自然语言修改</span>
            <textarea
              {...form.register("naturalLanguageRequest")}
              rows={5}
              placeholder="例如：保留已确认酒店，减少跨区移动，第二天下午安排休息。"
              className="mt-2 w-full resize-y rounded-md border border-border bg-surface px-3 py-2 text-sm leading-6"
            />
          </label>

          <div className="grid gap-4 sm:grid-cols-2">
            <label>
              <span className="text-xs font-bold">调整总预算</span>
              <input
                {...form.register("adjustedTotalBudget")}
                inputMode="decimal"
                className="mt-2 h-10 w-full rounded-md border border-border bg-surface px-3 text-sm"
              />
              {form.formState.errors.adjustedTotalBudget && (
                <span className="mt-1 block text-xs text-danger">
                  {form.formState.errors.adjustedTotalBudget.message}
                </span>
              )}
            </label>
            <label>
              <span className="text-xs font-bold">旅行节奏</span>
              <select
                {...form.register("adjustedPace")}
                className="mt-2 h-10 w-full rounded-md border border-border bg-surface px-3 text-sm"
              >
                <option value="UNCHANGED">保持当前</option>
                <option value="RELAXED">舒缓</option>
                <option value="BALANCED">均衡</option>
                <option value="INTENSIVE">紧凑</option>
              </select>
            </label>
          </div>

          <label className="block">
            <span className="text-xs font-bold">新增偏好</span>
            <input
              {...form.register("addedPreferences")}
              placeholder="用逗号分隔，例如：胡同历史，清真餐饮"
              className="mt-2 h-10 w-full rounded-md border border-border bg-surface px-3 text-sm"
            />
          </label>

          {mutation.isError && (
            <div role="alert" className="border-l-2 border-danger bg-danger-soft/50 px-3 py-2">
              <p className="flex items-center gap-2 text-xs font-bold text-danger">
                <AlertCircle className="size-3.5" />
                本次调整暂未提交
              </p>
              <p className="mt-1 text-xs leading-5 text-muted">
                {mutationErrorMessage(mutation.error)}
              </p>
            </div>
          )}

          <div className="flex justify-end gap-2 border-t border-border pt-4">
            <Button type="button" variant="ghost" onClick={() => onOpenChange(false)}>
              取消
            </Button>
            <Button type="submit" disabled={mutation.isPending}>
              {mutation.isPending ? (
                <LoaderCircle className="animate-spin" />
              ) : (
                <RefreshCcw />
              )}
              {mutation.isError ? "重试提交" : "生成新版本"}
            </Button>
          </div>
        </form>
      </SheetContent>
    </Sheet>
  );
}
