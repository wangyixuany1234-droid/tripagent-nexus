import * as DialogPrimitive from "@radix-ui/react-dialog";
import { X } from "lucide-react";
import { cn } from "@/lib/utils";

export const Sheet = DialogPrimitive.Root;
export const SheetTrigger = DialogPrimitive.Trigger;
export const SheetClose = DialogPrimitive.Close;
export const SheetTitle = DialogPrimitive.Title;
export const SheetDescription = DialogPrimitive.Description;

export function SheetContent({
  className,
  children,
  side = "left",
  ...props
}: React.ComponentProps<typeof DialogPrimitive.Content> & {
  side?: "left" | "right" | "bottom";
}) {
  return (
    <DialogPrimitive.Portal>
      <DialogPrimitive.Overlay className="fixed inset-0 z-50 bg-black/45 data-[state=closed]:opacity-0" />
      <DialogPrimitive.Content
        className={cn(
          "fixed z-50 bg-surface shadow-2xl",
          side === "left" &&
            "inset-y-0 left-0 w-[min(86vw,300px)] border-r border-border",
          side === "right" &&
            "inset-y-0 right-0 w-[min(92vw,920px)] border-l border-border",
          side === "bottom" &&
            "inset-x-0 bottom-0 max-h-[88vh] min-h-72 overflow-y-auto border-t border-border",
          className,
        )}
        {...props}
      >
        {children}
        <DialogPrimitive.Close className="absolute top-3 right-3 flex size-9 items-center justify-center rounded-md text-muted hover:bg-surface-subtle hover:text-foreground">
          <X className="size-4" />
          <span className="sr-only">关闭</span>
        </DialogPrimitive.Close>
      </DialogPrimitive.Content>
    </DialogPrimitive.Portal>
  );
}
