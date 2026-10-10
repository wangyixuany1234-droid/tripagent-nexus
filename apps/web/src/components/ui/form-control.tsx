import { forwardRef, type ComponentProps, type ReactNode } from "react";
import { cn } from "@/lib/utils";

const controlClass =
  "w-full rounded-md border border-border bg-surface px-3 text-sm text-foreground transition-colors placeholder:text-muted/70 focus:border-sky disabled:cursor-not-allowed disabled:opacity-60";

interface FieldProps {
  label: string;
  htmlFor: string;
  error?: string;
  hint?: string;
  optional?: boolean;
  children: ReactNode;
}

export function FormField({
  label,
  htmlFor,
  error,
  hint,
  optional,
  children,
}: FieldProps) {
  return (
    <div className="min-w-0">
      <label htmlFor={htmlFor} className="mb-1.5 flex items-center gap-2 text-sm font-semibold">
        {label}
        {optional && <span className="text-xs font-normal text-muted">可选</span>}
      </label>
      {children}
      {error ? (
        <p className="mt-1.5 text-xs text-danger" role="alert">
          {error}
        </p>
      ) : (
        hint && <p className="mt-1.5 text-xs leading-5 text-muted">{hint}</p>
      )}
    </div>
  );
}

export const Input = forwardRef<HTMLInputElement, ComponentProps<"input">>(
  ({ className, ...props }, ref) => (
    <input ref={ref} className={cn(controlClass, "h-10", className)} {...props} />
  ),
);
Input.displayName = "Input";

export const Textarea = forwardRef<HTMLTextAreaElement, ComponentProps<"textarea">>(
  ({ className, ...props }, ref) => (
    <textarea
      ref={ref}
      className={cn(controlClass, "min-h-28 resize-y py-2.5 leading-6", className)}
      {...props}
    />
  ),
);
Textarea.displayName = "Textarea";

export const Select = forwardRef<HTMLSelectElement, ComponentProps<"select">>(
  ({ className, ...props }, ref) => (
    <select ref={ref} className={cn(controlClass, "h-10", className)} {...props} />
  ),
);
Select.displayName = "Select";
