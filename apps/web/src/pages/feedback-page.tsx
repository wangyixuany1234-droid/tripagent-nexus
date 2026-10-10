import { useEffect, useMemo, useState, type FormEvent } from "react";
import { useMutation, useQueries, useQuery, useQueryClient } from "@tanstack/react-query";
import type { CreateFeedbackRequest } from "@api-generated/models/CreateFeedbackRequest";
import type { FeedbackSentiment } from "@api-generated/models/FeedbackSentiment";
import type { FeedbackTargetType } from "@api-generated/models/FeedbackTargetType";
import {
  Banknote,
  BookOpenCheck,
  Check,
  Heart,
  MapPin,
  MessageSquareText,
  Route,
  Send,
  ThumbsDown,
  ThumbsUp,
} from "lucide-react";
import { Link } from "react-router-dom";
import { apiAdapter } from "@/api/client";
import { PageHeader } from "@/components/app/page-header";
import { EmptyState, ErrorState } from "@/components/states/state-panel";
import { Button } from "@/components/ui/button";
import { FormField, Input, Select, Textarea } from "@/components/ui/form-control";
import { cn } from "@/lib/utils";
import { useAppStore } from "@/stores/app-store";
import { useTripFlowStore } from "@/stores/trip-flow-store";

const targetOptions: Array<{
  value: FeedbackTargetType;
  label: string;
  icon: typeof Route;
}> = [
  { value: "PLAN", label: "整体方案", icon: Route },
  { value: "PLACE", label: "单个地点", icon: MapPin },
  { value: "BUDGET", label: "预算结果", icon: Banknote },
  { value: "EVIDENCE", label: "推荐依据", icon: BookOpenCheck },
];

const targetLabels: Record<string, string> = {
  PLAN: "整体方案",
  PLACE: "单个地点",
  BUDGET: "预算结果",
  EVIDENCE: "推荐依据",
};

const reasonOptions = [
  "符合旅行节奏",
  "地点选择合适",
  "路线安排合理",
  "预算清晰",
  "依据充分",
  "时间安排不合理",
  "通勤时间过长",
  "费用超出预期",
  "缺少重要地点",
  "依据不足",
] as const;

function formatDate(date: Date) {
  return new Intl.DateTimeFormat("zh-CN", {
    month: "short",
    day: "numeric",
    hour: "2-digit",
    minute: "2-digit",
  }).format(date);
}

export function FeedbackPage() {
  const recentTripIds = useTripFlowStore((state) => state.recentTripIds);
  const activeTripId = useTripFlowStore((state) => state.activeTripId);
  const activePlanByTrip = useTripFlowStore((state) => state.activePlanByTrip);
  const addNotification = useAppStore((state) => state.addNotification);
  const queryClient = useQueryClient();
  const [tripId, setTripId] = useState(activeTripId ?? recentTripIds[0] ?? "");
  const [targetType, setTargetType] = useState<FeedbackTargetType>("PLAN");
  const [targetName, setTargetName] = useState("");
  const [sentiment, setSentiment] = useState<FeedbackSentiment>("LIKE");
  const [tags, setTags] = useState<Set<string>>(new Set());
  const [comment, setComment] = useState("");
  const [formError, setFormError] = useState("");

  useEffect(() => {
    if (!tripId && (activeTripId || recentTripIds[0])) {
      setTripId(activeTripId ?? recentTripIds[0] ?? "");
    }
  }, [activeTripId, recentTripIds, tripId]);

  const tripQueries = useQueries({
    queries: recentTripIds.map((id) => ({
      queryKey: ["trips", id],
      queryFn: () => apiAdapter.trips.get(id),
      retry: false,
      staleTime: 30_000,
    })),
  });
  const tripOptions = useMemo(
    () => tripQueries.flatMap((query) => (query.data ? [query.data] : [])),
    [tripQueries],
  );
  const planId = tripId ? activePlanByTrip[tripId] : undefined;

  const feedbackQuery = useQuery({
    queryKey: ["feedback", tripId],
    queryFn: () => apiAdapter.feedback.list(tripId),
    enabled: Boolean(tripId),
  });

  const submitFeedback = useMutation({
    mutationFn: (request: CreateFeedbackRequest) =>
      apiAdapter.feedback.create(request, `feedback-create-${crypto.randomUUID()}`),
    onSuccess: (feedback) => {
      setTags(new Set());
      setComment("");
      setTargetName("");
      setFormError("");
      addNotification({
        tone: "success",
        title: "反馈已提交",
        message: feedback.memoryAccepted
          ? "这条反馈会作为可管理的偏好建议。"
          : "这条反馈仅用于改进当前内容。",
      });
      void queryClient.invalidateQueries({ queryKey: ["feedback", tripId] });
      if (feedback.memoryAccepted) {
        void queryClient.invalidateQueries({ queryKey: ["preferences"] });
      }
    },
    onError: () => setFormError("反馈暂未提交，已填写的内容仍会保留，请重试。"),
  });

  const toggleTag = (tag: string) => {
    setTags((current) => {
      const next = new Set(current);
      if (next.has(tag)) next.delete(tag);
      else next.add(tag);
      return next;
    });
  };

  const submit = (event: FormEvent) => {
    event.preventDefault();
    if (!tripId) {
      setFormError("请先选择一个行程。");
      return;
    }
    if (targetType === "PLAN" && !planId) {
      setFormError("请先生成并打开一个方案，再提交整体方案反馈。");
      return;
    }
    if (targetType !== "PLAN" && !targetName.trim()) {
      setFormError("请填写反馈对象。");
      return;
    }
    if (tags.size === 0) {
      setFormError("请至少选择一个原因。");
      return;
    }

    submitFeedback.mutate({
      tripId,
      planId: targetType === "PLAN" ? planId : undefined,
      targetType,
      targetId: targetType === "PLAN" ? undefined : targetName.trim(),
      sentiment,
      rating: sentiment === "LIKE" ? 5 : 2,
      comment: comment.trim(),
      tags,
    });
  };

  if (recentTripIds.length === 0) {
    return (
      <>
        <PageHeader title="反馈中心" breadcrumbs={[{ label: "反馈中心" }]} />
        <EmptyState
          icon={MessageSquareText}
          title="还没有可反馈的行程"
          description="创建旅行并生成方案后，可以对方案、地点、预算和推荐依据提交反馈。"
        />
        <div className="mt-4 flex justify-center">
          <Button asChild>
            <Link to="/trips/new">创建旅行</Link>
          </Button>
        </div>
      </>
    );
  }

  return (
    <>
      <PageHeader
        eyebrow="体验改进"
        title="反馈中心"
        description="评价方案、地点、预算或推荐依据。符合规则的偏好建议会进入可查看、可拒绝的偏好列表。"
        breadcrumbs={[{ label: "反馈中心" }]}
      />

      <div className="grid gap-7 xl:grid-cols-[minmax(0,0.9fr)_minmax(420px,1.1fr)]">
        <form
          onSubmit={submit}
          className="rounded-md border border-border bg-surface p-5 shadow-[var(--shadow)]"
        >
          <div className="flex items-center gap-2">
            <Heart className="size-4 text-coral" />
            <h2 className="text-base font-bold text-navy-strong">提交反馈</h2>
          </div>

          <div className="mt-5">
            <FormField label="选择行程" htmlFor="feedback-trip">
              <Select
                id="feedback-trip"
                value={tripId}
                onChange={(event) => setTripId(event.target.value)}
              >
                {tripOptions.map((trip) => (
                  <option key={trip.tripId} value={trip.tripId}>
                    {trip.title}
                  </option>
                ))}
                {tripOptions.length === 0 && tripId && (
                  <option value={tripId}>当前行程</option>
                )}
              </Select>
            </FormField>
          </div>

          <fieldset className="mt-5">
            <legend className="mb-2 text-sm font-semibold">反馈对象</legend>
            <div className="grid grid-cols-2 gap-2">
              {targetOptions.map((option) => (
                <button
                  key={option.value}
                  type="button"
                  onClick={() => {
                    setTargetType(option.value);
                    setFormError("");
                  }}
                  className={cn(
                    "flex min-h-11 items-center gap-2 rounded-md border px-3 text-sm font-semibold",
                    targetType === option.value
                      ? "border-sky bg-sky-soft text-navy-strong"
                      : "border-border hover:bg-surface-subtle",
                  )}
                  aria-pressed={targetType === option.value}
                >
                  <option.icon className="size-4" />
                  {option.label}
                </button>
              ))}
            </div>
          </fieldset>

          {targetType !== "PLAN" && (
            <div className="mt-5">
              <FormField
                label={
                  targetType === "PLACE"
                    ? "地点名称"
                    : targetType === "BUDGET"
                      ? "预算项目"
                      : "依据标题"
                }
                htmlFor="feedback-target"
              >
                <Input
                  id="feedback-target"
                  value={targetName}
                  maxLength={200}
                  onChange={(event) => setTargetName(event.target.value)}
                  placeholder={
                    targetType === "PLACE"
                      ? "例如：故宫博物院"
                      : targetType === "BUDGET"
                        ? "例如：住宿费用"
                        : "例如：开放时间说明"
                  }
                />
              </FormField>
            </div>
          )}

          <fieldset className="mt-5">
            <legend className="mb-2 text-sm font-semibold">你的感受</legend>
            <div className="grid grid-cols-2 gap-2">
              <button
                type="button"
                aria-pressed={sentiment === "LIKE"}
                onClick={() => setSentiment("LIKE")}
                className={cn(
                  "flex h-11 items-center justify-center gap-2 rounded-md border text-sm font-semibold",
                  sentiment === "LIKE"
                    ? "border-success bg-success-soft text-success"
                    : "border-border hover:bg-surface-subtle",
                )}
              >
                <ThumbsUp className="size-4" />
                喜欢
              </button>
              <button
                type="button"
                aria-pressed={sentiment === "DISLIKE"}
                onClick={() => setSentiment("DISLIKE")}
                className={cn(
                  "flex h-11 items-center justify-center gap-2 rounded-md border text-sm font-semibold",
                  sentiment === "DISLIKE"
                    ? "border-danger bg-danger-soft text-danger"
                    : "border-border hover:bg-surface-subtle",
                )}
              >
                <ThumbsDown className="size-4" />
                不喜欢
              </button>
            </div>
          </fieldset>

          <fieldset className="mt-5">
            <legend className="mb-2 text-sm font-semibold">原因</legend>
            <div className="flex flex-wrap gap-2">
              {reasonOptions.map((reason) => (
                <button
                  key={reason}
                  type="button"
                  aria-pressed={tags.has(reason)}
                  onClick={() => toggleTag(reason)}
                  className={cn(
                    "min-h-8 rounded-full border px-3 text-xs font-semibold transition-colors",
                    tags.has(reason)
                      ? "border-sky bg-sky-soft text-navy-strong"
                      : "border-border text-muted hover:bg-surface-subtle",
                  )}
                >
                  {tags.has(reason) && <Check className="mr-1 inline size-3" />}
                  {reason}
                </button>
              ))}
            </div>
          </fieldset>

          <div className="mt-5">
            <FormField label="补充说明" htmlFor="feedback-comment" optional>
              <Textarea
                id="feedback-comment"
                value={comment}
                maxLength={5000}
                onChange={(event) => setComment(event.target.value)}
                placeholder="补充说明具体满意或需要调整的部分"
              />
            </FormField>
          </div>

          {formError && (
            <p className="mt-4 text-sm text-danger" role="alert">
              {formError}
            </p>
          )}

          <div className="mt-5 flex justify-end">
            <Button type="submit" disabled={submitFeedback.isPending}>
              <Send />
              {submitFeedback.isPending ? "提交中" : "提交反馈"}
            </Button>
          </div>
        </form>

        <section aria-labelledby="feedback-history-title">
          <div className="mb-3 flex items-center justify-between gap-3">
            <h2
              id="feedback-history-title"
              className="text-base font-bold text-navy-strong"
            >
              反馈记录
            </h2>
            <span className="text-xs text-muted">
              {feedbackQuery.data?.items.length ?? 0} 条
            </span>
          </div>

          {feedbackQuery.isLoading ? (
            <div className="space-y-3">
              <div className="h-28 animate-pulse rounded-md bg-surface-subtle" />
              <div className="h-28 animate-pulse rounded-md bg-surface-subtle" />
            </div>
          ) : feedbackQuery.isError ? (
            <ErrorState
              compact
              title="暂时无法读取反馈"
              description="已提交的内容不会丢失，请稍后重试。"
              onAction={() => void feedbackQuery.refetch()}
            />
          ) : feedbackQuery.data?.items.length === 0 ? (
            <EmptyState
              compact
              icon={MessageSquareText}
              title="还没有反馈"
              description="提交后可在这里查看反馈对象、原因和处理范围。"
            />
          ) : (
            <div className="space-y-3">
              {feedbackQuery.data?.items.map((feedback) => (
                <article
                  key={feedback.feedbackId}
                  className="rounded-md border border-border bg-surface p-4"
                >
                  <div className="flex items-start justify-between gap-3">
                    <div>
                      <p className="text-xs font-semibold text-muted">
                        {targetLabels[feedback.targetType ?? ""] ?? "旅行体验"}
                      </p>
                      <h3 className="mt-1 font-bold">
                        {feedback.targetId || "当前旅行方案"}
                      </h3>
                    </div>
                    <span
                      className={cn(
                        "flex size-8 shrink-0 items-center justify-center rounded-full",
                        feedback.sentiment === "DISLIKE"
                          ? "bg-danger-soft text-danger"
                          : "bg-success-soft text-success",
                      )}
                      title={feedback.sentiment === "DISLIKE" ? "不喜欢" : "喜欢"}
                    >
                      {feedback.sentiment === "DISLIKE" ? (
                        <ThumbsDown className="size-4" />
                      ) : (
                        <ThumbsUp className="size-4" />
                      )}
                    </span>
                  </div>
                  <div className="mt-3 flex flex-wrap gap-1.5">
                    {[...feedback.tags].map((tag) => (
                      <span
                        key={tag}
                        className="rounded-full bg-surface-subtle px-2 py-1 text-[11px] font-semibold text-muted"
                      >
                        {tag}
                      </span>
                    ))}
                  </div>
                  {feedback.comment && (
                    <p className="mt-3 text-sm leading-6">{feedback.comment}</p>
                  )}
                  <div className="mt-3 flex flex-wrap items-center justify-between gap-2 border-t border-border pt-3 text-xs text-muted">
                    <time>{formatDate(feedback.createdAt)}</time>
                    <span>
                      {feedback.memoryAccepted
                        ? "已形成可管理的偏好建议"
                        : "仅用于改进当前内容"}
                    </span>
                  </div>
                </article>
              ))}
            </div>
          )}
        </section>
      </div>
    </>
  );
}
