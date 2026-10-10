import type { FieldValues, Path, UseFormSetError } from "react-hook-form";
import { ApiClientError } from "@/api/errors";

function pointerToPath(pointer: string) {
  return pointer
    .replace(/^\/+/, "")
    .split("/")
    .map((segment) => segment.replace(/~1/g, "/").replace(/~0/g, "~"))
    .join(".");
}

export function applyServerFieldErrors<T extends FieldValues>(
  error: unknown,
  setError: UseFormSetError<T>,
  prefix = "",
) {
  if (!(error instanceof ApiClientError) || error.status !== 422 || !error.payload) {
    return false;
  }

  let applied = false;
  error.payload.details.forEach((detail) => {
    if (!detail.field) {
      return;
    }
    const path = pointerToPath(detail.field);
    const field = (prefix ? `${prefix}.${path}` : path) as Path<T>;
    setError(field, {
      type: "server",
      message: detail.code.includes("REQUIRED")
        ? "请填写此项"
        : "请检查此项是否符合要求",
    });
    applied = true;
  });
  return applied;
}
