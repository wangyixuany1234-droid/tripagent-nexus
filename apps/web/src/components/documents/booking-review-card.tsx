import { useMemo, useRef, useState } from "react";
import { useMutation } from "@tanstack/react-query";
import { BookingField, type BookingField as BookingFieldValue } from "@api-generated/models/BookingField";
import type { BookingFieldCorrection } from "@api-generated/models/BookingFieldCorrection";
import type { ExtractedFieldEvidence } from "@api-generated/models/ExtractedFieldEvidence";
import type { ParsedBooking } from "@api-generated/models/ParsedBooking";
import {
  Check,
  FileSearch,
  LocateFixed,
  PencilLine,
  TriangleAlert,
  X,
} from "lucide-react";
import { apiAdapter } from "@/api/client";
import { Button } from "@/components/ui/button";
import { FormField, Input, Textarea } from "@/components/ui/form-control";
import { StatusBadge } from "@/components/travel/status-badge";
import { cn, formatCurrency } from "@/lib/utils";

const fieldLabels: Record<BookingFieldValue, string> = {
  type: "订单类型",
  provider: "供应商",
  origin: "出发地",
  destination: "目的地",
  hotelName: "酒店名称",
  hotelAddress: "酒店地址",
  transportNumber: "班次 / 航班号",
  startTime: "开始时间",
  endTime: "结束时间",
  travelerCount: "人数",
  amount: "金额",
  currency: "币种",
  confirmationNumber: "确认号",
};

const bookingTypeLabels: Record<ParsedBooking["type"], string> = {
  FLIGHT: "机票",
  HOTEL: "酒店",
  TICKET: "门票",
  TRAIN: "火车票",
  OTHER: "其他",
};

function valueFor(booking: ParsedBooking, field: BookingFieldValue) {
  const value = booking[field as keyof ParsedBooking];
  if (value instanceof Date) {
    return value.toISOString();
  }
  return value == null ? "" : String(value);
}

function displayValueFor(booking: ParsedBooking, field: BookingFieldValue) {
  const value = booking[field as keyof ParsedBooking];
  if (value instanceof Date) {
    return value.toLocaleString("zh-CN");
  }
  if (field === BookingField.Amount && typeof value === "number" && booking.currency) {
    return formatCurrency(value, booking.currency);
  }
  return value == null || value === "" ? "未提取" : String(value);
}

function confidenceStyle(confidence: number) {
  if (confidence < 0.75) {
    return "bg-danger-soft text-danger";
  }
  if (confidence < 0.9) {
    return "bg-warning-soft text-warning";
  }
  return "bg-success-soft text-success";
}

function EvidenceLocator({
  evidence,
  previewUrl,
}: {
  evidence: ExtractedFieldEvidence;
  previewUrl?: string;
}) {
  return (
    <div className="mt-2 overflow-hidden rounded-md border border-border bg-surface-subtle">
      {previewUrl && evidence.region ? (
        <div className="relative aspect-[16/9] max-h-52 overflow-hidden bg-background">
          <img src={previewUrl} alt="上传文件预览" className="size-full object-contain" />
          <span
            className="pointer-events-none absolute border-2 border-coral bg-coral/15"
            style={{
              left: `${evidence.region.x * 100}%`,
              top: `${evidence.region.y * 100}%`,
              width: `${evidence.region.width * 100}%`,
              height: `${evidence.region.height * 100}%`,
            }}
          />
        </div>
      ) : null}
      <div className="flex items-start gap-2 px-3 py-2 text-xs text-muted">
        <LocateFixed className="mt-0.5 size-3.5 shrink-0 text-coral" />
        <div className="min-w-0">
          <p className="break-words text-foreground">
            {evidence.textEvidence || "此字段仅提供区域证据"}
          </p>
          {evidence.region && (
            <p className="mt-1">
              第 {evidence.region.page} 页 · 已标出原文位置
            </p>
          )}
        </div>
      </div>
    </div>
  );
}

export function BookingReviewCard({
  documentId,
  booking,
  previewUrl,
  onCorrected,
}: {
  documentId: string;
  booking: ParsedBooking;
  previewUrl?: string;
  onCorrected: () => void;
}) {
  const [editing, setEditing] = useState(false);
  const [reason, setReason] = useState("");
  const [values, setValues] = useState<Partial<Record<BookingFieldValue, string>>>({});
  const [selectedEvidence, setSelectedEvidence] =
    useState<ExtractedFieldEvidence>();
  const correctionIdempotencyKey = useRef(
    `booking-correction-${crypto.randomUUID()}`,
  );

  const fieldsToReview = useMemo(() => {
    const fields = new Set<BookingFieldValue>();
    booking.missingFields.forEach((field) => {
      if (Object.values(BookingField).includes(field as BookingFieldValue)) {
        fields.add(field as BookingFieldValue);
      }
    });
    booking.fieldEvidence
      .filter(
        (evidence) =>
          evidence.confidence < 0.75 ||
          evidence.status === "LOW_CONFIDENCE" ||
          evidence.status === "MISSING",
      )
      .forEach((evidence) => fields.add(evidence.field));
    return [...fields];
  }, [booking]);
  const extractedFields = useMemo(
    () =>
      Object.values(BookingField)
        .filter((field) => field !== BookingField.Type && field !== BookingField.Currency)
        .map((field) => ({
          field,
          value: displayValueFor(booking, field),
          evidence: booking.fieldEvidence.find((item) => item.field === field),
        }))
        .filter((item) => item.value !== "未提取" || fieldsToReview.includes(item.field)),
    [booking, fieldsToReview],
  );

  const correction = useMutation({
    mutationFn: (corrections: BookingFieldCorrection[]) =>
      apiAdapter.documents.correct(
        documentId,
        booking.bookingId,
        { corrections, reason: reason.trim() || undefined },
        correctionIdempotencyKey.current,
      ),
    onSuccess: () => {
      correctionIdempotencyKey.current = `booking-correction-${crypto.randomUUID()}`;
      setEditing(false);
      setValues({});
      setReason("");
      onCorrected();
    },
  });

  function submitCorrections() {
    const corrections = fieldsToReview.flatMap((field) => {
      const value = values[field]?.trim();
      return value ? [{ field, value }] : [];
    });
    if (corrections.length > 0) {
      correction.mutate(corrections);
    }
  }

  return (
    <article
      className={cn(
        "rounded-md border bg-surface p-4",
        fieldsToReview.length > 0 ? "border-warning/40" : "border-border",
      )}
    >
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <p className="text-xs font-semibold text-coral">{bookingTypeLabels[booking.type]}</p>
          <h3 className="mt-1 text-base font-bold">
            {booking.hotelName ||
              booking.transportNumber ||
              booking.provider ||
              "已提取订单"}
          </h3>
        </div>
        <StatusBadge status={booking.reviewStatus} />
      </div>

      <dl className="mt-4 grid border-t border-l border-border text-sm sm:grid-cols-2">
        {extractedFields.map((item) => {
          const lowConfidence =
            item.evidence &&
            (item.evidence.confidence < 0.75 ||
              item.evidence.status === "LOW_CONFIDENCE" ||
              item.evidence.status === "MISSING");
          return (
            <div
              key={item.field}
              className={cn(
                "min-w-0 border-r border-b border-border px-3 py-2.5",
                lowConfidence && "bg-danger-soft/45",
              )}
            >
              <dt className="flex items-center justify-between gap-2 text-xs text-muted">
                {fieldLabels[item.field]}
                {item.evidence && (
                  <span className={cn("font-bold", lowConfidence && "text-danger")}>
                    {Math.round(item.evidence.confidence * 100)}%
                  </span>
                )}
              </dt>
              <dd className={cn("mt-1 truncate font-semibold", lowConfidence && "text-danger")}>
                {item.value}
              </dd>
            </div>
          );
        })}
      </dl>

      <div className="mt-4">
        <div className="flex items-center justify-between">
          <p className="text-xs font-semibold text-muted">字段证据</p>
          <span className={cn("rounded-full px-2 py-1 text-[11px] font-bold", confidenceStyle(booking.confidence))}>
            总置信度 {Math.round(booking.confidence * 100)}%
          </span>
        </div>
        <div className="mt-2 flex flex-wrap gap-2">
          {booking.fieldEvidence.map((evidence) => (
            <button
              key={evidence.field}
              type="button"
              onClick={() => setSelectedEvidence(evidence)}
              className={cn(
                "inline-flex h-7 items-center gap-1.5 rounded-full px-2.5 text-xs font-semibold",
                confidenceStyle(evidence.confidence),
              )}
            >
              <FileSearch className="size-3.5" />
              {fieldLabels[evidence.field]} {Math.round(evidence.confidence * 100)}%
            </button>
          ))}
        </div>
        {selectedEvidence && (
          <EvidenceLocator evidence={selectedEvidence} previewUrl={previewUrl} />
        )}
      </div>

      {booking.validationIssues.length > 0 && (
        <div className="mt-4 space-y-2">
          {booking.validationIssues.map((issue) => (
            <p
              key={`${issue.code}-${issue.field}`}
              className="flex items-start gap-2 rounded-md bg-warning-soft px-3 py-2 text-xs text-warning"
            >
              <TriangleAlert className="mt-0.5 size-3.5 shrink-0" />
              {fieldLabels[issue.field as BookingFieldValue] ?? "订单信息"}
              需要人工确认
            </p>
          ))}
        </div>
      )}

      {fieldsToReview.length > 0 && (
        <div className="mt-4 border-t border-border pt-4">
          {!editing ? (
            <Button variant="outline" size="sm" onClick={() => setEditing(true)}>
              <PencilLine />
              人工修正
            </Button>
          ) : (
            <div className="space-y-4">
              <div className="grid gap-4 sm:grid-cols-2">
                {fieldsToReview.map((field) => (
                  <FormField key={field} label={fieldLabels[field]} htmlFor={`${booking.bookingId}-${field}`}>
                    <Input
                      id={`${booking.bookingId}-${field}`}
                      value={values[field] ?? valueFor(booking, field)}
                      onChange={(event) =>
                        setValues((current) => ({
                          ...current,
                          [field]: event.target.value,
                        }))
                      }
                    />
                  </FormField>
                ))}
              </div>
              <FormField label="修正原因" htmlFor={`${booking.bookingId}-reason`} optional>
                <Textarea
                  id={`${booking.bookingId}-reason`}
                  value={reason}
                  onChange={(event) => setReason(event.target.value)}
                  placeholder="说明核对来源"
                  className="min-h-20"
                />
              </FormField>
              <div className="flex gap-2">
                <Button
                  type="button"
                  size="sm"
                  onClick={submitCorrections}
                  disabled={correction.isPending}
                >
                  <Check />
                  {correction.isPending ? "正在保存" : "保存修正"}
                </Button>
                <Button
                  type="button"
                  size="sm"
                  variant="ghost"
                  onClick={() => setEditing(false)}
                >
                  <X />
                  取消
                </Button>
              </div>
            </div>
          )}
        </div>
      )}
    </article>
  );
}
