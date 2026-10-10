import { useEffect, useMemo, useRef, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  Bot,
  File,
  FileImage,
  FileUp,
  LoaderCircle,
  RefreshCcw,
  UploadCloud,
  X,
} from "lucide-react";
import { Link, useNavigate, useParams } from "react-router-dom";
import { apiAdapter } from "@/api/client";
import { BookingReviewCard } from "@/components/documents/booking-review-card";
import { PageHeader } from "@/components/app/page-header";
import { EmptyState } from "@/components/states/state-panel";
import { Button } from "@/components/ui/button";
import { Progress } from "@/components/ui/progress";
import { Skeleton } from "@/components/ui/skeleton";
import { StatusBadge } from "@/components/travel/status-badge";
import { useTaskMonitor } from "@/hooks/use-task-monitor";
import { cn } from "@/lib/utils";
import {
  useTripFlowStore,
  type StoredDocumentTask,
} from "@/stores/trip-flow-store";
import { useAppStore } from "@/stores/app-store";

const allowedTypes = new Set(["image/jpeg", "image/png", "image/webp", "application/pdf"]);
const maxFileSize = 10 * 1024 * 1024;
const documentStageDescriptions: Record<string, string> = {
  CREATED: "资料已接收",
  QUEUED: "等待内容识别",
  OCR_STARTED: "正在识别文档",
  RULE_VALIDATION: "正在核对提取内容",
  NEEDS_REVIEW: "等待人工确认",
  REVIEW_APPLIED: "人工修正已应用",
  COMPLETED: "订单信息识别完成",
  FAILED: "订单信息识别未完成",
  CANCELLED: "内容识别已取消",
};

async function sha256(file: File) {
  const digest = await crypto.subtle.digest("SHA-256", await file.arrayBuffer());
  return [...new Uint8Array(digest)]
    .map((value) => value.toString(16).padStart(2, "0"))
    .join("");
}

function formatFileSize(bytes: number) {
  if (bytes < 1024 * 1024) {
    return `${Math.max(1, Math.round(bytes / 1024))} KB`;
  }
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
}

function DocumentTaskPanel({
  record,
  previewUrl,
}: {
  record: StoredDocumentTask;
  previewUrl?: string;
}) {
  const queryClient = useQueryClient();
  const replaceDocumentTask = useTripFlowStore((state) => state.replaceDocumentTask);
  const monitor = useTaskMonitor(record.taskId, () => {
    void queryClient.invalidateQueries({
      queryKey: ["documents", record.resourceId],
    });
  });
  const documentQuery = useQuery({
    queryKey: ["documents", record.resourceId],
    queryFn: () => apiAdapter.documents.get(record.resourceId),
    retry: 1,
  });
  const retry = useMutation({
    mutationFn: () =>
      apiAdapter.documents.retry(
        record.resourceId,
        record.idempotencyKey,
      ),
    onSuccess: (accepted) => {
      replaceDocumentTask(record.resourceId, accepted);
      void queryClient.invalidateQueries({
        queryKey: ["documents", record.resourceId],
      });
    },
  });

  useEffect(() => {
    if (monitor.data?.updatedAt) {
      void documentQuery.refetch();
    }
  }, [monitor.data?.updatedAt]);

  const task = monitor.data;
  const document = documentQuery.data;
  const failed = task?.status === "FAILED" || document?.status === "FAILED";

  return (
    <section className="border-t border-border py-6 first:border-t-0 first:pt-0">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="flex min-w-0 items-center gap-3">
          <span className="flex size-10 shrink-0 items-center justify-center rounded-md bg-sky-soft text-sky">
            {record.mediaType.startsWith("image/") ? (
              <FileImage className="size-5" />
            ) : (
              <File className="size-5" />
            )}
          </span>
          <div className="min-w-0">
            <h2 className="truncate text-sm font-bold">{record.fileName}</h2>
            <p className="mt-1 text-xs text-muted">
              {formatFileSize(record.sizeBytes)}
            </p>
          </div>
        </div>
        {document ? (
          <StatusBadge status={document.status} />
        ) : task ? (
          <StatusBadge status={task.status} />
        ) : null}
      </div>

      {task && (
        <div className="mt-4">
          <div className="mb-2 flex items-center justify-between text-xs">
            <span className="text-muted">
              {documentStageDescriptions[task.stage] || "正在处理订单"}
            </span>
            <span className="font-bold text-navy-strong">{task.progress}%</span>
          </div>
          <Progress value={task.progress} label={`${record.fileName} 识别进度`} />
        </div>
      )}

      {failed && (
        <div className="mt-4 flex flex-wrap items-center justify-between gap-3 rounded-md bg-danger-soft px-3 py-3">
          <div>
            <p className="text-sm font-bold text-danger">识别未完成</p>
            <p className="mt-1 text-xs text-muted">当前文件未能完成识别，请重新尝试。</p>
          </div>
          <Button
            size="sm"
            variant="outline"
            onClick={() => retry.mutate()}
            disabled={retry.isPending}
          >
            <RefreshCcw />
            {retry.isPending ? "正在重试" : "重新识别"}
          </Button>
        </div>
      )}

      {documentQuery.isLoading ? (
        <div className="mt-4 grid gap-3 sm:grid-cols-2">
          <Skeleton className="h-36" />
          <Skeleton className="h-36" />
        </div>
      ) : document && document.bookings.length > 0 ? (
        <div className="mt-5 grid gap-4 xl:grid-cols-2">
          {document.bookings.map((booking) => (
            <BookingReviewCard
              key={booking.bookingId}
              documentId={document.documentId}
              booking={booking}
              previewUrl={previewUrl}
              onCorrected={() =>
                void queryClient.invalidateQueries({
                  queryKey: ["documents", record.resourceId],
                })
              }
            />
          ))}
        </div>
      ) : task?.status === "WAITING_CONFIRMATION" ? (
        <p className="mt-4 text-sm text-warning">部分信息需要确认后才能继续。</p>
      ) : null}
    </section>
  );
}

export function DocumentsPage() {
  const params = useParams<{ tripId?: string }>();
  const navigate = useNavigate();
  const inputRef = useRef<HTMLInputElement>(null);
  const activeTripId = useTripFlowStore((state) => state.activeTripId);
  const documentTasks = useTripFlowStore((state) => state.documentTasks);
  const planningTasks = useTripFlowStore((state) => state.planningTasks);
  const rememberTrip = useTripFlowStore((state) => state.rememberTrip);
  const rememberDocument = useTripFlowStore((state) => state.rememberDocument);
  const rememberPlanningTask = useTripFlowStore((state) => state.rememberPlanningTask);
  const addNotification = useAppStore((state) => state.addNotification);
  const tripId = params.tripId ?? activeTripId;
  const [dragging, setDragging] = useState(false);
  const [selectedFile, setSelectedFile] = useState<File>();
  const [uploadProgress, setUploadProgress] = useState(0);
  const [previewUrls, setPreviewUrls] = useState<Record<string, string>>({});
  const [selectedPreviewUrl, setSelectedPreviewUrl] = useState<string>();
  const previewUrlsRef = useRef<Record<string, string>>({});

  useEffect(() => {
    previewUrlsRef.current = previewUrls;
  }, [previewUrls]);

  useEffect(
    () => () => {
      Object.values(previewUrlsRef.current).forEach(URL.revokeObjectURL);
    },
    [],
  );

  useEffect(() => {
    if (!selectedFile?.type.startsWith("image/")) {
      setSelectedPreviewUrl(undefined);
      return;
    }
    const url = URL.createObjectURL(selectedFile);
    setSelectedPreviewUrl(url);
    return () => URL.revokeObjectURL(url);
  }, [selectedFile]);

  const records = useMemo(
    () => documentTasks.filter((item) => item.tripId === tripId),
    [documentTasks, tripId],
  );

  useEffect(() => {
    if (params.tripId) {
      rememberTrip(params.tripId);
    }
  }, [params.tripId, rememberTrip]);
  const activePlanningTask = planningTasks.find((item) => item.tripId === tripId);
  const tripQuery = useQuery({
    queryKey: ["trips", tripId],
    queryFn: () => apiAdapter.trips.get(tripId!),
    enabled: Boolean(tripId),
  });

  const upload = useMutation({
    mutationFn: async (file: File) => {
      if (!tripId) {
        throw new Error("Missing trip ID.");
      }
      const hash = await sha256(file);
      const duplicate = records.find((record) => record.sha256 === hash);
      if (duplicate) {
        throw new Error(`DUPLICATE:${duplicate.fileName}`);
      }
      const idempotencyKey = `document-${hash.slice(0, 48)}`;
      const accepted = await apiAdapter.documents.upload({
        tripId,
        file,
        idempotencyKey,
        onProgress: setUploadProgress,
      });
      return { accepted, hash, idempotencyKey, file };
    },
    onSuccess: ({ accepted, hash, idempotencyKey, file }) => {
      const previewUrl = file.type.startsWith("image/")
        ? URL.createObjectURL(file)
        : undefined;
      if (previewUrl) {
        setPreviewUrls((current) => ({
          ...current,
          [accepted.resourceId]: previewUrl,
        }));
      }
      rememberDocument({
        taskId: accepted.taskId,
        resourceId: accepted.resourceId,
        traceId: accepted.traceId,
        tripId: tripId!,
        fileName: file.name,
        mediaType: file.type,
        sizeBytes: file.size,
        sha256: hash,
        idempotencyKey,
        createdAt: accepted.createdAt.toISOString(),
      });
      setSelectedFile(undefined);
      setUploadProgress(0);
      addNotification({
        tone: "success",
        title: "文件已上传",
        message: "正在识别文件中的订单信息。",
      });
    },
    onError: (error) => {
      if (error instanceof Error && error.message.startsWith("DUPLICATE:")) {
        addNotification({
          tone: "warning",
          title: "检测到重复文件",
          message: `${error.message.slice(10)} 已在当前旅行中上传。`,
        });
      }
      setUploadProgress(0);
    },
  });

  const createPlan = useMutation({
    mutationFn: () =>
      apiAdapter.plans.create(
        { tripId: tripId!, title: tripQuery.data?.title },
        `plan-create-${tripId}`,
      ),
    onSuccess: (accepted) => {
      rememberPlanningTask({
        taskId: accepted.taskId,
        resourceId: accepted.resourceId,
        traceId: accepted.traceId,
        tripId: tripId!,
        type: "PLAN_GENERATION",
        createdAt: accepted.createdAt.toISOString(),
      });
      navigate(`/tasks/${accepted.taskId}`);
    },
  });

  function selectFile(file?: File) {
    if (!file) {
      return;
    }
    if (!allowedTypes.has(file.type)) {
      addNotification({
        tone: "error",
        title: "不支持的文件类型",
        message: "请选择 PNG、JPEG、WEBP 或 PDF。",
      });
      return;
    }
    if (file.size === 0 || file.size > maxFileSize) {
      addNotification({
        tone: "error",
        title: "文件大小不符合要求",
        message: "文件必须小于 10 MB 且不能为空。",
      });
      return;
    }
    setSelectedFile(file);
  }

  if (!tripId) {
    return (
      <>
        <PageHeader title="订单资料中心" breadcrumbs={[{ label: "订单资料" }]} />
        <EmptyState
          icon={FileUp}
          title="请先创建旅行"
          description="创建旅行后即可上传相关订单资料。"
          actionLabel="开始规划"
          onAction={() => navigate("/trips/new")}
        />
      </>
    );
  }

  return (
    <>
      <PageHeader
        eyebrow="订单资料"
        title="订单资料中心"
        description={tripQuery.data?.title || "正在读取旅行信息"}
        breadcrumbs={[
          { label: "旅行项目", to: "/trips" },
          { label: tripQuery.data?.title || "当前旅行" },
          { label: "订单资料" },
        ]}
        actions={
          activePlanningTask ? (
            <Button asChild>
              <Link to={`/tasks/${activePlanningTask.taskId}`}>
                <Bot />
                查看生成进度
              </Link>
            </Button>
          ) : (
            <Button
              onClick={() => createPlan.mutate()}
              disabled={createPlan.isPending || tripQuery.data?.status !== "READY"}
            >
              <Bot />
              {createPlan.isPending ? "正在生成" : "生成旅行方案"}
            </Button>
          )
        }
      />

      <section
        className={cn(
          "relative flex min-h-48 flex-col items-center justify-center rounded-md border-2 border-dashed px-5 py-8 text-center transition-colors",
          dragging ? "border-sky bg-sky-soft/60" : "border-border bg-surface/60",
        )}
        onDragEnter={(event) => {
          event.preventDefault();
          setDragging(true);
        }}
        onDragOver={(event) => event.preventDefault()}
        onDragLeave={() => setDragging(false)}
        onDrop={(event) => {
          event.preventDefault();
          setDragging(false);
          selectFile(event.dataTransfer.files[0]);
        }}
      >
        <input
          ref={inputRef}
          type="file"
          accept=".png,.jpg,.jpeg,.webp,.pdf"
          className="sr-only"
          onChange={(event) => selectFile(event.target.files?.[0])}
        />
        <span className="flex size-11 items-center justify-center rounded-full bg-sky-soft text-sky">
          <UploadCloud className="size-5" />
        </span>
        <h2 className="mt-4 text-base font-bold">拖入订单或收藏截图</h2>
        <p className="mt-2 text-xs text-muted">PNG、JPEG、WEBP、PDF，最大 10 MB</p>
        <Button
          type="button"
          variant="outline"
          className="mt-4"
          onClick={() => inputRef.current?.click()}
        >
          <FileUp />
          选择文件
        </Button>
      </section>

      {selectedFile && (
        <section className="mt-5 rounded-md border border-border bg-surface p-4">
          <div className="flex items-start justify-between gap-3">
            <div className="min-w-0">
              <h2 className="truncate text-sm font-bold">{selectedFile.name}</h2>
              <p className="mt-1 text-xs text-muted">
                {formatFileSize(selectedFile.size)} ·{" "}
                {selectedFile.type === "application/pdf" ? "PDF 文档" : "图片"}
              </p>
            </div>
            <Button
              variant="ghost"
              size="icon"
              aria-label="移除待上传文件"
              onClick={() => setSelectedFile(undefined)}
              disabled={upload.isPending}
            >
              <X />
            </Button>
          </div>
          {selectedPreviewUrl && (
            <img
              src={selectedPreviewUrl}
              alt="待上传文件预览"
              className="mt-4 max-h-56 w-full rounded-md bg-surface-subtle object-contain"
            />
          )}
          {upload.isPending && (
            <div className="mt-4">
              <div className="mb-2 flex justify-between text-xs text-muted">
                <span>上传中</span>
                <span>{uploadProgress}%</span>
              </div>
              <Progress value={uploadProgress} label="文件上传进度" />
            </div>
          )}
          <Button
            className="mt-4"
            onClick={() => upload.mutate(selectedFile)}
            disabled={upload.isPending}
          >
            {upload.isPending ? (
              <LoaderCircle className="animate-spin" />
            ) : (
              <UploadCloud />
            )}
            {upload.isPending ? "正在上传" : "上传并识别"}
          </Button>
        </section>
      )}

      <section className="mt-8" aria-labelledby="uploaded-documents-title">
        <div className="mb-4 flex items-center justify-between">
          <h2 id="uploaded-documents-title" className="text-lg font-bold text-navy-strong">
            已上传资料
          </h2>
          <span className="text-xs text-muted">{records.length} 份</span>
        </div>
        {records.length === 0 ? (
          <EmptyState
            compact
            icon={FileUp}
            title="暂无订单资料"
            description="上传后将显示识别进度与提取结果。"
          />
        ) : (
          records.map((record) => (
            <DocumentTaskPanel
              key={`${record.resourceId}-${record.taskId}`}
              record={record}
              previewUrl={previewUrls[record.resourceId]}
            />
          ))
        )}
      </section>
    </>
  );
}
