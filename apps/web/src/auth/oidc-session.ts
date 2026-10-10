import {
  UserManager,
  WebStorageStateStore,
  type User,
} from "oidc-client-ts";
import { setAccessTokenProvider } from "@/api/client";
import { environment } from "@/lib/environment";
import { useTripFlowStore } from "@/stores/trip-flow-store";

export interface AuthSession {
  accessToken: string;
  displayName: string;
  userId: string;
}

let currentSession: AuthSession | undefined;

setAccessTokenProvider(() => currentSession?.accessToken);

function decodeJwtPayload(accessToken: string): Record<string, unknown> {
  const segments = accessToken.split(".");
  if (segments.length !== 3) {
    throw new Error("The access token is not a JWT.");
  }
  const encoded = segments[1].replace(/-/g, "+").replace(/_/g, "/");
  const padded = encoded.padEnd(Math.ceil(encoded.length / 4) * 4, "=");
  const bytes = Uint8Array.from(atob(padded), (character) =>
    character.charCodeAt(0),
  );
  const payload: unknown = JSON.parse(new TextDecoder().decode(bytes));
  if (!payload || typeof payload !== "object" || Array.isArray(payload)) {
    throw new Error("The access token payload is invalid.");
  }
  return payload as Record<string, unknown>;
}

export function userIdFromAccessToken(accessToken: string) {
  const subject = decodeJwtPayload(accessToken).sub;
  if (
    typeof subject !== "string" ||
    !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(
      subject,
    )
  ) {
    throw new Error("The access token subject must be a UUID.");
  }
  return subject;
}

export function sessionFromUser(user: User): AuthSession {
  if (user.expired || !user.access_token) {
    throw new Error("The OIDC session has expired.");
  }
  const userId = userIdFromAccessToken(user.access_token);
  if (user.profile.sub && user.profile.sub !== userId) {
    throw new Error("The OIDC subject does not match the access token.");
  }
  const displayName =
    [user.profile.name, user.profile.preferred_username, user.profile.email]
      .find((value): value is string => typeof value === "string" && value.trim() !== "")
      ?.trim() ?? "旅行规划师";
  return {
    accessToken: user.access_token,
    displayName,
    userId,
  };
}

export function applyOidcUser(user: User) {
  const session = sessionFromUser(user);
  currentSession = session;
  useTripFlowStore.getState().setAuthenticatedUser(session.userId);
  return session;
}

export function clearOidcSession() {
  currentSession = undefined;
  useTripFlowStore.getState().clearUserSession();
}

export function currentAuthSession() {
  return currentSession;
}

function createUserManager() {
  if (
    !environment.auth.enabled ||
    !environment.auth.authority ||
    !environment.auth.clientId
  ) {
    return undefined;
  }
  const stateStore = new WebStorageStateStore({
    store: window.sessionStorage,
  });
  return new UserManager({
    authority: environment.auth.authority,
    client_id: environment.auth.clientId,
    redirect_uri: environment.auth.redirectUri,
    post_logout_redirect_uri: environment.auth.postLogoutRedirectUri,
    silent_redirect_uri: `${window.location.origin}/auth/silent-callback`,
    response_type: "code",
    scope: environment.auth.scope,
    automaticSilentRenew: true,
    monitorSession: false,
    loadUserInfo: false,
    revokeTokensOnSignout: true,
    stateStore,
    userStore: stateStore,
  });
}

export const oidcUserManager = createUserManager();
