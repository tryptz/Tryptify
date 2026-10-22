// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Tryptify Cloud end to end: the Worker's own routes (src/app.ts) over a real
// SQLite database (the engine D1 is), with stand-ins for Supabase Auth's
// public keys and for Creem's API. Needs nothing else:
//
//   bun install && bun run test.ts
//
// A plain script rather than `bun test`, like the Theme Store's.

import assert from "node:assert/strict";
import { Database } from "bun:sqlite";
import { SignJWT, exportJWK, generateKeyPair } from "jose";
import { createApp } from "./src/app";
import { supabaseAuth } from "./src/auth";
import { creemBilling } from "./src/creem";
import * as rules from "./src/rules";
import type { Db, Stmt } from "./src/store";
import type { Bucket, MultipartUpload, StoredObject } from "./src/library";
import { PART_SIZE } from "./src/library";
import { cleanup } from "./src/cleanup";
import { md5 } from "@noble/hashes/legacy.js";

const A = "11111111-1111-4111-8111-111111111111";
const B = "22222222-2222-4222-8222-222222222222";
const DAY = 86_400_000;
const GB = 1_000_000_000;

/** D1's binding over bun:sqlite: positional ?N parameters, atomic batches. */
function d1(sqlite: Database): Db {
  const stmt = (sql: string, values: unknown[] = []): Stmt => ({
    bind: (...v: unknown[]) => stmt(sql, v),
    first: async <T>() => (sqlite.query(sql).get(...(values as any[])) as T) ?? null,
    all: async <T>() => ({ results: sqlite.query(sql).all(...(values as any[])) as T[] }),
    run: async () => sqlite.query(sql).run(...(values as any[])),
    runSync: () => sqlite.query(sql).run(...(values as any[])),
  } as Stmt & { runSync: () => unknown });
  return {
    prepare: (sql) => stmt(sql),
    batch: async (statements) => sqlite.transaction(() => statements.map((s) => (s as any).runSync?.() ?? s))(),
  };
}

let ISSUER = "";
let keys: CryptoKeyPair;
let sqlite: Database;

/** R2 in memory, with the multipart rules R2 enforces (5 MiB minimum, equal non-trailing parts). */
class MemBucket implements Bucket {
  objects = new Map<string, { data: Uint8Array; type?: string }>();
  uploads = new Map<string, { key: string; type?: string; parts: Map<number, Uint8Array> }>();
  private static async bytes(v: ArrayBuffer | Uint8Array | ReadableStream | string): Promise<Uint8Array> {
    if (typeof v === "string") return new TextEncoder().encode(v);
    if (v instanceof Uint8Array) return v;
    if (v instanceof ArrayBuffer) return new Uint8Array(v);
    return new Uint8Array(await new Response(v).arrayBuffer());
  }
  async put(key: string, value: any, options?: { httpMetadata?: { contentType?: string } }) {
    const data = await MemBucket.bytes(value);
    this.objects.set(key, { data, type: options?.httpMetadata?.contentType });
    return { size: data.length };
  }
  async get(key: string, options?: { range?: any }): Promise<StoredObject | null> {
    const o = this.objects.get(key);
    if (!o) return null;
    const r = options?.range;
    const slice = r ? o.data.slice(r.offset, r.offset + (r.length ?? o.data.length)) : o.data;
    return { body: new Blob([slice]).stream(), size: o.data.length, httpMetadata: { contentType: o.type } };
  }
  async head(key: string) {
    const o = this.objects.get(key);
    return o ? { size: o.data.length } : null;
  }
  async delete(keys: string | string[]) {
    for (const k of Array.isArray(keys) ? keys : [keys]) this.objects.delete(k);
  }
  async list(options?: { prefix?: string; cursor?: string; limit?: number }) {
    const all = [...this.objects.keys()].filter((k) => k.startsWith(options?.prefix ?? "")).sort();
    const start = Number(options?.cursor ?? 0);
    const limit = options?.limit ?? 1000;
    const page = all.slice(start, start + limit);
    return { objects: page.map((key) => ({ key })), truncated: start + limit < all.length, cursor: String(start + limit) };
  }
  async createMultipartUpload(key: string, options?: { httpMetadata?: { contentType?: string } }) {
    const id = crypto.randomUUID();
    this.uploads.set(id, { key, type: options?.httpMetadata?.contentType, parts: new Map() });
    return this.resumeMultipartUpload(key, id);
  }
  resumeMultipartUpload(key: string, uploadId: string): MultipartUpload {
    const bucket = this;
    const etagOf = (b: Uint8Array) => Array.from(md5(b), (x) => x.toString(16).padStart(2, "0")).join("");
    return {
      key,
      uploadId,
      async uploadPart(n, value) {
        const u = bucket.uploads.get(uploadId);
        if (!u || u.key !== key) throw new Error("NoSuchUpload");
        const data = await MemBucket.bytes(value);
        u.parts.set(n, data);
        return { partNumber: n, etag: etagOf(data) };
      },
      async complete(parts) {
        const u = bucket.uploads.get(uploadId);
        if (!u) throw new Error("NoSuchUpload");
        const ordered = [...parts].sort((a, b) => a.partNumber - b.partNumber).map((p) => {
          const data = u.parts.get(p.partNumber);
          if (!data || etagOf(data) !== p.etag) throw new Error("InvalidPart");
          return data;
        });
        for (let i = 0; i < ordered.length - 1; i++) {
          if (ordered[i].length < 5 * 1024 * 1024 || ordered[i].length !== ordered[0].length) throw new Error("EntityTooSmall/InvalidPart");
        }
        const all = new Uint8Array(ordered.reduce((n, p) => n + p.length, 0));
        let at = 0;
        for (const p of ordered) { all.set(p, at); at += p.length; }
        bucket.objects.set(u.key, { data: all, type: u.type });
        bucket.uploads.delete(uploadId);
        return { size: all.length };
      },
      async abort() {
        bucket.uploads.delete(uploadId);
      },
    };
  }
}
let bucket: MemBucket;
let fetchCloud: (r: Request) => Promise<Response>;
const cloud = rules;

// A stand-in for Creem: what checkouts it was asked for, and its answer.
const SECRET = "whsec_test_only";
const checkouts: any[] = [];
const sign = (raw: string) => new Bun.CryptoHasher("sha256", SECRET).update(raw).digest("hex"); // independent of the code under test
async function webhook(event: unknown, signature?: string) {
  const raw = JSON.stringify(event);
  return call("POST", "/webhooks/billing", undefined, raw, { "creem-signature": signature ?? sign(raw) });
}
const paidEvent = (id: string, userId: string | null, product: string, end: Date, sub = "sub_1") => ({
  id,
  eventType: "subscription.paid",
  object: {
    id: sub,
    object: "subscription",
    product: { id: product },
    customer: { id: "cust_1" },
    current_period_end_date: end.toISOString(),
    metadata: userId ? { userId } : {},
  },
});

async function token(sub: string) {
  return new SignJWT({ role: "authenticated" })
    .setProtectedHeader({ alg: "ES256", kid: "test" })
    .setSubject(sub)
    .setIssuer(ISSUER)
    .setAudience("authenticated")
    .setIssuedAt()
    .setExpirationTime("5m")
    .sign(keys.privateKey);
}

async function call(method: string, path: string, who?: string, body?: unknown, headers: Record<string, string> = {}) {
  const h: Record<string, string> = { ...headers };
  if (who) h.authorization = `Bearer ${who}`;
  const res = await fetchCloud(
    new Request(`http://cloud.test${path}`, {
      method,
      headers: h,
      body: body === undefined ? undefined : typeof body === "string" ? body : JSON.stringify(body),
    }),
  );
  const text = await res.text();
  return { status: res.status, body: text ? JSON.parse(text) : null };
}

const PLAY_KEY = "test-play-key";

const tests: [string, () => Promise<void>][] = [];
const test = (name: string, fn: () => Promise<void>) => tests.push([name, fn]);

async function setUp() {
  sqlite = new Database(":memory:");
  sqlite.exec(await Bun.file(new URL("./schema.sql", import.meta.url)).text());
  keys = await generateKeyPair("ES256");
  const jwk = { ...(await exportJWK(keys.publicKey)), kid: "test", alg: "ES256", use: "sig" };
  const supabase = Bun.serve({
    port: 0,
    fetch(req) {
      const path = new URL(req.url).pathname;
      if (path === "/auth/v1/.well-known/jwks.json") return Response.json({ keys: [jwk] });
      return new Response("no", { status: 404 });
    },
  });
  ISSUER = `http://127.0.0.1:${supabase.port}/auth/v1`;
  const creem = Bun.serve({
    port: 0,
    async fetch(req) {
      if (req.headers.get("x-api-key") !== "creem_test_key") return new Response("no", { status: 403 });
      if (new URL(req.url).pathname === "/v1/customers/billing") {
        const { customer_id } = await req.json();
        return Response.json({ customer_portal_link: `https://creem.test/portal/${customer_id}` });
      }
      if (new URL(req.url).pathname !== "/v1/checkouts") return new Response("no", { status: 404 });
      const body = await req.json();
      checkouts.push(body);
      return Response.json({ id: "ch_1", checkout_url: `https://checkout.creem.test/ch_${checkouts.length}` });
    },
  });
  bucket = new MemBucket();
  const app = createApp({
    db: d1(sqlite),
    storage: bucket,
    personOf: supabaseAuth(`http://127.0.0.1:${supabase.port}/`, "test-anon-key"),
    playKey: PLAY_KEY,
    billing: creemBilling({
      CREEM_API_KEY: "creem_test_key",
      CREEM_WEBHOOK_SECRET: SECRET,
      CREEM_API_BASE: `http://127.0.0.1:${creem.port}`,
      CREEM_PRODUCT_LITE_MONTHLY: "prod_lite_m",
      CREEM_PRODUCT_LITE_YEARLY: "prod_lite_y",
      CREEM_PRODUCT_PLUS_MONTHLY: "prod_plus_m",
      CREEM_PRODUCT_PLUS_YEARLY: "prod_plus_y",
      CREEM_PRODUCT_MAX_MONTHLY: "prod_max_m",
      CREEM_PRODUCT_LITE_MONTHLY_TRIAL: "prod_lite_trial",
      // MAX_YEARLY left unset on purpose: that checkout must say not set up.
    }),
  });
  fetchCloud = (r) => Promise.resolve(app.fetch(r));
}

// ── The rule: nobody costs more than they pay ───────────────────────────────

const now = new Date("2026-11-01T12:00:00Z");
const days = (n: number) => new Date(now.getTime() + n * DAY);

test("no plan, or a plan with no paid-until date, grants nothing", async () => {
  assert.equal(cloud.accessOf({ plan: null, paidUntil: null, usedBytes: 0 }, now).access, "none");
  assert.equal(cloud.accessOf({ plan: "lite", paidUntil: null, usedBytes: 0 }, now).access, "none");
});

test("paid up: full access, with the plan's quota", async () => {
  const r = cloud.accessOf({ plan: "plus", paidUntil: days(10), usedBytes: 0 }, now);
  assert.equal(r.access, "full");
  assert.equal(r.quotaBytes, 250 * GB);
  assert.equal(r.deleteAfter, null);
});

test("over quota while paid up: read-only, never billed extra", async () => {
  const a = { plan: "lite" as const, paidUntil: days(10), usedBytes: 100 * GB };
  assert.equal(cloud.accessOf(a, now).access, "read_only");
  assert.equal(cloud.mayUpload(a, 1, now), false);
});

test("an upload must fit under the quota in full", async () => {
  const a = { plan: "lite" as const, paidUntil: days(10), usedBytes: 99 * GB };
  assert.equal(cloud.mayUpload(a, GB, now), true);
  assert.equal(cloud.mayUpload(a, GB + 1, now), false);
  assert.equal(cloud.mayUpload(a, 0, now), false);
});

test("lapsed: read-only for the grace days, then nothing", async () => {
  const lapsed = (d: number) => ({ plan: "max" as const, paidUntil: days(-d), usedBytes: GB });
  assert.equal(cloud.accessOf(lapsed(0), now).access, "read_only");
  assert.equal(cloud.accessOf(lapsed(cloud.READ_ONLY_DAYS - 1), now).access, "read_only");
  assert.equal(cloud.mayUpload(lapsed(1), 1, now), false);
  assert.equal(cloud.accessOf(lapsed(cloud.READ_ONLY_DAYS), now).access, "none");
});

test("only a payment extends the paid-until date, and never backwards", async () => {
  const base = { plan: "lite" as const, paidUntil: days(20), usedBytes: 0 };
  const paid = (until: Date, plan: "lite" | "plus" = "lite") =>
    ({ kind: "paid", eventId: "e", userId: A, plan, paidUntil: until }) as const;
  assert.deepEqual(cloud.applyEvent(base, paid(days(50)), now).paidUntil, days(50));
  // An older renewal arriving late does not take time away.
  assert.deepEqual(cloud.applyEvent(base, paid(days(5)), now).paidUntil, days(20));
  // An upgrade takes effect with its payment.
  assert.equal(cloud.applyEvent(base, paid(days(30), "plus"), now).plan, "plus");
});

test("a refund ends access now and leaves only the short export window", async () => {
  const a = cloud.applyEvent(
    { plan: "plus", paidUntil: days(300), usedBytes: GB },
    { kind: "refunded", eventId: "r", userId: A },
    now,
  );
  const r = cloud.accessOf(a, now);
  assert.equal(r.access, "read_only");
  assert.deepEqual(r.deleteAfter, days(cloud.REFUND_EXPORT_DAYS));
  assert.equal(cloud.accessOf(a, days(cloud.REFUND_EXPORT_DAYS)).access, "none");
});

test("every plan is a profit in the worst case (the pricing check, kept)", async () => {
  // Worst path: web checkout, non-US PayPal, 27% VAT, a full quota.
  const STORE = 0.015, SAFETY = 1.15, VAT = 1.27;
  for (const [id, p] of Object.entries(cloud.PLANS)) {
    const cost = p.storageGb * STORE * SAFETY;
    for (const [price, months] of [[p.monthly, 1], [p.yearly, 12]] as const) {
      const worst = Math.min(
        (price / VAT) * 0.85,
        price / VAT - (0.085 * price + 0.5),
        price - (0.055 * price + 0.5),
      ) / months;
      assert.ok(worst - cost > 0, `${id} at ${price}: keeps ${worst.toFixed(2)}, costs ${cost.toFixed(2)}`);
    }
  }
});

test("a trial gets 20 GB until it ends, never the plan's full quota", async () => {
  const t = cloud.applyEvent(
    { plan: null, paidUntil: null, usedBytes: 0 },
    { kind: "paid", eventId: "t", userId: A, plan: "max", paidUntil: days(7), trial: true },
    now,
  );
  const r = cloud.accessOf(t, now);
  assert.equal(r.access, "full");
  assert.equal(r.quotaBytes, cloud.TRIAL_GB * GB);
  assert.equal(cloud.mayUpload({ ...t, usedBytes: 19 * GB }, 2 * GB, now), false);
});

test("a trial that ends unpaid leaves only the short export window", async () => {
  const t = { plan: "lite" as const, paidUntil: days(-1), usedBytes: GB, trial: true };
  assert.equal(cloud.accessOf(t, now).access, "read_only");
  assert.deepEqual(cloud.accessOf(t, now).deleteAfter, days(cloud.REFUND_EXPORT_DAYS - 1));
  assert.equal(cloud.accessOf(t, days(cloud.REFUND_EXPORT_DAYS)).access, "none");
});

test("the first real payment after a trial gives the whole plan", async () => {
  const t = { plan: "plus" as const, paidUntil: days(7), usedBytes: 0, trial: true };
  const paid = cloud.applyEvent(t, { kind: "paid", eventId: "p", userId: A, plan: "plus", paidUntil: days(37) }, now);
  assert.equal(paid.trial, false);
  assert.equal(cloud.accessOf(paid, now).quotaBytes, 250 * GB);
});

test("a trial never replaces paid time, and an account that had a plan gets no trial", async () => {
  const trialEvent = { kind: "paid", eventId: "t", userId: A, plan: "lite", paidUntil: days(7), trial: true } as const;
  const paying = { plan: "max" as const, paidUntil: days(20), usedBytes: 0, trial: false };
  assert.deepEqual(cloud.applyEvent(paying, trialEvent, now), paying);
  const lapsed = { plan: "lite" as const, paidUntil: days(-40), usedBytes: 0, trial: false };
  assert.equal(cloud.mayStartTrial(lapsed), false);
  assert.deepEqual(cloud.applyEvent(lapsed, trialEvent, now), lapsed);
  assert.equal(cloud.mayStartTrial({ plan: null, paidUntil: null, usedBytes: 0 }), true);
});

test("the trial's worst case costs pennies, not a plan", async () => {
  const cost = cloud.TRIAL_GB * 0.015 * 1.15 * (cloud.TRIAL_DAYS + cloud.REFUND_EXPORT_DAYS) / 30;
  assert.ok(cost < 0.15, `a trial can cost ${cost.toFixed(2)}`);
});

// ── Routes ───────────────────────────────────────────────────────────────────

test("health and plans answer without an account", async () => {
  assert.equal((await call("GET", "/health")).body.ok, true);
  assert.equal((await call("GET", "/health")).body.database, true);
  const plans = await call("GET", "/plans");
  assert.deepEqual(plans.body.plans.map((p: { id: string }) => p.id), ["lite", "plus", "max"]);
});

test("the account needs a sign-in", async () => {
  assert.equal((await call("GET", "/v1/account")).status, 401);
  assert.equal((await call("GET", "/v1/account", "not-a-token")).status, 401);
});

test("a new person has no plan and no access", async () => {
  const r = await call("GET", "/v1/account", await token(A));
  assert.equal(r.status, 200);
  assert.deepEqual(r.body, {
    plan: null, access: "none", paidUntil: null, deleteAfter: null, usedBytes: 0, quotaBytes: 0,
    trial: false, trialAvailable: true,
  });
});

test("an account row someone paid for shows its plan", async () => {
  sqlite.query("insert into cloud_accounts (user_id, plan, paid_until) values (?1, 'plus', ?2)").run(B, days(400).toISOString());
  const r = await call("GET", "/v1/account", await token(B));
  assert.equal(r.body.plan, "plus");
  assert.equal(r.body.access, "full");
  assert.equal(r.body.quotaBytes, 250 * GB);
  // A sees only A's account.
  assert.equal((await call("GET", "/v1/account", await token(A))).body.plan, null);
});

// ── Creem ────────────────────────────────────────────────────────────────────

const future = (d: number) => new Date(Date.now() + d * DAY);
const C = "33333333-3333-4333-8333-333333333333";

test("checkout asks Creem for the plan's product, carrying the person's id", async () => {
  const r = await call("POST", "/v1/checkout", await token(A), { plan: "plus", period: "yearly" });
  assert.equal(r.status, 200);
  assert.match(r.body.url, /^https:\/\/checkout\.creem\.test\//);
  const sent = checkouts.at(-1);
  assert.equal(sent.product_id, "prod_plus_y");
  assert.deepEqual(sent.metadata, { userId: A });
});

test("checkout refuses a bad plan, needs a sign-in, and says when a product is missing", async () => {
  assert.equal((await call("POST", "/v1/checkout", await token(A), { plan: "gold", period: "monthly" })).status, 400);
  assert.equal((await call("POST", "/v1/checkout", undefined, { plan: "lite", period: "monthly" })).status, 401);
  const r = await call("POST", "/v1/checkout", await token(A), { plan: "max", period: "yearly" });
  assert.equal(r.status, 503);
  assert.equal(r.body.error, "billing_not_set_up");
});

test("the billing portal opens for a paying customer, and only for them", async () => {
  const P = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
  assert.equal((await call("POST", "/v1/billing-portal", undefined)).status, 401);
  const none = await call("POST", "/v1/billing-portal", await token(P));
  assert.equal(none.status, 404);
  assert.equal(none.body.error, "no_subscription");
  const paid = paidEvent("evt_portal", P, "prod_lite_m", future(30), "sub_portal");
  paid.object.customer = { id: "cust_portal" };
  assert.equal((await webhook(paid)).status, 200);
  const r = await call("POST", "/v1/billing-portal", await token(P));
  assert.equal(r.status, 200);
  assert.equal(r.body.url, "https://creem.test/portal/cust_portal");
});

test("a webhook with a wrong or missing signature changes nothing", async () => {
  const e = paidEvent("evt_forged", C, "prod_max_m", future(30));
  assert.equal((await webhook(e, "00".repeat(32))).status, 401);
  assert.equal((await webhook(e, "")).status, 401);
  assert.equal((await call("GET", "/v1/account", await token(C))).body.access, "none");
});

test("subscription.paid grants the plan until the period's end", async () => {
  const end = future(30);
  assert.equal((await webhook(paidEvent("evt_p1", C, "prod_lite_m", end, "sub_c"))).status, 200);
  const r = (await call("GET", "/v1/account", await token(C))).body;
  assert.equal(r.plan, "lite");
  assert.equal(r.access, "full");
  assert.equal(r.paidUntil, end.toISOString());
});

test("a redelivered webhook is applied once", async () => {
  const again = await webhook(paidEvent("evt_p1", C, "prod_lite_m", future(30), "sub_c"));
  assert.equal(again.status, 200);
  const { n } = sqlite.query("select count(*) as n from cloud_billing_events where event_id = 'evt_p1'").get() as { n: number };
  assert.equal(n, 1);
});

test("a renewal moves the date on; an upgrade changes the plan", async () => {
  const end = future(60);
  await webhook(paidEvent("evt_p2", C, "prod_plus_m", end, "sub_c"));
  const r = (await call("GET", "/v1/account", await token(C))).body;
  assert.equal(r.plan, "plus");
  assert.equal(r.paidUntil, end.toISOString());
});

test("an unknown product or a payment naming nobody grants nothing", async () => {
  const D = "44444444-4444-4444-8444-444444444444";
  await webhook(paidEvent("evt_u1", D, "prod_someone_elses", future(30), "sub_d"));
  await webhook(paidEvent("evt_u2", null, "prod_lite_m", future(30), "sub_unknown"));
  assert.equal((await call("GET", "/v1/account", await token(D))).body.access, "none");
});

test("cancelled, past due and expired need nothing: access lapses at the paid-until date", async () => {
  for (const type of ["subscription.canceled", "subscription.past_due", "subscription.expired"]) {
    assert.equal((await webhook({ id: `evt_${type}`, eventType: type, object: { id: "sub_c" } })).status, 200);
  }
  assert.equal((await call("GET", "/v1/account", await token(C))).body.access, "full");
});

test("a partial refund keeps access; a full refund, found by subscription, ends it", async () => {
  const refund = (id: string, amount: number) => ({
    id,
    eventType: "refund.created",
    object: { refund_amount: amount, transaction: { amount_paid: 1210 }, subscription: { id: "sub_c", metadata: {} } },
  });
  await webhook(refund("evt_r1", 300));
  assert.equal((await call("GET", "/v1/account", await token(C))).body.access, "full");
  await webhook(refund("evt_r2", 1210));
  const r = (await call("GET", "/v1/account", await token(C))).body;
  assert.equal(r.access, "read_only");
  const left = new Date(r.deleteAfter).getTime() - Date.now();
  assert.ok(left > (cloud.REFUND_EXPORT_DAYS - 0.1) * DAY && left <= cloud.REFUND_EXPORT_DAYS * DAY, `export window ${left}`);
});

test("a chargeback ends access like a refund", async () => {
  const E = "55555555-5555-4555-8555-555555555555";
  await webhook(paidEvent("evt_e1", E, "prod_max_m", future(30), "sub_e"));
  assert.equal((await call("GET", "/v1/account", await token(E))).body.access, "full");
  await webhook({ id: "evt_d1", eventType: "dispute.created", object: { transaction: { subscription: "sub_e" } } });
  assert.equal((await call("GET", "/v1/account", await token(E))).body.access, "read_only");
});

test("trial checkout: monthly only, the trial product, once per account", async () => {
  const T = "66666666-6666-4666-8666-666666666666";
  const ok = await call("POST", "/v1/checkout", await token(T), { plan: "lite", period: "monthly", trial: true });
  assert.equal(ok.status, 200);
  assert.equal(checkouts.at(-1).product_id, "prod_lite_trial");
  assert.equal((await call("POST", "/v1/checkout", await token(T), { plan: "lite", period: "yearly", trial: true })).status, 400);
  assert.equal((await call("GET", "/v1/account", await token(T))).body.trialAvailable, true);
  // C already has a plan from the tests above: no trial.
  const used = await call("POST", "/v1/checkout", await token(C), { plan: "lite", period: "monthly", trial: true });
  assert.equal(used.status, 409);
  assert.equal(used.body.error, "trial_used");
});

test("Creem's real trial start (a $0 subscription.paid, status trialing) grants the trial only", async () => {
  // Shaped as captured from Creem's test mode on 2026-10-21.
  const T = "77777777-7777-4777-8777-777777777777";
  const end = future(7);
  const started = {
    id: "evt_trial_paid",
    eventType: "subscription.paid",
    object: {
      id: "sub_trial", object: "subscription", status: "trialing",
      product: { id: "prod_lite_trial" }, customer: { id: "cust_t" },
      current_period_end_date: end.toISOString(), metadata: { userId: T },
    },
  };
  await webhook(started);
  await webhook({ ...started, id: "evt_trialing", eventType: "subscription.trialing" });
  let r = (await call("GET", "/v1/account", await token(T))).body;
  assert.equal(r.trial, true);
  assert.equal(r.access, "full");
  assert.equal(r.quotaBytes, cloud.TRIAL_GB * GB);
  assert.equal(r.trialAvailable, false);
  // Day 7: the card is charged for the first month.
  await webhook({ ...started, id: "evt_first_charge", object: { ...started.object, status: "active", current_period_end_date: future(37).toISOString() } });
  r = (await call("GET", "/v1/account", await token(T))).body;
  assert.equal(r.trial, false);
  assert.equal(r.quotaBytes, 100 * GB);
});

test("two payments racing for one account both land, each once", async () => {
  const { record } = await import("./src/store");
  const R = "88888888-8888-4888-8888-888888888888";
  const base = d1(sqlite);
  let interleaved = false;
  const later = future(60);
  // A Db whose first batch lets a second webhook for the same person write
  // first: the version the first one read is then stale.
  const racing: Db = {
    prepare: (sql) => base.prepare(sql),
    batch: async (statements) => {
      if (!interleaved) {
        interleaved = true;
        await record(base, "creem", { kind: "paid", eventId: "evt_race_b", userId: R, plan: "plus", paidUntil: later });
      }
      return base.batch(statements);
    },
  };
  const applied = await record(racing, "creem", { kind: "paid", eventId: "evt_race_a", userId: R, plan: "lite", paidUntil: future(30) });
  assert.equal(applied, true);
  const events = sqlite.query("select event_id from cloud_billing_events where user_id = ?1 order by event_id").all(R) as { event_id: string }[];
  assert.deepEqual(events.map((e) => e.event_id), ["evt_race_a", "evt_race_b"]);
  const row = sqlite.query("select plan, paid_until, version from cloud_accounts where user_id = ?1").get(R) as any;
  // Both applied, the later paid-until kept, and two writes counted.
  assert.equal(row.paid_until, later.toISOString());
  assert.equal(row.version, 2);
});

// ── The library: uploads, quota, the Subsonic API, cleanup ─────────────────

const L = "99999999-9999-4999-8999-999999999999";
const meta = (title: string, extra: Record<string, unknown> = {}) => ({
  title, artist: "Band", album: "First Album", albumArtist: "Band", track: 1, duration: 200, ...extra,
});
const subscribe = (uid: string, plan: "lite" | "plus" | "max", days = 30, trial = 0) =>
  sqlite.query(`insert into cloud_accounts (user_id, plan, paid_until, trial) values (?1, ?2, ?3, ?4)
                on conflict (user_id) do update set plan = excluded.plan, paid_until = excluded.paid_until, trial = excluded.trial`)
    .run(uid, plan, future(days).toISOString(), trial);

/** Uploads [bytes] as one song through the API; returns the song id. */
async function upload(who: string, bytes: Uint8Array, m = meta("Song"), suffix = "flac") {
  const start = await call("POST", "/v1/uploads", who, { size: bytes.length, suffix, meta: m });
  assert.equal(start.status, 201, JSON.stringify(start.body));
  const parts = [];
  for (let n = 1; n <= start.body.parts; n++) {
    const chunk = bytes.slice((n - 1) * start.body.partSize, n * start.body.partSize);
    const res = await fetchCloud(new Request(`http://cloud.test/v1/uploads/${start.body.id}/parts/${n}`, {
      method: "PUT", headers: { authorization: `Bearer ${who}`, "content-length": String(chunk.length) }, body: chunk,
    }));
    assert.equal(res.status, 200, await res.clone().text());
    parts.push(await res.json());
  }
  const done = await call("POST", `/v1/uploads/${start.body.id}/complete`, who, { parts });
  assert.equal(done.status, 201, JSON.stringify(done.body));
  return start.body.id as string;
}

let subsonicLogin: { server: string; username: string; password: string };
async function rest(method: string, params: Record<string, string> = {}, login = subsonicLogin, headers: Record<string, string> = {}) {
  const salt = "c19b2d";
  const t = Array.from(md5(new TextEncoder().encode(login.password + salt)), (x) => x.toString(16).padStart(2, "0")).join("");
  const q = new URLSearchParams({ u: login.username, t, s: salt, v: "1.13.0", c: "Tryptify", f: "json", ...params });
  return fetchCloud(new Request(`http://cloud.test/rest/${method}.view?${q}`, { headers }));
}
const restJson = async (method: string, params: Record<string, string> = {}, login = subsonicLogin) =>
  (await (await rest(method, params, login)).json())["subsonic-response"];

test("no plan: no upload; the account must pay first", async () => {
  const r = await call("POST", "/v1/uploads", await token(L), { size: 10, suffix: "flac", meta: meta("x") });
  assert.equal(r.status, 402);
  assert.equal(r.body.error, "no_plan");
});

test("an upload is a song: reserved, sent in parts, counted, listed", async () => {
  subscribe(L, "lite");
  const who = await token(L);
  const bytes = crypto.getRandomValues(new Uint8Array(70_000));
  const id = await upload(who, bytes);
  const lib = (await call("GET", "/v1/library", who)).body;
  assert.equal(lib.usedBytes, 70_000);
  assert.equal(lib.reservedBytes, 0);
  assert.equal(lib.songs, 1);
  assert.ok(bucket.objects.has(`u/${L}/${id}.flac`));
});

test("a file bigger than one part goes in equal parts and comes back whole", async () => {
  const who = await token(L);
  const bytes = new Uint8Array(PART_SIZE + 1234);
  bytes[0] = 1; bytes[PART_SIZE] = 2; bytes[bytes.length - 1] = 3;
  const id = await upload(who, bytes, meta("Long one"));
  const stored = bucket.objects.get(`u/${L}/${id}.flac`)!.data;
  assert.equal(stored.length, bytes.length);
  assert.deepEqual([stored[0], stored[PART_SIZE], stored[stored.length - 1]], [1, 2, 3]);
});

test("a part of the wrong size is refused, and a short file is not kept", async () => {
  const who = await token(L);
  const start = await call("POST", "/v1/uploads", who, { size: 100, suffix: "mp3", meta: meta("Short") });
  const wrong = await fetchCloud(new Request(`http://cloud.test/v1/uploads/${start.body.id}/parts/1`, {
    method: "PUT", headers: { authorization: `Bearer ${who}`, "content-length": "99" }, body: new Uint8Array(99),
  }));
  assert.equal(wrong.status, 400);
  // Declared 100, but the part (sneaked past with a lying length) holds 99.
  const sneaky = bucket.resumeMultipartUpload(`u/${L}/${start.body.id}.mp3`, sqlite.query("select r2_upload from cloud_uploads where id = ?1").get(start.body.id)!.r2_upload as string);
  const part = await sneaky.uploadPart(1, new Uint8Array(99));
  const done = await call("POST", `/v1/uploads/${start.body.id}/complete`, who, { parts: [part] });
  assert.equal(done.status, 400);
  assert.equal(done.body.error, "size_mismatch");
  assert.equal(bucket.objects.has(`u/${L}/${start.body.id}.mp3`), false);
  assert.equal((await call("GET", "/v1/library", who)).body.reservedBytes, 0);
});

test("the quota counts reservations: two uploads cannot both take the last space", async () => {
  const Q = "aaaaaaaa-1111-4111-8111-aaaaaaaaaaaa";
  subscribe(Q, "lite", 30, 1); // a trial: 20 GB
  sqlite.query("update cloud_accounts set used_bytes = ?2 where user_id = ?1").run(Q, 20 * GB - 1000);
  const who = await token(Q);
  const first = await call("POST", "/v1/uploads", who, { size: 600, suffix: "mp3", meta: meta("a") });
  const second = await call("POST", "/v1/uploads", who, { size: 600, suffix: "mp3", meta: meta("b") });
  assert.equal(first.status, 201);
  assert.equal(second.status, 413);
  assert.equal(second.body.error, "over_quota");
  // Giving the first one up frees its reservation.
  assert.equal((await call("DELETE", `/v1/uploads/${first.body.id}`, who)).status, 204);
  assert.equal((await call("POST", "/v1/uploads", who, { size: 600, suffix: "mp3", meta: meta("c") })).status, 201);
});

test("not audio, too big, or no title is refused before anything is stored", async () => {
  const who = await token(L);
  assert.equal((await call("POST", "/v1/uploads", who, { size: 10, suffix: "exe", meta: meta("x") })).status, 415);
  assert.equal((await call("POST", "/v1/uploads", who, { size: 3 * 1024 ** 3, suffix: "flac", meta: meta("x") })).status, 413);
  assert.equal((await call("POST", "/v1/uploads", who, { size: 10, suffix: "flac", meta: { artist: "x" } })).status, 400);
});

test("a device password signs in to the Subsonic API; a wrong one does not", async () => {
  const r = await call("POST", "/v1/subsonic-keys", await token(L), { label: "phone" });
  assert.equal(r.status, 201);
  subsonicLogin = r.body;
  assert.equal(subsonicLogin.username, L);
  assert.equal((await restJson("ping")).status, "ok");
  const bad = await restJson("ping", {}, { ...subsonicLogin, password: "nope" });
  assert.equal(bad.status, "failed");
  assert.equal(bad.error.code, 40);
  // p=enc:hex works too.
  const hex = Array.from(new TextEncoder().encode(subsonicLogin.password), (b) => b.toString(16).padStart(2, "0")).join("");
  const res = await fetchCloud(new Request(`http://cloud.test/rest/ping.view?u=${L}&p=enc:${hex}&v=1.8.0&c=x&f=json`));
  assert.equal((await res.json())["subsonic-response"].status, "ok");
});

test("the library reads as a Subsonic server: albums, an album's songs, artists, search", async () => {
  const albums = (await restJson("getAlbumList2", { type: "alphabeticalByName", size: "50" })).albumList2.album;
  assert.equal(albums.length, 1);
  assert.equal(albums[0].name, "First Album");
  assert.equal(albums[0].songCount, 2);
  const album = (await restJson("getAlbum", { id: albums[0].id })).album;
  assert.deepEqual(album.song.map((s: any) => s.title).sort(), ["Long one", "Song"]);
  assert.equal(album.song[0].suffix, "flac");
  const artists = (await restJson("getArtists")).artists.index;
  assert.equal(artists[0].artist[0].name, "Band");
  const found = (await restJson("search3", { query: "long", songCount: "10" })).searchResult3.song;
  assert.deepEqual(found.map((s: any) => s.title), ["Long one"]);
  const everything = (await restJson("search3", { query: "", songCount: "10" })).searchResult3.song;
  assert.equal(everything.length, 2);
});

test("stream plays the file, with ranges for seeking", async () => {
  const song = (await restJson("search3", { query: "Song", songCount: "1" })).searchResult3.song[0];
  const stored = bucket.objects.get(`u/${L}/${song.id}.flac`)!.data;
  const whole = await rest("stream", { id: song.id });
  assert.equal(whole.status, 200);
  assert.equal(whole.headers.get("content-type"), "audio/flac");
  assert.deepEqual(new Uint8Array(await whole.arrayBuffer()), stored);
  const part = await rest("stream", { id: song.id }, subsonicLogin, { range: "bytes=100-199" });
  assert.equal(part.status, 206);
  assert.equal(part.headers.get("content-range"), `bytes 100-199/${stored.length}`);
  assert.deepEqual(new Uint8Array(await part.arrayBuffer()), stored.slice(100, 200));
  const bad = await rest("stream", { id: song.id }, subsonicLogin, { range: "bytes=999999-" });
  assert.equal(bad.status, 416);
});

test("the web player gets a signed link that plays, with ranges, and CORS only for the site", async () => {
  const who = await token(L);
  const song = (await restJson("search3", { query: "Song", songCount: "1" })).searchResult3.song[0];
  const stored = bucket.objects.get(`u/${L}/${song.id}.flac`)!.data;
  const link = await call("GET", `/v1/songs/${song.id}/play`, who);
  assert.equal(link.status, 200, JSON.stringify(link.body));
  assert.equal(link.body.contentType, "audio/flac");
  assert.match(link.body.url, new RegExp(`^http://cloud\\.test/play/${song.id}\\?u=${L}&e=\\d+&s=[0-9a-f]{64}$`));
  const whole = await fetchCloud(new Request(link.body.url));
  assert.equal(whole.status, 200);
  assert.deepEqual(new Uint8Array(await whole.arrayBuffer()), stored);
  assert.equal(whole.headers.get("access-control-allow-origin"), null);
  const part = await fetchCloud(new Request(link.body.url, { headers: { range: "bytes=10-19", origin: "https://tryptify.com" } }));
  assert.equal(part.status, 206);
  assert.equal(part.headers.get("content-range"), `bytes 10-19/${stored.length}`);
  assert.deepEqual(new Uint8Array(await part.arrayBuffer()), stored.slice(10, 20));
  assert.equal(part.headers.get("access-control-allow-origin"), "https://tryptify.com");
  const elsewhere = await fetchCloud(new Request(link.body.url, { headers: { origin: "https://evil.test" } }));
  assert.equal(elsewhere.headers.get("access-control-allow-origin"), null);
});

test("a play link can't be bent: another song, a later expiry, a bad signature or old age", async () => {
  const who = await token(L);
  const songs = (await restJson("search3", { query: "", songCount: "10" })).searchResult3.song;
  const url = new URL((await call("GET", `/v1/songs/${songs[0].id}/play`, who)).body.url);
  const other = new URL(url); other.pathname = `/play/${songs[1].id}`;
  assert.equal((await fetchCloud(new Request(other))).status, 403);
  const later = new URL(url); later.searchParams.set("e", String(Number(url.searchParams.get("e")) + 3600));
  assert.equal((await fetchCloud(new Request(later))).status, 403);
  const forged = new URL(url); forged.searchParams.set("s", "0".repeat(64));
  assert.equal((await fetchCloud(new Request(forged))).status, 403);
  const someoneElse = new URL(url); someoneElse.searchParams.set("u", B);
  assert.equal((await fetchCloud(new Request(someoneElse))).status, 403);
  const tomorrow = createApp({
    db: d1(sqlite), storage: bucket, billing: null, personOf: async () => null, playKey: PLAY_KEY,
    now: () => new Date(Date.now() + 7 * 3600_000),
  });
  const old = await tomorrow.fetch(new Request(url));
  assert.equal(old.status, 403);
  assert.equal((await old.json()).error, "link_expired");
});

test("no plan, no play link; another person's song has none either", async () => {
  const N = "cccccccc-3333-4333-8333-cccccccccccc";
  const song = (await restJson("search3", { query: "Song", songCount: "1" })).searchResult3.song[0];
  const none = await call("GET", `/v1/songs/${song.id}/play`, await token(N));
  assert.equal(none.status, 402);
  assert.equal(none.body.error, "no_plan");
  subscribe(N, "lite");
  assert.equal((await call("GET", `/v1/songs/${song.id}/play`, await token(N))).status, 404);
  assert.equal((await call("GET", `/v1/songs/${song.id}/play`)).status, 401);
});

test("nobody else's songs: another person cannot stream or see them", async () => {
  const O = "bbbbbbbb-2222-4222-8222-bbbbbbbbbbbb";
  subscribe(O, "lite");
  const other = (await call("POST", "/v1/subsonic-keys", await token(O), {})).body;
  const mine = (await restJson("search3", { query: "", songCount: "10" })).searchResult3.song[0];
  const theirs = await (await rest("stream", { id: mine.id }, other)).json();
  assert.equal(theirs["subsonic-response"].error.code, 70);
  assert.equal((await restJson("search3", { query: "", songCount: "10" }, other)).searchResult3.song.length, 0);
});

test("a cover counts against the quota, shows as coverArt, and is served", async () => {
  const who = await token(L);
  const song = (await restJson("search3", { query: "Song", songCount: "1" })).searchResult3.song[0];
  const before = (await call("GET", "/v1/library", who)).body.usedBytes;
  const jpeg = new Uint8Array([0xff, 0xd8, 0xff, 0xe0, 1, 2, 3, 4]);
  const put = await fetchCloud(new Request(`http://cloud.test/v1/songs/${song.id}/cover`, {
    method: "PUT", headers: { authorization: `Bearer ${who}`, "content-length": "8" }, body: jpeg,
  }));
  assert.equal(put.status, 204);
  assert.equal((await call("GET", "/v1/library", who)).body.usedBytes, before + 8);
  const again = (await restJson("getSong", { id: song.id })).song;
  assert.equal(again.coverArt, song.id);
  const art = await rest("getCoverArt", { id: again.albumId });
  assert.equal(art.headers.get("content-type"), "image/jpeg");
  assert.deepEqual(new Uint8Array(await art.arrayBuffer()), jpeg);
  const notImage = await fetchCloud(new Request(`http://cloud.test/v1/songs/${song.id}/cover`, {
    method: "PUT", headers: { authorization: `Bearer ${who}`, "content-length": "3" }, body: new Uint8Array([1, 2, 3]),
  }));
  assert.equal(notImage.status, 415);
});

test("the web app lists, searches and shows covers, only for the signed-in person", async () => {
  const who = await token(L);
  const all = await call("GET", "/v1/songs", who);
  assert.equal(all.status, 200);
  assert.ok(all.body.total >= 1 && all.body.songs.length === Math.min(all.body.total, 200));
  const withCover = all.body.songs.find((s: any) => s.hasCover);
  assert.ok(withCover, "the song given a cover above");
  const art = await fetchCloud(new Request(`http://cloud.test/v1/songs/${withCover.id}/cover`, { headers: { authorization: `Bearer ${who}` } }));
  assert.equal(art.status, 200);
  assert.equal(art.headers.get("content-type"), "image/jpeg");
  const found = await call("GET", "/v1/songs?q=First%20Album", who);
  assert.ok(found.body.total >= 1 && found.body.songs.every((s: any) => s.album === "First Album"));
  // A % or _ in the search is a character, not a wildcard.
  assert.equal((await call("GET", "/v1/songs?q=%25", who)).body.total, 0);
  // Somebody else sees none of it.
  const other = await token(B);
  assert.equal((await call("GET", "/v1/songs", other)).body.total, 0);
  const theirs = await fetchCloud(new Request(`http://cloud.test/v1/songs/${withCover.id}/cover`, { headers: { authorization: `Bearer ${other}` } }));
  assert.equal(theirs.status, 404);
  assert.equal((await call("GET", "/v1/songs")).status, 401);
});

test("devices are listed without passwords and can be signed out one by one", async () => {
  const who = await token(L);
  const extra = await call("POST", "/v1/subsonic-keys", who, { label: "Feishin" });
  const list = await call("GET", "/v1/subsonic-keys", who);
  assert.equal(list.status, 200);
  assert.equal(list.body.username, L);
  assert.ok(list.body.devices.some((d: any) => d.label === "Feishin"));
  assert.ok(!JSON.stringify(list.body).includes(extra.body.password), "never a password");
  const id = list.body.devices.find((d: any) => d.label === "Feishin").id;
  // Another person cannot sign it out.
  await call("DELETE", `/v1/subsonic-keys/${id}`, await token(B));
  assert.equal((await restJson("ping", {}, extra.body)).status, "ok");
  assert.equal((await call("DELETE", `/v1/subsonic-keys/${id}`, who)).status, 204);
  assert.equal((await restJson("ping", {}, extra.body)).status, "failed");
  // The phone's sign-in is untouched.
  assert.equal((await restJson("ping")).status, "ok");
});

test("the web app's pages may call /v1 from a browser; other sites may not", async () => {
  const pre = (origin: string) => fetchCloud(new Request("http://cloud.test/v1/account", {
    method: "OPTIONS",
    headers: { origin, "access-control-request-method": "GET", "access-control-request-headers": "authorization" },
  }));
  for (const ok of ["https://tryptify.com", "https://www.tryptify.com", "http://localhost:8765"]) {
    const r = await pre(ok);
    assert.ok(r.status === 204 || r.status === 200, `${ok}: ${r.status}`);
    assert.equal(r.headers.get("access-control-allow-origin"), ok);
  }
  for (const bad of ["https://evil.example", "https://tryptify.com.evil.example", "http://tryptify.com"]) {
    assert.equal((await pre(bad)).headers.get("access-control-allow-origin"), null, bad);
  }
});

test("deleting a song removes its file and cover and gives the space back", async () => {
  const who = await token(L);
  const song = (await restJson("search3", { query: "Song", songCount: "1" })).searchResult3.song[0];
  const before = (await call("GET", "/v1/library", who)).body.usedBytes;
  assert.equal((await call("DELETE", `/v1/songs/${song.id}`, who)).status, 204);
  assert.equal((await call("GET", "/v1/library", who)).body.usedBytes, before - 70_000 - 8);
  assert.equal(bucket.objects.has(`u/${L}/${song.id}.flac`), false);
  assert.equal(bucket.objects.has(`u/${L}/covers/${song.id}`), false);
});

test("a lapsed library still plays (read-only) but takes no uploads", async () => {
  subscribe(L, "lite", -1);
  const who = await token(L);
  assert.equal((await call("POST", "/v1/uploads", who, { size: 10, suffix: "mp3", meta: meta("x") })).status, 409);
  assert.equal((await restJson("getAlbumList2", { type: "newest" })).status, "ok");
});

test("cleanup deletes libraries past their grace and abandoned uploads, nothing else", async () => {
  const keep = "cccccccc-3333-4333-8333-cccccccccccc";
  subscribe(keep, "plus");
  await upload(await token(keep), new Uint8Array(500), meta("Kept"));
  subscribe(L, "lite", -(rules.READ_ONLY_DAYS + 1)); // past the 7 days
  const stale = await call("POST", "/v1/uploads", await token(keep), { size: 10, suffix: "mp3", meta: meta("left open") });
  sqlite.query("update cloud_uploads set created_at = ?2 where id = ?1").run(stale.body.id, new Date(Date.now() - 7 * 3600_000).toISOString());
  const result = await cleanup(d1(sqlite), bucket, new Date());
  assert.ok(result.wiped >= 1);
  assert.equal(result.aborted, 1);
  assert.equal([...bucket.objects.keys()].some((k) => k.startsWith(`u/${L}/`)), false);
  assert.equal((sqlite.query("select used_bytes from cloud_accounts where user_id = ?1").get(L) as any).used_bytes, 0);
  assert.equal((sqlite.query("select count(*) as n from cloud_songs where user_id = ?1").get(keep) as any).n, 1);
  assert.equal((sqlite.query("select count(*) as n from cloud_uploads where user_id = ?1").get(keep) as any).n, 0);
  // The deleted library is gone for the API too.
  assert.equal((await restJson("getAlbumList2", { type: "newest" })).error.code, 50);
});

// ── Run ──────────────────────────────────────────────────────────────────────

await setUp();
let failed = 0;
for (const [name, fn] of tests) {
  try {
    await fn();
    console.log(`  ok    ${name}`);
  } catch (e) {
    failed++;
    console.log(`  FAIL  ${name}`);
    console.log(e);
  }
}
sqlite.close();
console.log(`${tests.length - failed} passed, ${failed} failed`);
process.exit(failed ? 1 : 0);
