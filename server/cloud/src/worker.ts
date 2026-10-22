// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Tryptify Cloud on Cloudflare Workers: paid, private storage for a
// listener's own music. Accounts and billing live in D1 (binding DB); music
// will live in R2 (phase 1). See ../README.md.

import { createApp } from "./app";
import { supabaseAuth } from "./auth";
import { creemBilling } from "./creem";
import { cleanup } from "./cleanup";
import type { Bucket } from "./library";
import type { Db } from "./store";

export interface Env {
  DB?: Db;
  /** The R2 bucket for listeners' music. */
  MUSIC?: Bucket;
  SUPABASE_URL?: string;
  SUPABASE_ANON_KEY?: string;
  [key: string]: unknown;
}

// The app's own project and its public key: they identify it, they grant nothing.
const DEFAULT_SUPABASE_URL = "https://lvzorvfhhopillzlwgau.supabase.co";
const DEFAULT_SUPABASE_ANON_KEY =
  "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6Imx2em9ydmZoaG9waWxsemx3Z2F1Iiwicm9sZSI6ImFub24iLCJpYXQiOjE3NzQzNTc0NDQsImV4cCI6MjA4OTkzMzQ0NH0.Y_TN9r19WS96HyVZSQeNa0TyOqyBGuqFARaj8-7Ylow";

// Built once per isolate: the JWKS and sign-in caches live in it.
let app: ReturnType<typeof createApp> | null = null;

export default {
  fetch(request: Request, env: Env, ctx: unknown): Response | Promise<Response> {
    if (!app) {
      const strings = Object.fromEntries(
        Object.entries(env).filter(([, v]) => typeof v === "string"),
      ) as Record<string, string>;
      app = createApp({
        db: env.DB ?? null,
        storage: env.MUSIC ?? null,
        billing: creemBilling(strings),
        personOf: supabaseAuth(env.SUPABASE_URL ?? DEFAULT_SUPABASE_URL, env.SUPABASE_ANON_KEY ?? DEFAULT_SUPABASE_ANON_KEY),
        playKey: strings.PLAY_KEY ?? null,
      });
    }
    return app.fetch(request, env, ctx as never);
  },

  /** The daily cleanup (wrangler.toml [triggers]). */
  async scheduled(_event: unknown, env: Env): Promise<void> {
    if (!env.DB || !env.MUSIC) return;
    const result = await cleanup(env.DB, env.MUSIC, new Date());
    console.log(`cleanup: ${result.wiped} libraries deleted, ${result.aborted} stale uploads aborted`);
  },
};
