import { userIdFromAccessToken } from "./oidc-session";

function jwt(payload: Record<string, unknown>) {
  const encode = (value: Record<string, unknown>) =>
    btoa(JSON.stringify(value))
      .replace(/\+/g, "-")
      .replace(/\//g, "_")
      .replace(/=+$/, "");
  return `${encode({ alg: "RS256", typ: "JWT" })}.${encode(payload)}.signature`;
}

describe("OIDC access token identity", () => {
  it("uses a UUID subject from the access token", () => {
    expect(
      userIdFromAccessToken(
        jwt({ sub: "018f0e31-6ec8-7a59-b871-0242ac120001" }),
      ),
    ).toBe("018f0e31-6ec8-7a59-b871-0242ac120001");
  });

  it.each([
    ["opaque-token"],
    [jwt({})],
    [jwt({ sub: "self-reported-user" })],
  ])("rejects an unusable identity token", (token) => {
    expect(() => userIdFromAccessToken(token)).toThrow();
  });
});
