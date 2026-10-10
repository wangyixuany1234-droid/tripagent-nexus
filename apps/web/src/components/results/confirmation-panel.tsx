import { useState } from "react";
import { useMutation } from "@tanstack/react-query";
import type { GuardedAction } from "@api-generated/models/GuardedAction";
import type { TravelPlan } from "@api-generated/models/TravelPlan";
import {
  AlertCircle,
  Check,
  Clock3,
  LoaderCircle,
  ShieldCheck,
  WalletCards,
  X,
} from "lucide-react";
import { apiAdapter } from "@/api/client";
import { Button } from "@/components/ui/button";
import {
  Sheet,
  SheetContent,
  SheetDescription,
  SheetTitle,
} from "@/components/ui/sheet";
import { mutationErrorMessage } from "@/lib/plan-view";
import { formatCurrency } from "@/lib/utils";
import { useAppStore } from "@/stores/app-store";

const actionRisk =
  "确认只用于后续预订前的人工核对，不会自动支付。商家价格、库存和取消条款仍可能变化。";

export function ConfirmationPanel({
  plan,
  onPlanConfirmed,
}: {
  plan: TravelPlan;
  onPlanConfirmed: () => void;
}) {
  const addNotification = useAppStore((state) => state.addNotification);
  const [hitlOpen, setHitlOpen] = useState(false);
  const [acknowledged, setAcknowledged] = useState(false);
  const [action, setAction] = useState<GuardedAction>();
  const [planConfirmKey] = useState(() => `plan-confirm-${crypto.randomUUID()}`);
  const [actionProposeKey] = useState(() => `action-propose-${crypto.randomUUID()}`);
  const [actionConfirmKey] = useState(() => `action-confirm-${crypto.randomUUID()}`);
  const [actionRejectKey] = useState(() => `action-reject-${crypto.randomUUID()}`);
  const [expiresAt] = useState(() => new Date(Date.now() + 15 * 60_000));

  const confirmPlan = useMutation({
    mutationFn: () =>
      apiAdapter.plans.confirm(
        plan.planId,
        { expectedVersion: plan.version },
        planConfirmKey,
      ),
    onSuccess: () => {
      addNotification({
        tone: "success",
        title: "候选行程已确认",
        message: "确认不会创建订单或触发支付。",
      });
      onPlanConfirmed();
    },
  });

  const proposeAction = useMutation({
    mutationFn: () =>
      apiAdapter.actions.propose(
        {
          type: "ORDER_CREATE",
          resourceId: plan.planId,
          riskDescription: actionRisk,
          amount: plan.budgetSummary.estimatedTotal,
          currency: plan.budgetSummary.currency,
          expiresAt,
        },
        actionProposeKey,
      ),
    onSuccess: setAction,
  });

  const confirmAction = useMutation({
    mutationFn: () =>
      apiAdapter.actions.confirm(
        action!.actionId,
        {
          actionId: action!.actionId,
          expectedVersion: action!.version,
          acknowledgedRisk: true,
          acknowledgedAmount: action!.amount,
          currency: action!.currency,
        },
        actionConfirmKey,
      ),
    onSuccess: (confirmed) => {
      setAction(confirmed);
      addNotification({
        tone: "success",
        title: "费用授权已确认",
        message: "本次仅记录你的确认，不会向商家提交订单或扣款。",
      });
    },
  });

  const rejectAction = useMutation({
    mutationFn: () =>
      apiAdapter.actions.reject(
        action!.actionId,
        {
          actionId: action!.actionId,
          expectedVersion: action!.version,
          reason: "用户在结果页拒绝候选预订操作。",
        },
        actionRejectKey,
      ),
    onSuccess: (rejected) => {
      setAction(rejected);
      setAcknowledged(false);
    },
  });

  const actionError =
    proposeAction.error ?? confirmAction.error ?? rejectAction.error ?? undefined;
  const actionPending =
    proposeAction.isPending || confirmAction.isPending || rejectAction.isPending;

  return (
    <>
      <section aria-labelledby="confirmation-title">
        <p className="text-xs font-semibold text-muted">操作</p>
        <h2 id="confirmation-title" className="mt-1 text-base font-bold">
          确认与重规划
        </h2>
        <div className="mt-4 grid gap-2">
          <Button
            type="button"
            disabled={plan.status === "CONFIRMED" || confirmPlan.isPending}
            onClick={() => confirmPlan.mutate()}
          >
            {confirmPlan.isPending ? (
              <LoaderCircle className="animate-spin" />
            ) : (
              <Check />
            )}
            {plan.status === "CONFIRMED"
              ? "行程已确认"
              : confirmPlan.isError
                ? "重试确认"
                : "确认候选行程"}
          </Button>
          <Button
            type="button"
            variant="outline"
            onClick={() => setHitlOpen(true)}
          >
            <ShieldCheck />
            费用操作确认
          </Button>
        </div>
        <p className="mt-3 text-[11px] leading-5 text-muted">
          确认行程只保存你的选择，不会创建订单或发起支付。
        </p>
        {confirmPlan.isError && (
          <div role="alert" className="mt-3 border-l-2 border-danger pl-3 text-xs">
            <p className="font-bold text-danger">确认失败</p>
            <p className="mt-1 text-muted">{mutationErrorMessage(confirmPlan.error)}</p>
          </div>
        )}
      </section>

      <Sheet open={hitlOpen} onOpenChange={setHitlOpen}>
        <SheetContent side="right" className="max-w-[560px] overflow-y-auto p-5 sm:p-7">
          <div className="pr-10">
            <p className="text-xs font-bold text-coral">人工确认</p>
            <SheetTitle className="mt-1 flex items-center gap-2 text-xl font-bold text-navy-strong">
              <ShieldCheck className="size-5 text-sky" />
              费用操作确认
            </SheetTitle>
            <SheetDescription className="mt-2 text-sm leading-6 text-muted">
              此处只记录你的确认，不会自动支付，也不会向商家提交订单。
            </SheetDescription>
          </div>

          <dl className="mt-6 divide-y divide-border border-y border-border text-sm">
            <div className="flex justify-between gap-4 py-3">
              <dt className="text-muted">费用</dt>
              <dd className="font-bold">
                {formatCurrency(
                  action?.amount ?? plan.budgetSummary.estimatedTotal,
                  action?.currency ?? plan.budgetSummary.currency,
                )}
              </dd>
            </div>
            <div className="py-3">
              <dt className="text-muted">风险</dt>
              <dd className="mt-1 leading-6">{actionRisk}</dd>
            </div>
            <div className="flex justify-between gap-4 py-3">
              <dt className="text-muted">有效期</dt>
              <dd className="flex items-center gap-1 font-semibold">
                <Clock3 className="size-3.5" />
                {new Intl.DateTimeFormat("zh-CN", {
                  hour: "2-digit",
                  minute: "2-digit",
                }).format(action?.expiresAt ?? expiresAt)}
              </dd>
            </div>
            <div className="py-3">
              <dt className="text-muted">影响范围</dt>
              <dd className="mt-1">
                仅适用于当前方案版本 {plan.version} 的后续预订，不影响其他版本。
              </dd>
            </div>
          </dl>

          {!action && (
            <Button
              type="button"
              className="mt-5 w-full"
              disabled={proposeAction.isPending}
              onClick={() => proposeAction.mutate()}
            >
              {proposeAction.isPending ? (
                <LoaderCircle className="animate-spin" />
              ) : (
                <WalletCards />
              )}
              {proposeAction.isError ? "重新核对费用" : "继续核对费用"}
            </Button>
          )}

          {action?.status === "PENDING_CONFIRMATION" && (
            <div className="mt-5">
              <label className="flex items-start gap-3 rounded-md bg-warning-soft/55 p-3 text-sm">
                <input
                  type="checkbox"
                  checked={acknowledged}
                  onChange={(event) => setAcknowledged(event.target.checked)}
                  className="mt-0.5 size-4"
                />
                <span>我已核对费用、风险、有效期和影响范围。</span>
              </label>
              <div className="mt-4 grid grid-cols-2 gap-2">
                <Button
                  type="button"
                  variant="outline"
                  disabled={actionPending}
                  onClick={() => rejectAction.mutate()}
                >
                  <X />
                  {rejectAction.isError ? "重试拒绝" : "拒绝"}
                </Button>
                <Button
                  type="button"
                  disabled={!acknowledged || actionPending}
                  onClick={() => confirmAction.mutate()}
                >
                  {confirmAction.isPending ? (
                    <LoaderCircle className="animate-spin" />
                  ) : (
                    <ShieldCheck />
                  )}
                  {confirmAction.isError ? "重试确认" : "确认授权"}
                </Button>
              </div>
            </div>
          )}

          {action && action.status !== "PENDING_CONFIRMATION" && (
            <div className="mt-5 border-y border-border py-5 text-center">
              <p className="text-sm font-bold">
                {action.status === "CONFIRMED" ? "费用授权已确认" : "费用授权已拒绝"}
              </p>
              <p className="mt-1 text-xs text-muted">
                {action.status === "CONFIRMED"
                  ? "不会向商家提交订单，也不会自动支付。"
                  : "后续预订不会继续。"}
              </p>
            </div>
          )}

          {actionError && (
            <div role="alert" className="mt-4 border-l-2 border-danger bg-danger-soft/50 px-3 py-2">
              <p className="flex items-center gap-2 text-xs font-bold text-danger">
                <AlertCircle className="size-3.5" />
                操作未完成
              </p>
              <p className="mt-1 text-xs leading-5 text-muted">
                {mutationErrorMessage(actionError)}
              </p>
            </div>
          )}
        </SheetContent>
      </Sheet>
    </>
  );
}
