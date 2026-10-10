import { zodResolver } from "@hookform/resolvers/zod";
import { Send } from "lucide-react";
import { useForm } from "react-hook-form";
import { z } from "zod";
import { Button } from "@/components/ui/button";
import { useAppStore } from "@/stores/app-store";

const formSchema = z.object({
  title: z.string().trim().min(2, "至少输入 2 个字符").max(40),
});

type FormValues = z.infer<typeof formSchema>;

export function NotificationPreviewForm() {
  const addNotification = useAppStore((state) => state.addNotification);
  const {
    register,
    handleSubmit,
    reset,
    formState: { errors },
  } = useForm<FormValues>({
    resolver: zodResolver(formSchema),
    defaultValues: { title: "方案已完成规则校验" },
  });

  const onSubmit = (values: FormValues) => {
    addNotification({
      tone: "success",
      title: values.title,
      message: "可在顶部通知中心查看。",
    });
    reset();
  };

  return (
    <form
      className="flex min-w-0 flex-col gap-2 sm:flex-row sm:items-start"
      onSubmit={handleSubmit(onSubmit)}
      noValidate
    >
      <div className="min-w-0 flex-1">
        <label htmlFor="notification-title" className="sr-only">
          通知标题
        </label>
        <input
          id="notification-title"
          className="h-9 w-full rounded-md border border-border bg-surface px-3 text-sm placeholder:text-muted"
          {...register("title")}
        />
        {errors.title && <p className="mt-1 text-xs text-danger">{errors.title.message}</p>}
      </div>
      <Button type="submit" variant="outline">
        <Send />
        发送通知
      </Button>
    </form>
  );
}
