import { z } from "zod";

const optionalOidcAuthority = z.preprocess(
  (value) => (value === "" ? undefined : value),
  z
    .string()
    .url()
    .refine((value) => {
      const url = new URL(value);
      return (
        url.protocol === "https:" ||
        (url.protocol === "http:" &&
          ["127.0.0.1", "localhost", "::1", "[::1]"].includes(url.hostname))
      );
    })
    .optional(),
);
const optionalText = z.preprocess(
  (value) => (value === "" ? undefined : value),
  z.string().trim().min(1).optional(),
);

const environmentSchema = z.object({
  VITE_API_BASE_URL: z.string().url(),
  VITE_USER_AUTH_ENABLED: z.enum(["true", "false"]),
  VITE_OIDC_AUTHORITY: optionalOidcAuthority,
  VITE_OIDC_CLIENT_ID: optionalText,
  VITE_OIDC_SCOPE: z.string().trim().min(1),
}).superRefine((value, context) => {
  if (value.VITE_USER_AUTH_ENABLED !== "true") {
    return;
  }
  if (!value.VITE_OIDC_AUTHORITY) {
    context.addIssue({
      code: "custom",
      path: ["VITE_OIDC_AUTHORITY"],
      message: "VITE_OIDC_AUTHORITY is required when user authentication is enabled.",
    });
  }
  if (!value.VITE_OIDC_CLIENT_ID) {
    context.addIssue({
      code: "custom",
      path: ["VITE_OIDC_CLIENT_ID"],
      message: "VITE_OIDC_CLIENT_ID is required when user authentication is enabled.",
    });
  }
});

const parsed = environmentSchema.safeParse({
  VITE_API_BASE_URL:
    import.meta.env.VITE_API_BASE_URL ??
    (import.meta.env.DEV ? "http://localhost:8080" : window.location.origin),
  VITE_USER_AUTH_ENABLED:
    import.meta.env.VITE_USER_AUTH_ENABLED ??
    (import.meta.env.MODE === "test" ? "false" : "true"),
  VITE_OIDC_AUTHORITY: import.meta.env.VITE_OIDC_AUTHORITY,
  VITE_OIDC_CLIENT_ID: import.meta.env.VITE_OIDC_CLIENT_ID,
  VITE_OIDC_SCOPE: import.meta.env.VITE_OIDC_SCOPE ?? "openid profile",
});

if (!parsed.success) {
  throw new Error("前端运行环境配置无效。");
}

export const environment = {
  apiBaseUrl: parsed.data.VITE_API_BASE_URL.replace(/\/+$/, ""),
  auth: {
    enabled: parsed.data.VITE_USER_AUTH_ENABLED === "true",
    authority: parsed.data.VITE_OIDC_AUTHORITY?.replace(/\/+$/, ""),
    clientId: parsed.data.VITE_OIDC_CLIENT_ID,
    scope: parsed.data.VITE_OIDC_SCOPE,
    redirectUri: `${window.location.origin}/auth/callback`,
    postLogoutRedirectUri: `${window.location.origin}/auth/logout-callback`,
  },
} as const;
