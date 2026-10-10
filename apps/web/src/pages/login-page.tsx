import { LogIn } from "lucide-react";
import { Link, Navigate, useSearchParams } from "react-router-dom";
import { useAuth } from "@/auth/auth-context";
import { Button } from "@/components/ui/button";

export function LoginPage() {
  const auth = useAuth();
  const [searchParams] = useSearchParams();
  const requestedPath = searchParams.get("returnTo");
  const returnTo =
    requestedPath?.startsWith("/") && !requestedPath.startsWith("//")
      ? requestedPath
      : "/";

  if (auth.enabled && auth.status === "authenticated") {
    return <Navigate replace to={returnTo} />;
  }

  return (
    <main className="flex min-h-screen items-center justify-center bg-background px-4">
      <section className="w-full max-w-md border-y border-border py-10 text-center">
        <span className="mx-auto flex size-11 items-center justify-center rounded-full bg-sky-soft text-sky">
          <LogIn className="size-5" />
        </span>
        <h1 className="mt-4 text-2xl font-bold text-navy-strong">请重新登录</h1>
        <p className="mt-2 text-sm leading-6 text-muted">
          {auth.status === "error"
            ? "登录请求未完成，请稍后重试。"
            : "登录后可继续之前的操作。"}
        </p>
        {auth.enabled ? (
          <Button
            className="mt-6"
            disabled={auth.status === "loading"}
            onClick={() => void auth.signIn(returnTo).catch(() => undefined)}
          >
            <LogIn />
            登录
          </Button>
        ) : (
          <Button asChild className="mt-6">
            <Link to={returnTo}>返回并重新登录</Link>
          </Button>
        )}
      </section>
    </main>
  );
}
