import { useState, type FormEvent } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import type { SaveUserPreferenceRequest } from "@api-generated/models/SaveUserPreferenceRequest";
import type { UserPreference } from "@api-generated/models/UserPreference";
import {
  BrainCircuit,
  Check,
  Pencil,
  Plus,
  Sparkles,
  Trash2,
  UserRound,
  X,
} from "lucide-react";
import { PageHeader } from "@/components/app/page-header";
import { EmptyState, ErrorState } from "@/components/states/state-panel";
import { Button } from "@/components/ui/button";
import { FormField, Input, Select } from "@/components/ui/form-control";
import {
  Sheet,
  SheetContent,
  SheetDescription,
  SheetTitle,
} from "@/components/ui/sheet";
import { StatusBadge } from "@/components/travel/status-badge";
import { cn } from "@/lib/utils";
import { useAppStore } from "@/stores/app-store";
import { useTripFlowStore } from "@/stores/trip-flow-store";
import { apiAdapter } from "@/api/client";

const categoryOptions = [
  "行程节奏",
  "兴趣主题",
  "饮食偏好",
  "住宿偏好",
  "交通偏好",
  "同行需求",
] as const;

const inferredCategoryLabels: Record<string, string> = {
  "feedback.plan": "方案偏好",
  "feedback.place": "地点偏好",
};

function categoryLabel(category: string) {
  return inferredCategoryLabels[category] ?? category;
}

function preferenceValue(value: string) {
  if (value.startsWith("like: ")) return `喜欢：${value.slice(6)}`;
  if (value.startsWith("dislike: ")) return `不喜欢：${value.slice(9)}`;
  return value;
}

function newKey(scope: string) {
  return `${scope}-${crypto.randomUUID()}`;
}

export function PreferencesPage() {
  const userId = useTripFlowStore((state) => state.userId);
  const addNotification = useAppStore((state) => state.addNotification);
  const queryClient = useQueryClient();
  const [category, setCategory] = useState<(typeof categoryOptions)[number]>(
    categoryOptions[0],
  );
  const [value, setValue] = useState("");
  const [editing, setEditing] = useState<UserPreference>();
  const [editCategory, setEditCategory] = useState("");
  const [editValue, setEditValue] = useState("");
  const [deleting, setDeleting] = useState<UserPreference>();
  const [actionError, setActionError] = useState("");

  const preferencesQuery = useQuery({
    queryKey: ["preferences", userId],
    queryFn: () => apiAdapter.preferences.list(userId),
  });

  const refresh = () =>
    queryClient.invalidateQueries({ queryKey: ["preferences", userId] });

  const createPreference = useMutation({
    mutationFn: (request: SaveUserPreferenceRequest) =>
      apiAdapter.preferences.create(userId, request, newKey("preference-create")),
    onSuccess: () => {
      setValue("");
      setActionError("");
      addNotification({ tone: "success", title: "偏好已保存" });
      void refresh();
    },
    onError: () => setActionError("偏好暂未保存，请检查内容后重试。"),
  });

  const updatePreference = useMutation({
    mutationFn: ({
      preferenceId,
      request,
    }: {
      preferenceId: string;
      request: SaveUserPreferenceRequest;
    }) =>
      apiAdapter.preferences.update(
        userId,
        preferenceId,
        request,
        newKey("preference-update"),
      ),
    onSuccess: () => {
      setEditing(undefined);
      setActionError("");
      addNotification({ tone: "success", title: "偏好已更新" });
      void refresh();
    },
    onError: () => setActionError("偏好暂未更新，请稍后重试。"),
  });

  const rejectPreference = useMutation({
    mutationFn: (preferenceId: string) =>
      apiAdapter.preferences.reject(userId, preferenceId, newKey("preference-reject")),
    onSuccess: () => {
      setActionError("");
      addNotification({
        tone: "success",
        title: "已拒绝这条推断",
        message: "后续规划不会继续采用该偏好。",
      });
      void refresh();
    },
    onError: () => setActionError("当前推断暂未拒绝，请重试。"),
  });

  const deletePreference = useMutation({
    mutationFn: (preferenceId: string) =>
      apiAdapter.preferences.delete(userId, preferenceId, newKey("preference-delete")),
    onSuccess: () => {
      setDeleting(undefined);
      setActionError("");
      addNotification({
        tone: "success",
        title: "偏好已删除",
        message: "相关记忆内容也已清理。",
      });
      void refresh();
    },
    onError: () => setActionError("偏好暂未删除，相关内容没有发生变化，请重试。"),
  });

  const items = preferencesQuery.data?.items ?? [];
  const activeItems = items.filter((item) => item.status === "ACTIVE");
  const rejectedItems = items.filter((item) => item.status === "REJECTED");

  const submitCreate = (event: FormEvent) => {
    event.preventDefault();
    const normalized = value.trim();
    if (!normalized) {
      setActionError("请输入具体偏好。");
      return;
    }
    createPreference.mutate({ category, value: normalized });
  };

  const beginEdit = (preference: UserPreference) => {
    setEditing(preference);
    setEditCategory(categoryLabel(preference.category));
    setEditValue(preferenceValue(preference.value));
    setActionError("");
  };

  const submitEdit = (event: FormEvent) => {
    event.preventDefault();
    if (!editing || !editCategory.trim() || !editValue.trim()) {
      setActionError("请完整填写偏好类别和内容。");
      return;
    }
    updatePreference.mutate({
      preferenceId: editing.preferenceId,
      request: {
        category: editCategory.trim(),
        value: editValue.trim(),
      },
    });
  };

  return (
    <>
      <PageHeader
        eyebrow="个性化"
        title="用户偏好与记忆"
        description="管理规划时采用的长期偏好。系统推断会明确标注，只有你接受的内容才会继续使用。"
        breadcrumbs={[{ label: "用户偏好与记忆" }]}
      />

      <section
        className="grid gap-6 border-y border-border bg-surface px-4 py-5 lg:grid-cols-[minmax(0,1fr)_minmax(300px,0.65fr)] lg:px-5"
        aria-labelledby="add-preference-title"
      >
        <form onSubmit={submitCreate}>
          <div className="mb-4 flex items-center gap-2">
            <Plus className="size-4 text-sky" />
            <h2 id="add-preference-title" className="text-base font-bold text-navy-strong">
              添加主动偏好
            </h2>
          </div>
          <div className="grid gap-3 sm:grid-cols-[180px_minmax(0,1fr)_auto] sm:items-end">
            <FormField label="类别" htmlFor="preference-category">
              <Select
                id="preference-category"
                value={category}
                onChange={(event) =>
                  setCategory(event.target.value as (typeof categoryOptions)[number])
                }
              >
                {categoryOptions.map((option) => (
                  <option key={option}>{option}</option>
                ))}
              </Select>
            </FormField>
            <FormField label="偏好内容" htmlFor="preference-value">
              <Input
                id="preference-value"
                value={value}
                maxLength={500}
                onChange={(event) => setValue(event.target.value)}
                placeholder="例如：每天保留午休时间"
              />
            </FormField>
            <Button type="submit" disabled={createPreference.isPending}>
              <Check />
              {createPreference.isPending ? "保存中" : "保存"}
            </Button>
          </div>
        </form>
        <div className="border-t border-border pt-5 lg:border-t-0 lg:border-l lg:pt-0 lg:pl-6">
          <p className="text-xs font-semibold text-muted">当前记录</p>
          <div className="mt-2 flex items-baseline gap-2">
            <span className="text-3xl font-bold text-navy-strong">{activeItems.length}</span>
            <span className="text-sm text-muted">条正在使用</span>
          </div>
          <p className="mt-2 text-xs leading-5 text-muted">
            删除或拒绝后，相关内容会从后续规划依据中移除。
          </p>
        </div>
      </section>

      {actionError && (
        <div
          role="alert"
          className="mt-4 flex items-center justify-between gap-3 border border-danger/25 bg-danger-soft/50 px-4 py-3 text-sm text-danger"
        >
          <span>{actionError}</span>
          <Button type="button" size="sm" variant="ghost" onClick={() => setActionError("")}>
            <X />
            关闭
          </Button>
        </div>
      )}

      <section className="mt-7" aria-labelledby="active-preferences-title">
        <div className="mb-3 flex items-center justify-between gap-3">
          <h2
            id="active-preferences-title"
            className="text-base font-bold text-navy-strong"
          >
            正在使用
          </h2>
          <span className="text-xs text-muted">
            主动设置与待确认推断会分别标注
          </span>
        </div>

        {preferencesQuery.isLoading ? (
          <div className="grid gap-3 md:grid-cols-2">
            <div className="space-y-2">
              <div className="h-28 animate-pulse rounded-md bg-surface-subtle" />
              <div className="h-28 animate-pulse rounded-md bg-surface-subtle" />
            </div>
            <div className="h-28 animate-pulse rounded-md bg-surface-subtle" />
          </div>
        ) : preferencesQuery.isError ? (
          <ErrorState
            compact
            title="暂时无法读取偏好"
            description="已保存的偏好没有发生变化，请稍后重试。"
            onAction={() => void preferencesQuery.refetch()}
          />
        ) : activeItems.length === 0 ? (
          <EmptyState
            compact
            icon={BrainCircuit}
            title="还没有长期偏好"
            description="可先添加旅行节奏、住宿或饮食偏好。"
          />
        ) : (
          <div className="grid gap-3 md:grid-cols-2">
            {activeItems.map((preference) => {
              const isEditing = editing?.preferenceId === preference.preferenceId;
              return (
                <article
                  key={preference.preferenceId}
                  className={cn(
                    "rounded-md border bg-surface p-4",
                    preference.source === "INFERRED"
                      ? "border-warning/35"
                      : "border-border",
                  )}
                >
                  {isEditing ? (
                    <form onSubmit={submitEdit} className="space-y-3">
                      <FormField label="类别" htmlFor={`category-${preference.preferenceId}`}>
                        <Input
                          id={`category-${preference.preferenceId}`}
                          value={editCategory}
                          maxLength={100}
                          onChange={(event) => setEditCategory(event.target.value)}
                        />
                      </FormField>
                      <FormField label="偏好内容" htmlFor={`value-${preference.preferenceId}`}>
                        <Input
                          id={`value-${preference.preferenceId}`}
                          value={editValue}
                          maxLength={500}
                          onChange={(event) => setEditValue(event.target.value)}
                        />
                      </FormField>
                      <div className="flex justify-end gap-2">
                        <Button
                          type="button"
                          size="sm"
                          variant="ghost"
                          onClick={() => setEditing(undefined)}
                        >
                          取消
                        </Button>
                        <Button type="submit" size="sm" disabled={updatePreference.isPending}>
                          保存修改
                        </Button>
                      </div>
                    </form>
                  ) : (
                    <>
                      <div className="flex items-start justify-between gap-3">
                        <div className="flex min-w-0 items-start gap-3">
                          <span
                            className={cn(
                              "flex size-9 shrink-0 items-center justify-center rounded-full",
                              preference.source === "INFERRED"
                                ? "bg-warning-soft text-warning"
                                : "bg-sky-soft text-sky",
                            )}
                          >
                            {preference.source === "INFERRED" ? (
                              <Sparkles className="size-4" />
                            ) : (
                              <UserRound className="size-4" />
                            )}
                          </span>
                          <div className="min-w-0">
                            <div className="flex flex-wrap items-center gap-2">
                              <h3 className="font-bold">
                                {categoryLabel(preference.category)}
                              </h3>
                              <span className="text-[11px] font-semibold text-muted">
                                {preference.source === "INFERRED"
                                  ? "根据反馈推断"
                                  : "由你设置"}
                              </span>
                            </div>
                            <p className="mt-2 break-words text-sm leading-6">
                              {preferenceValue(preference.value)}
                            </p>
                          </div>
                        </div>
                        <StatusBadge status={preference.status} />
                      </div>
                      <div className="mt-4 flex flex-wrap justify-end gap-2 border-t border-border pt-3">
                        {preference.source === "INFERRED" && (
                          <Button
                            type="button"
                            size="sm"
                            variant="outline"
                            disabled={
                              rejectPreference.isPending &&
                              rejectPreference.variables === preference.preferenceId
                            }
                            onClick={() => rejectPreference.mutate(preference.preferenceId)}
                          >
                            <X />
                            拒绝
                          </Button>
                        )}
                        <Button
                          type="button"
                          size="sm"
                          variant="ghost"
                          onClick={() => beginEdit(preference)}
                        >
                          <Pencil />
                          修改
                        </Button>
                        <Button
                          type="button"
                          size="icon"
                          variant="ghost"
                          aria-label={`删除${categoryLabel(preference.category)}`}
                          onClick={() => {
                            setDeleting(preference);
                            setActionError("");
                          }}
                        >
                          <Trash2 />
                        </Button>
                      </div>
                    </>
                  )}
                </article>
              );
            })}
          </div>
        )}
      </section>

      {rejectedItems.length > 0 && (
        <section className="mt-8 border-t border-border pt-6" aria-labelledby="rejected-title">
          <h2 id="rejected-title" className="text-base font-bold text-navy-strong">
            已拒绝的推断
          </h2>
          <div className="mt-3 divide-y divide-border border-y border-border">
            {rejectedItems.map((preference) => (
              <div
                key={preference.preferenceId}
                className="flex items-center gap-3 py-3 text-sm"
              >
                <span className="min-w-0 flex-1 truncate text-muted">
                  {categoryLabel(preference.category)}：{preferenceValue(preference.value)}
                </span>
                <StatusBadge status={preference.status} />
                <Button
                  type="button"
                  size="icon"
                  variant="ghost"
                  aria-label={`删除${categoryLabel(preference.category)}`}
                  onClick={() => setDeleting(preference)}
                >
                  <Trash2 />
                </Button>
              </div>
            ))}
          </div>
        </section>
      )}

      <Sheet open={Boolean(deleting)} onOpenChange={(open) => !open && setDeleting(undefined)}>
        <SheetContent side="bottom" className="mx-auto max-w-2xl p-6 sm:bottom-6 sm:rounded-md sm:border">
          <SheetTitle className="text-lg font-bold text-navy-strong">删除这条偏好？</SheetTitle>
          <SheetDescription className="mt-2 text-sm leading-6 text-muted">
            删除后，相关记忆内容也会被清理，后续规划不会再采用这条偏好。
          </SheetDescription>
          {deleting && (
            <div className="mt-4 border-y border-border py-3">
              <p className="text-xs font-semibold text-muted">
                {categoryLabel(deleting.category)}
              </p>
              <p className="mt-1 text-sm">{preferenceValue(deleting.value)}</p>
            </div>
          )}
          {actionError && <p className="mt-3 text-sm text-danger">{actionError}</p>}
          <div className="mt-5 flex justify-end gap-2">
            <Button type="button" variant="ghost" onClick={() => setDeleting(undefined)}>
              保留
            </Button>
            <Button
              type="button"
              variant="danger"
              disabled={deletePreference.isPending}
              onClick={() => deleting && deletePreference.mutate(deleting.preferenceId)}
            >
              <Trash2 />
              {deletePreference.isPending ? "删除中" : "确认删除"}
            </Button>
          </div>
        </SheetContent>
      </Sheet>
    </>
  );
}
