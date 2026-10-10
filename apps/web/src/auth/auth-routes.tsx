import { useEffect, useRef, useState, type ReactNode } from "react";
import { LoaderCircle } from "lucide-react";
import { Link, Navigate, useLocation, useNavigate } from "react-router-dom";
import { Button } from "@/components/ui/button";
import { useAuth } from "./auth-context";

function SessionLoading() {
  return (
    <main className="flex min-h-screen items-center justify-center bg-background px-4">
      <div className="flex items-center gap-3 text-sm text-muted">
        <LoaderCircle className="size-5 animate-spin" />
        正在验证登录状态
      </div>
    </main>
  );
}

export function RequireAuthentication({ children }: { children: ReactNode }) {
  const auth = useAuth();
  const location = useLocation();

  if (!auth.enabled || auth.status === "authenticated") {
    return children;
  }
  if (auth.status === "loading") {
    return <SessionLoading />;
  }
  const returnTo = `${location.pathname}${location.search}`;
  return (
    <Navigate
      replace
      to={`/login?returnTo=${encodeURIComponent(returnTo)}`}
    />
  );
}

function CallbackFailure() {
  return (
    <main className="flex min-h-screen items-center justify-center bg-background px-4">
      <section className="w-full max-w-md border-y border-border py-10 text-center">
        <h1 className="text-2xl font-bold text-navy-strong">无法完成登录</h1>
        <p className="mt-2 text-sm leading-6 text-muted">
          登录请求未完成，请重新发起登录。
        </p>
        <Button asChild className="mt-6">
          <Link to="/login">返回登录</Link>
        </Button>
      </section>
    </main>
  );
}

export function AuthCallbackPage() {
  const auth = useAuth();
  const navigate = useNavigate();
  const started = useRef(false);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    if (started.current) {
      return;
    }
    started.current = true;
    void auth
      .completeSignIn()
      .then((returnTo) => navigate(returnTo, { replace: true }))
      .catch(() => setFailed(true));
  }, [auth, navigate]);

  return failed ? <CallbackFailure /> : <SessionLoading />;
}

export function SilentAuthCallbackPage() {
  const auth = useAuth();
  const started = useRef(false);

  useEffect(() => {
    if (started.current) {
      return;
    }
    started.current = true;
    void auth.completeSilentSignIn();
  }, [auth]);

  return <SessionLoading />;
}

export function LogoutCallbackPage() {
  const auth = useAuth();
  const navigate = useNavigate();
  const started = useRef(false);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    if (started.current) {
      return;
    }
    started.current = true;
    void auth
      .completeSignOut()
      .then(() => navigate("/login", { replace: true }))
      .catch(() => setFailed(true));
  }, [auth, navigate]);

  return failed ? <CallbackFailure /> : <SessionLoading />;
}
