import {
  Bell,
  CheckCheck,
  CircleAlert,
  CircleCheck,
  Info,
  TriangleAlert,
  X,
} from "lucide-react";
import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { cn } from "@/lib/utils";
import { useAppStore, type NotificationTone } from "@/stores/app-store";

const toneIcon: Record<NotificationTone, typeof Info> = {
  info: Info,
  success: CircleCheck,
  warning: TriangleAlert,
  error: CircleAlert,
};

const toneClass: Record<NotificationTone, string> = {
  info: "bg-sky-soft text-sky",
  success: "bg-success-soft text-success",
  warning: "bg-warning-soft text-warning",
  error: "bg-danger-soft text-danger",
};

export function NotificationCenter() {
  const notifications = useAppStore((state) => state.notifications);
  const removeNotification = useAppStore((state) => state.removeNotification);
  const clearNotifications = useAppStore((state) => state.clearNotifications);

  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button variant="ghost" size="icon" className="relative" aria-label="打开通知">
          <Bell />
          {notifications.length > 0 && (
            <span className="absolute mt-[-22px] ml-[20px] size-2.5 rounded-full border-2 border-surface bg-coral" />
          )}
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end" className="w-[min(92vw,360px)] p-0">
        <div className="flex h-12 items-center justify-between px-3">
          <DropdownMenuLabel className="p-0 text-sm text-foreground">
            通知
          </DropdownMenuLabel>
          {notifications.length > 0 && (
            <Button
              variant="ghost"
              size="sm"
              onClick={clearNotifications}
              className="text-muted"
            >
              <CheckCheck />
              全部清除
            </Button>
          )}
        </div>
        <DropdownMenuSeparator className="m-0" />
        <div className="max-h-80 overflow-y-auto p-2">
          {notifications.length === 0 ? (
            <div className="flex min-h-28 flex-col items-center justify-center gap-2 text-muted">
              <Bell className="size-5" />
              <p className="text-sm">暂无通知</p>
            </div>
          ) : (
            notifications.map((notification) => {
              const Icon = toneIcon[notification.tone];
              return (
                <div
                  key={notification.id}
                  className="group flex gap-3 rounded-md px-2 py-2.5 hover:bg-surface-subtle"
                >
                  <span
                    className={cn(
                      "mt-0.5 flex size-7 shrink-0 items-center justify-center rounded-full",
                      toneClass[notification.tone],
                    )}
                  >
                    <Icon className="size-3.5" />
                  </span>
                  <div className="min-w-0 flex-1">
                    <p className="text-sm font-semibold">{notification.title}</p>
                    {notification.message && (
                      <p className="mt-1 break-all text-xs leading-5 text-muted">
                        {notification.message}
                      </p>
                    )}
                  </div>
                  <button
                    type="button"
                    onClick={() => removeNotification(notification.id)}
                    className="flex size-7 shrink-0 items-center justify-center rounded text-muted opacity-60 hover:bg-background hover:text-foreground group-hover:opacity-100"
                    aria-label={`移除通知：${notification.title}`}
                  >
                    <X className="size-3.5" />
                  </button>
                </div>
              );
            })
          )}
        </div>
      </DropdownMenuContent>
    </DropdownMenu>
  );
}
