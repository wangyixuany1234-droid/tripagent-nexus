import { MapPinOff } from "lucide-react";
import { Link } from "react-router-dom";
import { EmptyState } from "@/components/states/state-panel";
import { Button } from "@/components/ui/button";

export function NotFoundPage() {
  return (
    <div className="py-12">
      <EmptyState
        icon={MapPinOff}
        title="页面不存在"
        description="当前地址没有对应页面。"
      />
      <div className="mt-4 flex justify-center">
        <Button asChild>
          <Link to="/">返回指挥舱</Link>
        </Button>
      </div>
    </div>
  );
}
