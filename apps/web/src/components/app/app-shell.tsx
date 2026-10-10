import { Activity } from "lucide-react";
import { Outlet } from "react-router-dom";
import { BrandMark, SideNavigation } from "./side-navigation";
import { TopNavigation } from "./top-navigation";
import {
  Sheet,
  SheetContent,
  SheetDescription,
  SheetTitle,
} from "@/components/ui/sheet";
import { useAppStore } from "@/stores/app-store";

export function AppShell() {
  const sidebarOpen = useAppStore((state) => state.sidebarOpen);
  const setSidebarOpen = useAppStore((state) => state.setSidebarOpen);

  return (
    <div className="min-h-screen bg-background">
      <a
        href="#main-content"
        className="fixed top-2 left-2 z-[100] -translate-y-20 rounded bg-navy-strong px-3 py-2 text-sm text-white focus:translate-y-0"
      >
        跳到主要内容
      </a>

      <aside className="fixed inset-y-0 left-0 z-40 hidden w-60 border-r border-border bg-surface lg:flex lg:flex-col">
        <BrandMark />
        <SideNavigation />
        <div className="border-t border-border p-3">
          <div className="flex items-center gap-2 rounded-md bg-surface-subtle px-2.5 py-2 text-xs text-muted">
            <Activity className="size-4 shrink-0 text-success" />
            <span className="min-w-0 flex-1 truncate">行程会自动保存</span>
          </div>
        </div>
      </aside>

      <Sheet open={sidebarOpen} onOpenChange={setSidebarOpen}>
        <SheetContent>
          <SheetTitle className="sr-only">主导航</SheetTitle>
          <SheetDescription className="sr-only">TripAgent Nexus 页面导航</SheetDescription>
          <div className="flex h-full flex-col">
            <BrandMark />
            <SideNavigation onNavigate={() => setSidebarOpen(false)} />
          </div>
        </SheetContent>
      </Sheet>

      <div className="min-w-0 lg:pl-60">
        <TopNavigation onOpenNavigation={() => setSidebarOpen(true)} />
        <main
          id="main-content"
          className="mx-auto min-w-0 max-w-[1600px] px-4 py-5 sm:px-6 sm:py-7 xl:px-8"
        >
          <Outlet />
        </main>
      </div>
    </div>
  );
}
