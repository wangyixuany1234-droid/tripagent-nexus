import { lazy, Suspense, useEffect, type ReactNode } from "react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { createBrowserRouter, Navigate, RouterProvider } from "react-router-dom";
import { AuthProvider } from "@/auth/auth-context";
import {
  AuthCallbackPage,
  LogoutCallbackPage,
  RequireAuthentication,
  SilentAuthCallbackPage,
} from "@/auth/auth-routes";
import { AppShell } from "@/components/app/app-shell";
import { WorkspaceSkeleton } from "@/components/ui/skeleton";
import { TooltipProvider } from "@/components/ui/tooltip";
import { ModulePlaceholderPage } from "@/pages/module-placeholder-page";
import { NotFoundPage } from "@/pages/not-found-page";
import { WorkspacePage } from "@/pages/workspace-page";

const DesignSystemPage = lazy(() =>
  import("@/pages/design-system-page").then((module) => ({
    default: module.DesignSystemPage,
  })),
);
const TripWizardPage = lazy(() =>
  import("@/pages/trip-wizard-page").then((module) => ({
    default: module.TripWizardPage,
  })),
);
const DocumentsPage = lazy(() =>
  import("@/pages/documents-page").then((module) => ({
    default: module.DocumentsPage,
  })),
);
const TaskConsolePage = lazy(() =>
  import("@/pages/task-console-page").then((module) => ({
    default: module.TaskConsolePage,
  })),
);
const PlanResultsPage = lazy(() =>
  import("@/pages/plan-results-page").then((module) => ({
    default: module.PlanResultsPage,
  })),
);
const WorkbenchPage = lazy(() =>
  import("@/pages/workbench-page").then((module) => ({
    default: module.WorkbenchPage,
  })),
);
const PreferencesPage = lazy(() =>
  import("@/pages/preferences-page").then((module) => ({
    default: module.PreferencesPage,
  })),
);
const FeedbackPage = lazy(() =>
  import("@/pages/feedback-page").then((module) => ({
    default: module.FeedbackPage,
  })),
);
const DeveloperTracePage = lazy(() =>
  import("@/pages/developer-trace-page").then((module) => ({
    default: module.DeveloperTracePage,
  })),
);
const SystemStatusPage = lazy(() =>
  import("@/pages/system-status-page").then((module) => ({
    default: module.SystemStatusPage,
  })),
);
const LoginPage = lazy(() =>
  import("@/pages/login-page").then((module) => ({
    default: module.LoginPage,
  })),
);

function lazyPage(element: ReactNode) {
  return <Suspense fallback={<WorkspaceSkeleton />}>{element}</Suspense>;
}

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      refetchOnWindowFocus: false,
      retry: 1,
    },
    mutations: {
      retry: false,
    },
  },
});

const router = createBrowserRouter([
  { path: "login", element: lazyPage(<LoginPage />) },
  { path: "auth/callback", element: <AuthCallbackPage /> },
  { path: "auth/logout-callback", element: <LogoutCallbackPage /> },
  { path: "auth/silent-callback", element: <SilentAuthCallbackPage /> },
  {
    element: (
      <RequireAuthentication>
        <AppShell />
      </RequireAuthentication>
    ),
    children: [
      { index: true, element: <WorkspacePage /> },
      {
        path: "design-system",
        element: lazyPage(<DesignSystemPage />),
      },
      { path: "workbench", element: lazyPage(<WorkbenchPage />) },
      { path: "trips", element: <Navigate to="/trips/new" replace /> },
      { path: "trips/new", element: lazyPage(<TripWizardPage />) },
      {
        path: "trips/:tripId/documents",
        element: lazyPage(<DocumentsPage />),
      },
      { path: "explore", element: lazyPage(<PlanResultsPage />) },
      { path: "plans/:planId", element: lazyPage(<PlanResultsPage />) },
      { path: "documents", element: lazyPage(<DocumentsPage />) },
      { path: "tasks", element: lazyPage(<TaskConsolePage />) },
      { path: "tasks/:taskId", element: lazyPage(<TaskConsolePage />) },
      { path: "preferences", element: lazyPage(<PreferencesPage />) },
      { path: "feedback", element: lazyPage(<FeedbackPage />) },
      { path: "system-status", element: lazyPage(<SystemStatusPage />) },
      { path: "developer/traces", element: lazyPage(<DeveloperTracePage />) },
      { path: "actions", element: <ModulePlaceholderPage /> },
      { path: "evaluations", element: <ModulePlaceholderPage /> },
      { path: "*", element: <NotFoundPage /> },
    ],
  },
]);

export function App() {
  useEffect(() => {
    const handleSessionExpired = () => {
      const returnTo = `${window.location.pathname}${window.location.search}`;
      void router.navigate(`/login?returnTo=${encodeURIComponent(returnTo)}`);
    };
    window.addEventListener("tripagent:session-expired", handleSessionExpired);
    return () =>
      window.removeEventListener("tripagent:session-expired", handleSessionExpired);
  }, []);

  return (
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <TooltipProvider delayDuration={250}>
          <RouterProvider router={router} />
        </TooltipProvider>
      </AuthProvider>
    </QueryClientProvider>
  );
}
