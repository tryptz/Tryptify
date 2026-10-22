// Tryptify Connect on Cloudflare Workers: which of a listener's devices are
// online, what plays on which, and moving it from one to another, like
// Spotify Connect. Each account gets one room (a Durable Object, src/room.ts);
// this entry point only checks who is connecting and sends them to it.
// See ../README.md for the protocol.

import { supabaseAuth } from "./auth";
import { tokenFrom } from "./protocol";
export { Room } from "./room";

export interface Env {
  ROOMS: DurableObjectNamespace;
  SUPABASE_URL?: string;
  SUPABASE_ANON_KEY?: string;
  /** Extra browser origins allowed to connect, comma-separated (a staging site). */
  ALLOWED_ORIGINS?: string;
  /** "1" under `wrangler dev` only: accepts "dev.<account uuid>" tokens, from localhost alone. */
  DEV_TOKENS?: string;
}

// The app's own project and its public key: they identify it, they grant nothing.
const SUPABASE_URL = "https://lvzorvfhhopillzlwgau.supabase.co";
const SUPABASE_ANON_KEY =
  "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6Imx2em9ydmZoaG9waWxsemx3Z2F1Iiwicm9sZSI6ImFub24iLCJpYXQiOjE3NzQzNTc0NDQsImV4cCI6MjA4OTkzMzQ0NH0.Y_TN9r19WS96HyVZSQeNa0TyOqyBGuqFARaj8-7Ylow";
const ORIGINS = ["https://tryptify.com", "https://www.tryptify.com"];
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

let personOf: ((token: string) => Promise<string | null>) | null = null;

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);
    if (url.pathname === "/") return new Response("Tryptify Connect\n", { headers: { "content-type": "text/plain" } });
    if (url.pathname !== "/v1/connect") return new Response("Not found.", { status: 404 });
    if (request.headers.get("upgrade")?.toLowerCase() !== "websocket") return new Response("This is a WebSocket.", { status: 426 });

    // Browsers say where they come from; the app sends no Origin.
    const origin = request.headers.get("origin");
    const allowed = [...ORIGINS, ...(env.ALLOWED_ORIGINS ?? "").split(",").map((s) => s.trim()).filter(Boolean)];
    // This computer: by the address asked for and, when Cloudflare says who
    // connected (always, once deployed), by that too.
    const ip = request.headers.get("cf-connecting-ip");
    const local = /^(localhost|127\.0\.0\.1|\[::1\])$/.test(url.hostname) && (ip === null || /^(127\.|::1$|::ffff:127\.)/.test(ip));
    if (origin && !allowed.includes(origin) && !(local && env.DEV_TOKENS === "1")) return new Response("Not from here.", { status: 403 });

    const { token, protocol } = tokenFrom(request.headers);
    if (!token) return new Response("Sign in first.", { status: 401 });
    let uid: string | null = null;
    if (env.DEV_TOKENS === "1" && local && token.startsWith("dev.") && UUID.test(token.slice(4))) uid = token.slice(4).toLowerCase();
    else {
      personOf ??= supabaseAuth(env.SUPABASE_URL ?? SUPABASE_URL, env.SUPABASE_ANON_KEY ?? SUPABASE_ANON_KEY);
      try { uid = await personOf(token); } catch { return new Response("Sign-in could not be checked.", { status: 503 }); }
    }
    if (!uid) return new Response("Sign in again.", { status: 401 });

    const room = env.ROOMS.get(env.ROOMS.idFromName(uid));
    const headers = new Headers(request.headers);
    headers.delete("authorization");
    headers.set("x-connect-protocol", protocol ? "1" : "0");
    return room.fetch(new Request(request.url, { headers }));
  },
};
