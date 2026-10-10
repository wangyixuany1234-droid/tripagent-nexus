import {
  Bot,
  ClipboardCheck,
  FileScan,
  Map,
  Route,
  ShieldCheck,
} from "lucide-react";
import { Link, useLocation } from "react-router-dom";
import { PageHeader } from "@/components/app/page-header";
import { EmptyState } from "@/components/states/state-panel";
import { Button } from "@/components/ui/button";

const modules = {
  "/trips": { title: "旅行项目", icon: Route },
  "/explore": { title: "城市探索", icon: Map },
  "/documents": { title: "订单资料", icon: FileScan },
  "/tasks": { title: "规划进度", icon: Bot },
  "/actions": { title: "人工确认", icon: ShieldCheck },
  "/evaluations": { title: "质量记录", icon: ClipboardCheck },
} as const;

export function ModulePlaceholderPage() {
  const location = useLocation();
  const module = modules[location.pathname as keyof typeof modules] ?? modules["/trips"];

  return (
    <>
      <PageHeader
        title={module.title}
        breadcrumbs={[{ label: module.title }]}
      />
      <EmptyState
        icon={module.icon}
        title={`${module.title}尚未开放`}
        description="此功能正在准备中，暂时无法使用。"
      />
      <div className="mt-4 flex justify-center">
        <Button asChild variant="outline">
          <Link to="/">返回指挥舱</Link>
        </Button>
      </div>
    </>
  );
}
