import { beforeEach, describe, expect, it } from "vitest";
import { useAppStore } from "./app-store";

describe("notification store", () => {
  beforeEach(() => {
    useAppStore.setState({ notifications: [] });
  });

  it("deduplicates identical retry notifications", () => {
    const notification = {
      tone: "error" as const,
      title: "当前账号无法执行此操作",
      message: "请联系行程管理员。",
    };

    useAppStore.getState().addNotification(notification);
    useAppStore.getState().addNotification(notification);

    expect(useAppStore.getState().notifications).toHaveLength(1);
  });

  it("keeps notifications with distinct details", () => {
    useAppStore.getState().addNotification({
      tone: "error",
      title: "暂时无法完成此操作",
      message: "问题编号：trace-a",
    });
    useAppStore.getState().addNotification({
      tone: "error",
      title: "暂时无法完成此操作",
      message: "问题编号：trace-b",
    });

    expect(useAppStore.getState().notifications).toHaveLength(2);
  });
});
