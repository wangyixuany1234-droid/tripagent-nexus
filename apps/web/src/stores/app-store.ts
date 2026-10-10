import { create } from "zustand";
import { persist } from "zustand/middleware";

export type ThemeMode = "light" | "dark" | "system";
export type NotificationTone = "info" | "success" | "warning" | "error";

export interface AppNotification {
  id: string;
  title: string;
  message?: string;
  tone: NotificationTone;
  createdAt: Date;
}

interface AppState {
  theme: ThemeMode;
  sidebarOpen: boolean;
  notifications: AppNotification[];
  lastTraceId?: string;
  setTheme: (theme: ThemeMode) => void;
  setSidebarOpen: (open: boolean) => void;
  addNotification: (notification: Omit<AppNotification, "id" | "createdAt">) => void;
  removeNotification: (id: string) => void;
  clearNotifications: () => void;
  setLastTraceId: (traceId?: string) => void;
}

function applyTheme(theme: ThemeMode) {
  const prefersDark = window.matchMedia("(prefers-color-scheme: dark)").matches;
  document.documentElement.classList.toggle(
    "dark",
    theme === "dark" || (theme === "system" && prefersDark),
  );
  document.documentElement.style.colorScheme =
    theme === "system" ? (prefersDark ? "dark" : "light") : theme;
}

export const useAppStore = create<AppState>()(
  persist(
    (set) => ({
      theme: "system",
      sidebarOpen: false,
      notifications: [],
      setTheme: (theme) => {
        applyTheme(theme);
        set({ theme });
      },
      setSidebarOpen: (sidebarOpen) => set({ sidebarOpen }),
      addNotification: (notification) =>
        set((state) => {
          const duplicate = state.notifications.some(
            (item) =>
              item.title === notification.title &&
              item.message === notification.message,
          );
          if (duplicate) {
            return state;
          }
          return {
            notifications: [
              {
                ...notification,
                id: crypto.randomUUID(),
                createdAt: new Date(),
              },
              ...state.notifications,
            ].slice(0, 20),
          };
        }),
      removeNotification: (id) =>
        set((state) => ({
          notifications: state.notifications.filter((item) => item.id !== id),
        })),
      clearNotifications: () => set({ notifications: [] }),
      setLastTraceId: (lastTraceId) => set({ lastTraceId }),
    }),
    {
      name: "tripagent-ui",
      partialize: (state) => ({ theme: state.theme }),
      onRehydrateStorage: () => (state) => {
        applyTheme(state?.theme ?? "system");
      },
    },
  ),
);

export function initializeTheme() {
  applyTheme(useAppStore.getState().theme);
  const media = window.matchMedia("(prefers-color-scheme: dark)");
  const listener = () => {
    if (useAppStore.getState().theme === "system") {
      applyTheme("system");
    }
  };
  media.addEventListener("change", listener);
  return () => media.removeEventListener("change", listener);
}
