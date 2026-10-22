// Who a connection is, from the app's Supabase session. A token signed with
// one of the project's asymmetric keys is checked against its public keys
// (JWKS); one signed with the project's shared secret is checked by asking
// Supabase Auth who it is. Connect holds no Supabase secret.

import { createRemoteJWKSet, decodeJwt, decodeProtectedHeader, jwtVerify } from "jose";

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/** Returns `personOf(token)`: the account [token] belongs to, or null when it is not a live session. */
export function supabaseAuth(supabaseUrl: string, anonKey: string, fetcher: typeof fetch = fetch) {
  const issuer = `${supabaseUrl.replace(/\/+$/, "")}/auth/v1`;
  const jwks = createRemoteJWKSet(new URL(`${issuer}/.well-known/jwks.json`));
  // Shared-secret tokens Supabase vouched for, by hash, until they expire (five minutes at most).
  const vouched = new Map<string, { uid: string; until: number }>();
  // And the ones it turned away, for half a minute: a reconnecting device
  // with a dead token costs Supabase one question, not one per attempt.
  const refused = new Map<string, number>();

  return async function personOf(token: string): Promise<string | null> {
    let alg: string | undefined, exp = 0, iss: unknown;
    try {
      alg = decodeProtectedHeader(token).alg;
      const claims = decodeJwt(token);
      exp = Number(claims.exp ?? 0);
      iss = claims.iss;
    } catch {
      return null;
    }
    if (exp * 1000 <= Date.now()) return null;
    if (alg !== "HS256") {
      try {
        const { payload } = await jwtVerify(token, jwks, { issuer, audience: "authenticated" });
        return typeof payload.sub === "string" && UUID.test(payload.sub) ? payload.sub : null;
      } catch {
        return null;
      }
    }
    const digest = new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(token)));
    const key = Array.from(digest, (b) => b.toString(16).padStart(2, "0")).join("");
    const now = Date.now(), hit = vouched.get(key);
    if (hit && hit.until > now) return hit.uid;
    if (iss !== issuer || (refused.get(key) ?? 0) > now) return null;
    const refuse = () => {
      if (refused.size > 10_000) refused.clear();
      refused.set(key, now + 30_000);
      return null;
    };
    const res = await fetcher(`${issuer}/user`, { headers: { apikey: anonKey, authorization: `Bearer ${token}` }, signal: AbortSignal.timeout(10_000) });
    if (res.status >= 500) throw new Error("Supabase Auth is unreachable");
    if (!res.ok) return refuse();
    const user = (await res.json().catch(() => null)) as { id?: unknown } | null;
    if (typeof user?.id !== "string" || !UUID.test(user.id)) return refuse();
    if (vouched.size > 10_000) vouched.clear();
    vouched.set(key, { uid: user.id, until: Math.min(exp * 1000, now + 5 * 60_000) });
    return user.id;
  };
}
