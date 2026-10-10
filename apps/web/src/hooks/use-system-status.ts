import { useQuery } from "@tanstack/react-query";
import { apiAdapter } from "@/api/client";

export const systemStatusQueryKey = ["system", "status"] as const;

export function useSystemStatus() {
  return useQuery({
    queryKey: systemStatusQueryKey,
    queryFn: apiAdapter.system.getStatus,
    refetchInterval: 30_000,
    retry: 1,
    staleTime: 15_000,
  });
}
