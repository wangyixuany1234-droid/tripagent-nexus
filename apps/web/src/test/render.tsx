import type { ReactElement } from "react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { TooltipProvider } from "@/components/ui/tooltip";

export function renderWithProviders(
  element: ReactElement,
  {
    route = "/",
    queryClient = new QueryClient({
      defaultOptions: {
        queries: { retry: false },
        mutations: { retry: false },
      },
    }),
  }: {
    route?: string;
    queryClient?: QueryClient;
  } = {},
) {
  window.history.pushState({}, "", route);
  return {
    queryClient,
    ...render(
      <QueryClientProvider client={queryClient}>
        <TooltipProvider delayDuration={0}>
          <MemoryRouter initialEntries={[route]}>{element}</MemoryRouter>
        </TooltipProvider>
      </QueryClientProvider>,
    ),
  };
}
