import { useEffect, useMemo, useRef, useState } from "react";
import {
  Controller,
  type FieldPath,
  useFieldArray,
  useForm,
  useWatch,
} from "react-hook-form";
import type { ZodIssue } from "zod";
import {
  ArrowLeft,
  ArrowRight,
  CalendarRange,
  Check,
  CheckCircle2,
  MapPin,
  Save,
  Sparkles,
  UserRound,
  Users,
  WalletCards,
} from "lucide-react";
import { useNavigate } from "react-router-dom";
import { ApiClientError } from "@/api/errors";
import { apiAdapter } from "@/api/client";
import { PageHeader } from "@/components/app/page-header";
import { Button } from "@/components/ui/button";
import { FormField, Input, Select, Textarea } from "@/components/ui/form-control";
import { Stepper } from "@/components/travel/stepper";
import { applyServerFieldErrors } from "@/lib/form-errors";
import { cn, formatCurrency } from "@/lib/utils";
import {
  toRequirementRequest,
  useTripFlowStore,
  type TripWizardValues,
} from "@/stores/trip-flow-store";
import { useAppStore } from "@/stores/app-store";
import { tripWizardSchema, validateWizardStep } from "./trip-wizard-schema";

const steps = [
  "路线",
  "日期人数",
  "预算",
  "兴趣",
  "节奏",
  "偏好",
  "同行人员",
  "确认",
] as const;

const interests = [
  "历史文化",
  "自然风景",
  "本地美食",
  "城市漫步",
  "亲子",
  "博物馆",
  "夜生活",
  "购物",
] as const;

const paceOptions = [
  { value: "RELAXED", label: "松弛", description: "每天 2 至 3 个核心安排" },
  { value: "BALANCED", label: "均衡", description: "体验密度与休息兼顾" },
  { value: "INTENSIVE", label: "紧凑", description: "尽可能覆盖更多地点" },
] as const;

function fieldError(
  errors: ReturnType<typeof useForm<TripWizardValues>>["formState"]["errors"],
  path: string,
) {
  const value = path.split(".").reduce<unknown>((current, key) => {
    if (current && typeof current === "object") {
      return (current as Record<string, unknown>)[key];
    }
    return undefined;
  }, errors);
  return value && typeof value === "object" && "message" in value
    ? String((value as { message?: unknown }).message ?? "")
    : undefined;
}

function linesToTags(value: string) {
  return [
    ...new Set(
      value
        .split(/[\n,，]/)
        .map((item) => item.trim())
        .filter(Boolean),
    ),
  ];
}

export function TripWizardPage() {
  const navigate = useNavigate();
  const persistedDraft = useTripFlowStore((state) => state.draft);
  const saveDraft = useTripFlowStore((state) => state.saveDraft);
  const resetDraft = useTripFlowStore((state) => state.resetDraft);
  const rememberTrip = useTripFlowStore((state) => state.rememberTrip);
  const addNotification = useAppStore((state) => state.addNotification);
  const [step, setStep] = useState(Math.min(7, persistedDraft.currentStep));
  const [submitError, setSubmitError] = useState<string>();
  const [savedAt, setSavedAt] = useState(new Date(persistedDraft.updatedAt));
  const submittingRef = useRef(false);

  const form = useForm<TripWizardValues>({
    defaultValues: persistedDraft.values,
    mode: "onBlur",
  });
  const { fields: travelerFields, append, remove } = useFieldArray({
    control: form.control,
    name: "requirement.travelers",
  });
  const travelerCount = useWatch({ control: form.control, name: "travelerCount" });
  const selectedInterests = useWatch({
    control: form.control,
    name: "requirement.interests",
  });

  useEffect(() => {
    const currentCount = travelerFields.length;
    if (travelerCount > currentCount) {
      for (let index = currentCount; index < travelerCount; index += 1) {
        append({
          travelerId: crypto.randomUUID(),
          displayName: "",
          type: "ADULT",
          accessibilityNeeds: [],
        });
      }
    } else if (travelerCount < currentCount) {
      for (let index = currentCount - 1; index >= travelerCount; index -= 1) {
        remove(index);
      }
    }
  }, [append, remove, travelerCount, travelerFields.length]);

  useEffect(() => {
    let timeout: number | undefined;
    const subscription = form.watch((values) => {
      window.clearTimeout(timeout);
      timeout = window.setTimeout(() => {
        saveDraft(values as TripWizardValues, step, persistedDraft.tripId);
        setSavedAt(new Date());
      }, 350);
    });
    return () => {
      window.clearTimeout(timeout);
      subscription.unsubscribe();
    };
  }, [form, persistedDraft.tripId, saveDraft, step]);

  const stepperItems = useMemo(
    () =>
      steps.map((label, index) => ({
        id: label,
        label,
        state:
          index < step
            ? ("complete" as const)
            : index === step
              ? ("current" as const)
              : ("upcoming" as const),
      })),
    [step],
  );

  function applyIssues(issues: ZodIssue[]) {
    issues.forEach((issue) => {
      form.setError(issue.path.join(".") as FieldPath<TripWizardValues>, {
        type: "validate",
        message: issue.message,
      });
    });
  }

  function goToStep(nextStep: number) {
    const result = validateWizardStep(step, form.getValues());
    form.clearErrors();
    if (!result.success) {
      applyIssues(result.error.issues);
      return;
    }
    setStep(nextStep);
    saveDraft(form.getValues(), nextStep, persistedDraft.tripId);
    window.scrollTo({ top: 0, behavior: "smooth" });
  }

  async function submit(values: TripWizardValues) {
    if (submittingRef.current) {
      return;
    }
    form.clearErrors();
    setSubmitError(undefined);
    const validated = tripWizardSchema.safeParse(values);
    if (!validated.success) {
      applyIssues(validated.error.issues);
      return;
    }

    submittingRef.current = true;
    let tripId = persistedDraft.tripId;
    try {
      if (!tripId) {
        const trip = await apiAdapter.trips.create(
          validated.data.identity,
          persistedDraft.createIdempotencyKey,
        );
        tripId = trip.tripId;
        saveDraft(validated.data, step, tripId);
        rememberTrip(tripId);
      }
      await apiAdapter.trips.saveRequirement(
        tripId,
        toRequirementRequest(validated.data),
        persistedDraft.requirementIdempotencyKey,
      );
      rememberTrip(tripId);
      addNotification({
        tone: "success",
        title: "旅行需求已保存",
        message: "可以继续补充订单资料。",
      });
      resetDraft();
      navigate(`/trips/${tripId}/documents`);
    } catch (error) {
      const prefix = tripId ? "requirement" : "identity";
      const mapped = applyServerFieldErrors(error, form.setError, prefix);
      if (!mapped) {
        setSubmitError(
          error instanceof ApiClientError ? error.message : "提交失败，请稍后重试。",
        );
      }
    } finally {
      submittingRef.current = false;
    }
  }

  const errors = form.formState.errors;
  const values = form.watch();

  return (
    <>
      <PageHeader
        eyebrow="行程需求"
        title="创建旅行"
        description="填写内容会自动保存，提交前仍可随时返回修改。"
        breadcrumbs={[{ label: "旅行项目", to: "/trips" }, { label: "创建旅行" }]}
        actions={
          <div className="flex items-center gap-2 text-xs text-muted">
            <Save className="size-3.5 text-success" />
            已自动保存 {savedAt.toLocaleTimeString("zh-CN", { hour: "2-digit", minute: "2-digit" })}
          </div>
        }
      />

      <div className="border-y border-border py-5">
        <Stepper items={stepperItems} />
      </div>

      <form
        onSubmit={
          step === steps.length - 1
            ? form.handleSubmit(submit)
            : (event) => event.preventDefault()
        }
        className="mx-auto mt-8 max-w-3xl"
        noValidate
      >
        <div className="mb-7 flex items-center gap-3">
          <span className="flex size-10 items-center justify-center rounded-md bg-sky-soft text-sky">
            {step + 1}
          </span>
          <div>
            <p className="text-xs font-semibold text-muted">
              第 {step + 1} 步，共 {steps.length} 步
            </p>
            <h2 className="mt-0.5 text-xl font-bold text-navy-strong">{steps[step]}</h2>
          </div>
        </div>

        {step === 0 && (
          <div className="grid gap-5">
            <FormField
              label="旅行名称"
              htmlFor="trip-title"
              error={fieldError(errors, "identity.title")}
            >
              <Input
                id="trip-title"
                placeholder="例如：北京三日文化漫游"
                autoFocus
                {...form.register("identity.title")}
              />
            </FormField>
            <div className="grid gap-5 sm:grid-cols-2">
              <FormField
                label="出发地"
                htmlFor="origin"
                error={fieldError(errors, "requirement.origin.name")}
              >
                <Input
                  id="origin"
                  placeholder="城市或交通枢纽"
                  {...form.register("requirement.origin.name")}
                />
              </FormField>
              <FormField
                label="目的地"
                htmlFor="destination"
                error={fieldError(errors, "requirement.destinations.0.name")}
              >
                <Input
                  id="destination"
                  placeholder="本次主要目的地"
                  {...form.register("requirement.destinations.0.name")}
                />
              </FormField>
            </div>
            <p className="flex items-center gap-2 text-xs text-muted">
              <MapPin className="size-3.5" />
              时区：{values.identity.timeZone}
            </p>
          </div>
        )}

        {step === 1 && (
          <div className="grid gap-5 sm:grid-cols-2">
            <FormField
              label="出发日期"
              htmlFor="start-date"
              error={fieldError(errors, "requirement.startDate")}
            >
              <Input
                id="start-date"
                type="date"
                min={new Date().toISOString().slice(0, 10)}
                {...form.register("requirement.startDate")}
              />
            </FormField>
            <FormField
              label="返程日期"
              htmlFor="end-date"
              error={fieldError(errors, "requirement.endDate")}
            >
              <Input
                id="end-date"
                type="date"
                min={values.requirement.startDate || new Date().toISOString().slice(0, 10)}
                {...form.register("requirement.endDate")}
              />
            </FormField>
            <FormField
              label="旅行人数"
              htmlFor="traveler-count"
              error={fieldError(errors, "travelerCount")}
            >
              <Input
                id="traveler-count"
                type="number"
                min={1}
                max={20}
                {...form.register("travelerCount", { valueAsNumber: true })}
              />
            </FormField>
            <div className="flex items-end pb-2 text-sm text-muted">
              <CalendarRange className="mr-2 size-4 text-sky" />
              日期与人数会在提交前再次校验
            </div>
          </div>
        )}

        {step === 2 && (
          <div className="grid gap-5 sm:grid-cols-[minmax(0,1fr)_180px]">
            <FormField
              label="总预算"
              htmlFor="total-budget"
              error={fieldError(errors, "requirement.totalBudget")}
            >
              <Input
                id="total-budget"
                type="number"
                min={0}
                step="0.01"
                {...form.register("requirement.totalBudget", { valueAsNumber: true })}
              />
            </FormField>
            <FormField
              label="币种"
              htmlFor="currency"
              error={fieldError(errors, "requirement.currency")}
            >
              <Select id="currency" {...form.register("requirement.currency")}>
                <option value="CNY">CNY 人民币</option>
                <option value="USD">USD 美元</option>
                <option value="EUR">EUR 欧元</option>
                <option value="JPY">JPY 日元</option>
              </Select>
            </FormField>
            <div className="flex items-center gap-3 border-y border-border py-4 sm:col-span-2">
              <WalletCards className="size-5 text-coral" />
              <p className="text-sm">
                当前预算：
                <strong className="ml-1 text-navy-strong">
                  {formatCurrency(
                    Number.isFinite(values.requirement.totalBudget)
                      ? values.requirement.totalBudget
                      : 0,
                    values.requirement.currency,
                  )}
                </strong>
              </p>
            </div>
          </div>
        )}

        {step === 3 && (
          <Controller
            control={form.control}
            name="requirement.interests"
            render={({ field }) => (
              <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
                {interests.map((interest) => {
                  const active = field.value.includes(interest);
                  return (
                    <button
                      key={interest}
                      type="button"
                      aria-pressed={active}
                      onClick={() =>
                        field.onChange(
                          active
                            ? field.value.filter((item) => item !== interest)
                            : [...field.value, interest],
                        )
                      }
                      className={cn(
                        "flex min-h-12 items-center justify-center gap-2 rounded-md border px-3 text-sm font-semibold",
                        active
                          ? "border-sky bg-sky-soft text-navy-strong"
                          : "border-border bg-surface text-muted hover:bg-surface-subtle",
                      )}
                    >
                      {active && <Check className="size-4" />}
                      {interest}
                    </button>
                  );
                })}
              </div>
            )}
          />
        )}

        {step === 4 && (
          <Controller
            control={form.control}
            name="requirement.pace"
            render={({ field }) => (
              <div className="grid gap-3 sm:grid-cols-3">
                {paceOptions.map((option) => (
                  <button
                    key={option.value}
                    type="button"
                    aria-pressed={field.value === option.value}
                    onClick={() => field.onChange(option.value)}
                    className={cn(
                      "min-h-28 rounded-md border p-4 text-left",
                      field.value === option.value
                        ? "border-sky bg-sky-soft"
                        : "border-border bg-surface hover:bg-surface-subtle",
                    )}
                  >
                    <span className="text-sm font-bold">{option.label}</span>
                    <span className="mt-2 block text-xs leading-5 text-muted">
                      {option.description}
                    </span>
                  </button>
                ))}
              </div>
            )}
          />
        )}

        {step === 5 && (
          <div className="grid gap-5">
            <Controller
              control={form.control}
              name="requirement.mustVisit"
              render={({ field }) => (
                <FormField label="必去地点" htmlFor="must-visit" optional>
                  <Textarea
                    id="must-visit"
                    placeholder={"每行一个地点\n例如：故宫博物院"}
                    value={field.value.join("\n")}
                    onChange={(event) => field.onChange(linesToTags(event.target.value))}
                  />
                </FormField>
              )}
            />
            <Controller
              control={form.control}
              name="requirement.avoid"
              render={({ field }) => (
                <FormField label="希望避开" htmlFor="avoid" optional>
                  <Textarea
                    id="avoid"
                    placeholder={"每行一项\n例如：凌晨出发"}
                    value={field.value.join("\n")}
                    onChange={(event) => field.onChange(linesToTags(event.target.value))}
                  />
                </FormField>
              )}
            />
            <FormField label="其他约束" htmlFor="constraints" optional>
              <Textarea
                id="constraints"
                placeholder="饮食、休息、交通或其他安排"
                {...form.register("requirement.freeTextConstraints")}
              />
            </FormField>
          </div>
        )}

        {step === 6 && (
          <div className="space-y-5">
            {travelerFields.map((traveler, index) => (
              <div
                key={traveler.id}
                className="grid gap-4 border-b border-border pb-5 last:border-b-0 sm:grid-cols-[minmax(0,1fr)_170px]"
              >
                <FormField
                  label={`同行人员 ${index + 1}`}
                  htmlFor={`traveler-${index}`}
                  error={fieldError(errors, `requirement.travelers.${index}.displayName`)}
                >
                  <Input
                    id={`traveler-${index}`}
                    placeholder="称呼"
                    {...form.register(`requirement.travelers.${index}.displayName`)}
                  />
                </FormField>
                <FormField label="类型" htmlFor={`traveler-type-${index}`}>
                  <Select
                    id={`traveler-type-${index}`}
                    {...form.register(`requirement.travelers.${index}.type`)}
                  >
                    <option value="ADULT">成人</option>
                    <option value="CHILD">儿童</option>
                    <option value="INFANT">婴幼儿</option>
                    <option value="SENIOR">长者</option>
                  </Select>
                </FormField>
                <Controller
                  control={form.control}
                  name={`requirement.travelers.${index}.accessibilityNeeds`}
                  render={({ field }) => (
                    <FormField
                      label="特殊需求"
                      htmlFor={`traveler-needs-${index}`}
                      optional
                    >
                      <Input
                        id={`traveler-needs-${index}`}
                        placeholder="用逗号分隔，如：轮椅通道，低盐饮食"
                        value={(field.value ?? []).join("，")}
                        onChange={(event) => field.onChange(linesToTags(event.target.value))}
                      />
                    </FormField>
                  )}
                />
              </div>
            ))}
          </div>
        )}

        {step === 7 && (
          <div className="space-y-5">
            <div className="grid divide-y divide-border border-y border-border sm:grid-cols-2 sm:divide-x sm:divide-y-0">
              <div className="px-3 py-4">
                <p className="text-xs text-muted">路线</p>
                <p className="mt-1 text-sm font-bold">
                  {values.requirement.origin.name} →{" "}
                  {values.requirement.destinations.map((item) => item.name).join("、")}
                </p>
              </div>
              <div className="px-3 py-4">
                <p className="text-xs text-muted">日期</p>
                <p className="mt-1 text-sm font-bold">
                  {values.requirement.startDate} 至 {values.requirement.endDate}
                </p>
              </div>
              <div className="px-3 py-4">
                <p className="text-xs text-muted">预算</p>
                <p className="mt-1 text-sm font-bold">
                  {formatCurrency(
                    Number.isFinite(values.requirement.totalBudget)
                      ? values.requirement.totalBudget
                      : 0,
                    values.requirement.currency,
                  )}
                </p>
              </div>
              <div className="px-3 py-4">
                <p className="text-xs text-muted">同行</p>
                <p className="mt-1 text-sm font-bold">{values.travelerCount} 人</p>
              </div>
            </div>
            <div>
              <p className="text-xs font-semibold text-muted">兴趣</p>
              <div className="mt-2 flex flex-wrap gap-2">
                {values.requirement.interests.length > 0 ? (
                  values.requirement.interests.map((item) => (
                    <span
                      key={item}
                      className="rounded-full bg-sky-soft px-2.5 py-1 text-xs font-semibold text-sky"
                    >
                      {item}
                    </span>
                  ))
                ) : (
                  <span className="text-sm text-muted">未指定</span>
                )}
              </div>
            </div>
            <p className="flex items-center gap-2 text-xs text-muted">
              <CheckCircle2 className="size-4 text-success" />
              提交后将创建真实旅行记录，并保存规范化需求。
            </p>
          </div>
        )}

        {submitError && (
          <div role="alert" className="mt-5 rounded-md bg-danger-soft px-3 py-2 text-sm text-danger">
            {submitError}
          </div>
        )}

        <div className="mt-9 flex items-center justify-between border-t border-border pt-5">
          <Button
            type="button"
            variant="ghost"
            disabled={step === 0 || form.formState.isSubmitting}
            onClick={() => {
              const previous = Math.max(0, step - 1);
              setStep(previous);
              saveDraft(form.getValues(), previous, persistedDraft.tripId);
            }}
          >
            <ArrowLeft />
            上一步
          </Button>
          {step < steps.length - 1 ? (
            <Button type="button" onClick={() => goToStep(step + 1)}>
              下一步
              <ArrowRight />
            </Button>
          ) : (
            <Button
              type="button"
              variant="coral"
              disabled={form.formState.isSubmitting}
              onClick={() => void form.handleSubmit(submit)()}
            >
              {form.formState.isSubmitting ? (
                "正在提交"
              ) : (
                <>
                  <Sparkles />
                  创建旅行
                </>
              )}
            </Button>
          )}
        </div>

        <div className="mt-4 flex items-center justify-between text-xs text-muted">
          <span className="flex items-center gap-1.5">
            <UserRound className="size-3.5" />
            {values.travelerCount} 位旅行者
          </span>
          <button
            type="button"
            onClick={() => {
              resetDraft();
              const nextDraft = useTripFlowStore.getState().draft;
              form.reset(nextDraft.values);
              setStep(0);
              setSavedAt(new Date(nextDraft.updatedAt));
            }}
            className="hover:text-foreground"
          >
            清除草稿
          </button>
        </div>
      </form>
    </>
  );
}
