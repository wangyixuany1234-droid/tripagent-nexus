import { useQuery } from "@tanstack/react-query";
import { apiAdapter } from "@/api/client";

export const systemHealthQueryKey = ["system", "health"] as const;

export function useSystemHealth() {
  return useQuery({
    queryKey: systemHealthQueryKey,
    queryFn: apiAdapter.system.getHealth,
    refetchInterval: 30_000,
    retry: 1,
    staleTime: 15_000,
  });
}
