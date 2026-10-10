import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from "react";
import type { User } from "oidc-client-ts";
import { environment } from "@/lib/environment";
import {
  applyOidcUser,
  clearOidcSession,
  oidcUserManager,
  type AuthSession,
} from "./oidc-session";

type AuthStatus =
  | "disabled"
  | "loading"
  | "authenticated"
  | "anonymous"
  | "error";

interface AuthContextValue {
  enabled: boolean;
  session?: AuthSession;
  status: AuthStatus;
  completeSignIn: () => Promise<string>;
  completeSignOut: () => Promise<void>;
  completeSilentSignIn: () => Promise<void>;
  signIn: (returnTo: string) => Promise<void>;
  signOut: () => Promise<void>;
}

const AuthContext = createContext<AuthContextValue | undefined>(undefined);

function localReturnPath(value: unknown) {
  return typeof value === "string" &&
    value.startsWith("/") &&
    !value.startsWith("//")
    ? value
    : "/";
}

function returnPathFromUser(user: User) {
  if (!user.state || typeof user.state !== "object") {
    return "/";
  }
  return localReturnPath((user.state as Record<string, unknown>).returnTo);
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [status, setStatus] = useState<AuthStatus>(
    environment.auth.enabled ? "loading" : "disabled",
  );
  const [session, setSession] = useState<AuthSession>();

  const acceptUser = useCallback((user: User) => {
    const accepted = applyOidcUser(user);
    setSession(accepted);
    setStatus("authenticated");
    return accepted;
  }, []);

  const removeUser = useCallback(() => {
    clearOidcSession();
    setSession(undefined);
    setStatus("anonymous");
  }, []);

  useEffect(() => {
    const manager = oidcUserManager;
    if (!manager) {
      setStatus("disabled");
      return;
    }

    const onUserLoaded = (user: User) => {
      try {
        acceptUser(user);
      } catch {
        removeUser();
      }
    };
    const onUserUnloaded = () => removeUser();
    const onAccessTokenExpired = () => {
      void manager.removeUser().finally(removeUser);
    };
    const onSessionExpired = () => {
      void manager.removeUser().finally(removeUser);
    };

    manager.events.addUserLoaded(onUserLoaded);
    manager.events.addUserUnloaded(onUserUnloaded);
    manager.events.addAccessTokenExpired(onAccessTokenExpired);
    window.addEventListener("tripagent:session-expired", onSessionExpired);

    void manager
      .getUser()
      .then((user) => {
        if (user && !user.expired) {
          acceptUser(user);
        } else {
          removeUser();
        }
      })
      .catch(() => {
        clearOidcSession();
        setSession(undefined);
        setStatus("error");
      });

    return () => {
      manager.events.removeUserLoaded(onUserLoaded);
      manager.events.removeUserUnloaded(onUserUnloaded);
      manager.events.removeAccessTokenExpired(onAccessTokenExpired);
      window.removeEventListener("tripagent:session-expired", onSessionExpired);
    };
  }, [acceptUser, removeUser]);

  const signIn = useCallback(async (returnTo: string) => {
    if (!oidcUserManager) {
      return;
    }
    setStatus("loading");
    try {
      await oidcUserManager.signinRedirect({
        state: { returnTo: localReturnPath(returnTo) },
      });
    } catch (cause) {
      setStatus("error");
      throw cause;
    }
  }, []);

  const completeSignIn = useCallback(async () => {
    if (!oidcUserManager) {
      return "/";
    }
    try {
      const user = await oidcUserManager.signinRedirectCallback();
      acceptUser(user);
      return returnPathFromUser(user);
    } catch (cause) {
      clearOidcSession();
      setSession(undefined);
      setStatus("error");
      throw cause;
    }
  }, [acceptUser]);

  const completeSilentSignIn = useCallback(async () => {
    if (oidcUserManager) {
      await oidcUserManager.signinSilentCallback();
    }
  }, []);

  const signOut = useCallback(async () => {
    if (!oidcUserManager) {
      removeUser();
      return;
    }
    try {
      await oidcUserManager.signoutRedirect();
    } catch (cause) {
      await oidcUserManager.removeUser();
      removeUser();
      throw cause;
    }
  }, [removeUser]);

  const completeSignOut = useCallback(async () => {
    if (oidcUserManager) {
      await oidcUserManager.signoutRedirectCallback();
      await oidcUserManager.removeUser();
    }
    removeUser();
  }, [removeUser]);

  const value = useMemo<AuthContextValue>(
    () => ({
      enabled: environment.auth.enabled,
      session,
      status,
      completeSignIn,
      completeSignOut,
      completeSilentSignIn,
      signIn,
      signOut,
    }),
    [
      completeSignIn,
      completeSignOut,
      completeSilentSignIn,
      session,
      signIn,
      signOut,
      status,
    ],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth() {
  const value = useContext(AuthContext);
  if (!value) {
    throw new Error("useAuth must be used within AuthProvider.");
  }
  return value;
}
