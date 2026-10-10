import { Laptop, LogOut, Moon, Settings, Sun, UserRound } from "lucide-react";
import { Link } from "react-router-dom";
import { useAuth } from "@/auth/auth-context";
import { useAppStore, type ThemeMode } from "@/stores/app-store";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuGroup,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuRadioGroup,
  DropdownMenuRadioItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";

const themeOptions: Array<{ value: ThemeMode; label: string; icon: typeof Sun }> = [
  { value: "light", label: "亮色", icon: Sun },
  { value: "dark", label: "深色", icon: Moon },
  { value: "system", label: "跟随系统", icon: Laptop },
];

export function UserMenu() {
  const auth = useAuth();
  const theme = useAppStore((state) => state.theme);
  const setTheme = useAppStore((state) => state.setTheme);
  const displayName = auth.session?.displayName ?? "旅行规划师";
  const initials = [...displayName.trim()].slice(0, 2).join("").toUpperCase() || "TN";

  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <button
          type="button"
          className="flex size-9 items-center justify-center rounded-full bg-navy-strong text-xs font-bold text-white transition-transform hover:scale-[1.03] dark:text-background"
          aria-label="打开用户菜单"
        >
          {initials}
        </button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end" className="w-60">
        <DropdownMenuLabel>
          <span className="block truncate text-sm text-foreground">{displayName}</span>
          <span className="mt-0.5 block font-normal">个人工作区</span>
        </DropdownMenuLabel>
        <DropdownMenuSeparator />
        <DropdownMenuGroup>
          <DropdownMenuItem>
            <UserRound />
            账户资料
          </DropdownMenuItem>
          <DropdownMenuItem asChild>
            <Link to="/preferences">
              <Settings />
              偏好设置
            </Link>
          </DropdownMenuItem>
        </DropdownMenuGroup>
        <DropdownMenuLabel>显示主题</DropdownMenuLabel>
        <DropdownMenuRadioGroup
          value={theme}
          onValueChange={(value) => setTheme(value as ThemeMode)}
        >
          {themeOptions.map((option) => (
            <DropdownMenuRadioItem key={option.value} value={option.value}>
              <option.icon className="mr-2 size-4" />
              {option.label}
            </DropdownMenuRadioItem>
          ))}
        </DropdownMenuRadioGroup>
        <DropdownMenuSeparator />
        <DropdownMenuItem
          disabled={!auth.enabled}
          onSelect={() => void auth.signOut().catch(() => undefined)}
        >
          <LogOut />
          退出登录
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  );
}
