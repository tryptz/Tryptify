// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Creem (creem.io), the owner's choice of payment provider: a merchant of
// record that needs no Stripe account. Its settings are the Worker's
// variables; the key and the webhook secret are Worker secrets, never
// committed:
//   CREEM_API_KEY, CREEM_WEBHOOK_SECRET   (secrets)
//   CREEM_PRODUCT_<PLAN>_<PERIOD>[_TRIAL] the products' ids (wrangler.toml)
//   CREEM_API_BASE                        https://test-api.creem.io in test mode
//   CREEM_SUCCESS_URL                     optional page after paying

import { PLANS, type BillingEvent, type Period, type PlanId } from "./rules";

/** One payment provider, behind this. Until it is set up nobody can pay, or be granted anything. */
export interface Billing {
  name: string;
  checkoutUrl(plan: PlanId, period: Period, userId: string, trial?: boolean): Promise<string | null>;
  /** Whether [plan] can be bought here: the provider has a product for it, monthly or yearly. */
  sells(plan: PlanId): boolean;
  /** The provider's page where a customer changes or cancels their plan, or null when it cannot be had. */
  portalUrl(customerId: string): Promise<string | null>;
  /** The events in a verified webhook, or null when its signature does not check out. */
  parseWebhook(headers: Headers, raw: string): Promise<BillingEvent[] | null>;
}

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export const productKey = (plan: PlanId, period: Period, trial = false) =>
  `CREEM_PRODUCT_${plan.toUpperCase()}_${period.toUpperCase()}${trial ? "_TRIAL" : ""}`;

/** Constant-time comparison of two hex strings. */
export function sameHex(a: string, b: string): boolean {
  if (a.length !== b.length || a.length === 0) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}

/** HMAC-SHA256 of [message] with [secret], as lowercase hex (Web Crypto, so it runs on Workers and Bun alike). */
export async function hmacHex(secret: string, message: string): Promise<string> {
  const enc = new TextEncoder();
  const key = await crypto.subtle.importKey("raw", enc.encode(secret), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  const mac = new Uint8Array(await crypto.subtle.sign("HMAC", key, enc.encode(message)));
  return Array.from(mac, (b) => b.toString(16).padStart(2, "0")).join("");
}

export function creemBilling(env: Record<string, string | undefined>, http: typeof fetch = fetch): Billing | null {
  const apiKey = env.CREEM_API_KEY;
  const secret = env.CREEM_WEBHOOK_SECRET;
  if (!apiKey || !secret) return null;
  const base = (env.CREEM_API_BASE ?? "https://api.creem.io").replace(/\/+$/, "");
  const planOf = new Map<string, PlanId>();
  for (const plan of Object.keys(PLANS) as PlanId[]) {
    for (const key of [productKey(plan, "monthly"), productKey(plan, "yearly"), productKey(plan, "monthly", true)]) {
      const id = env[key];
      if (id) planOf.set(id, plan);
    }
  }
  const metaUser = (...sources: any[]): string | null => {
    for (const s of sources) {
      const id = s?.metadata?.userId;
      if (typeof id === "string" && UUID.test(id)) return id;
    }
    return null;
  };
  const idOf = (x: any): string | undefined => (typeof x === "string" ? x : typeof x?.id === "string" ? x.id : undefined);

  return {
    name: "creem",

    sells: (plan) => !!(env[productKey(plan, "monthly")] || env[productKey(plan, "yearly")]),

    async checkoutUrl(plan, period, userId, trial = false) {
      const product = env[productKey(plan, period, trial)];
      if (!product) return null;
      const res = await http(`${base}/v1/checkouts`, {
        method: "POST",
        headers: { "x-api-key": apiKey, "content-type": "application/json" },
        body: JSON.stringify({
          product_id: product,
          request_id: userId,
          metadata: { userId },
          ...(env.CREEM_SUCCESS_URL ? { success_url: env.CREEM_SUCCESS_URL } : {}),
        }),
        signal: AbortSignal.timeout(15_000),
      });
      if (!res.ok) {
        console.error(`creem checkout failed: ${res.status} ${(await res.text()).slice(0, 300)}`);
        return null;
      }
      const body = (await res.json().catch(() => null)) as { checkout_url?: unknown } | null;
      return typeof body?.checkout_url === "string" ? body.checkout_url : null;
    },

    async portalUrl(customerId) {
      const res = await http(`${base}/v1/customers/billing`, {
        method: "POST",
        headers: { "x-api-key": apiKey, "content-type": "application/json" },
        body: JSON.stringify({ customer_id: customerId }),
        signal: AbortSignal.timeout(15_000),
      });
      if (!res.ok) {
        console.error(`creem portal failed: ${res.status} ${(await res.text()).slice(0, 300)}`);
        return null;
      }
      const body = (await res.json().catch(() => null)) as { customer_portal_link?: unknown } | null;
      return typeof body?.customer_portal_link === "string" ? body.customer_portal_link : null;
    },

    async parseWebhook(headers, raw) {
      const given = (headers.get("creem-signature") ?? "").trim().toLowerCase();
      if (!sameHex(given, await hmacHex(secret, raw))) return null;
      let event: any;
      try {
        event = JSON.parse(raw);
      } catch {
        return [];
      }
      const eventId = typeof event?.id === "string" ? event.id : null;
      const o = event?.object;
      if (!eventId || !o) return [];
      switch (event.eventType) {
        // The one event that grants anything: a payment for a period. A trial
        // arrives as one too, for $0 with status "trialing" (seen in test mode,
        // next to subscription.trialing), and grants only the trial.
        case "subscription.paid":
        case "subscription.trialing": {
          const plan = planOf.get(idOf(o.product) ?? "");
          const until = new Date(o.current_period_end_date);
          if (!plan || Number.isNaN(until.getTime())) {
            console.error(`creem ${eventId}: unknown product ${idOf(o.product)} or no period end; ignored`);
            return [];
          }
          return [{
            kind: "paid",
            eventId,
            userId: metaUser(o),
            plan,
            paidUntil: until,
            trial: o.status === "trialing",
            customerId: idOf(o.customer),
            subscriptionId: idOf(o),
          }];
        }
        // A full refund ends access. A partial one (a goodwill credit) does not.
        case "refund.created": {
          const paid = Number(o.transaction?.amount_paid ?? NaN);
          const refunded = Number(o.refund_amount ?? NaN);
          if (!(refunded > 0) || (Number.isFinite(paid) && refunded < paid)) return [];
          return [{ kind: "refunded", eventId, userId: metaUser(o.subscription, o.checkout), subscriptionId: idOf(o.subscription) }];
        }
        // A chargeback: the money is in question, so access ends as for a refund.
        case "dispute.created":
          return [{
            kind: "refunded",
            eventId,
            userId: metaUser(o.subscription, o.checkout),
            subscriptionId: idOf(o.subscription) ?? idOf(o.transaction?.subscription),
          }];
        // Cancelled, past due, paused, expired: nothing to do. No payment
        // arrives, and access ends on its own at the paid-until date.
        default:
          return [];
      }
    },
  };
}
