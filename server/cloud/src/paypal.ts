// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// PayPal, the owner's payment provider after Creem. Unlike Creem it is not a
// merchant of record: tryptz is the seller and PayPal only takes the money.
// Its settings are the Worker's variables; the client secret is a Worker
// secret, never committed:
//   PAYPAL_CLIENT_ID, PAYPAL_SECRET       the REST app's credentials (secrets)
//   PAYPAL_WEBHOOK_ID                     the webhook's id, which signatures name
//   PAYPAL_PLAN_<PLAN>_<PERIOD>[_TRIAL]   the plans' ids (P-…), made by the
//                                         site repository's tools/paypal_setup
//   PAYPAL_API_BASE                       https://api-m.sandbox.paypal.com in sandbox
//   PAYPAL_RETURN_URL, PAYPAL_CANCEL_URL  optional pages after approving or backing out
//
// PayPal's events say less than Creem's: a payment names its subscription but
// not the plan or the person, so both are read back from the subscription.

import type { Billing } from "./creem";
import { DAY_MS, PLANS, TRIAL_DAYS, type Period, type PlanId } from "./rules";

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export const planKey = (plan: PlanId, period: Period, trial = false, intro = false) =>
  `PAYPAL_PLAN_${plan.toUpperCase()}_${period.toUpperCase()}${trial ? "_TRIAL" : intro ? "_INTRO" : ""}`;

/** A PayPal API answer that was not 2xx. 4xx is PayPal saying no; anything else may pass if tried again. */
class PayPalError extends Error {
  constructor(readonly status: number, detail: string) {
    super(`paypal ${status}: ${detail.slice(0, 300)}`);
  }
}

/** [from] plus one billing period, as PayPal counts them: the same day next month or next year. */
export function addPeriod(from: Date, period: Period): Date {
  const d = new Date(from);
  if (period === "monthly") d.setUTCMonth(d.getUTCMonth() + 1);
  else d.setUTCFullYear(d.getUTCFullYear() + 1);
  return d;
}

/** Slack allowed on PayPal's next-billing date over a period counted from the payment. */
const NEXT_BILLING_SLACK_MS = 2 * DAY_MS;

export function paypalBilling(env: Record<string, string | undefined>, http: typeof fetch = fetch): Billing | null {
  const clientId = env.PAYPAL_CLIENT_ID;
  const secret = env.PAYPAL_SECRET;
  const webhookId = env.PAYPAL_WEBHOOK_ID;
  if (!clientId || !secret || !webhookId) return null;
  const base = (env.PAYPAL_API_BASE ?? "https://api-m.paypal.com").replace(/\/+$/, "");
  const sandbox = base.includes("sandbox");
  const planOf = new Map<string, { plan: PlanId; period: Period; trial: boolean }>();
  for (const plan of Object.keys(PLANS) as PlanId[]) {
    for (const [period, trial, intro] of [["monthly", false, false], ["yearly", false, false], ["monthly", true, false], ["monthly", false, true]] as const) {
      const id = env[planKey(plan, period, trial, intro)];
      // A half-price first month is a payment like any other.
      if (id) planOf.set(id, { plan, period, trial });
    }
  }
  const userOf = (id: unknown): string | null => (typeof id === "string" && UUID.test(id) ? id : null);

  // One access token per isolate, renewed a minute before PayPal says it ends.
  let token: { value: string; until: number } | null = null;
  async function bearer(): Promise<string> {
    if (token && token.until > Date.now()) return token.value;
    const res = await http(`${base}/v1/oauth2/token`, {
      method: "POST",
      headers: { authorization: "Basic " + btoa(`${clientId}:${secret}`), "content-type": "application/x-www-form-urlencoded" },
      body: "grant_type=client_credentials",
      signal: AbortSignal.timeout(15_000),
    });
    if (!res.ok) throw new PayPalError(res.status, await res.text());
    const body = (await res.json()) as { access_token: string; expires_in: number };
    token = { value: body.access_token, until: Date.now() + (Number(body.expires_in) - 60) * 1000 };
    return token.value;
  }

  /** One API call. [body] is sent as is when it is a string, so a webhook can be passed on byte for byte. */
  async function api(method: string, path: string, body?: unknown): Promise<any> {
    const res = await http(`${base}${path}`, {
      method,
      headers: { authorization: `Bearer ${await bearer()}`, "content-type": "application/json" },
      body: body === undefined ? undefined : typeof body === "string" ? body : JSON.stringify(body),
      signal: AbortSignal.timeout(15_000),
    });
    if (!res.ok) throw new PayPalError(res.status, await res.text());
    return res.json();
  }

  /** A sale, or null when PayPal has none by that id (a dispute can name other kinds of transaction). */
  const sale = (id: string) =>
    api("GET", `/v1/payments/sale/${encodeURIComponent(id)}`).catch((e) => {
      if (e instanceof PayPalError && e.status === 404) return null;
      throw e;
    });

  /** Whether PayPal vouches for the webhook. A 4xx from it is a no; anything else throws, so PayPal sends it again. */
  async function verified(headers: Headers, raw: string): Promise<boolean> {
    const h = (name: string) => headers.get(name) ?? "";
    const fields = {
      auth_algo: h("paypal-auth-algo"),
      cert_url: h("paypal-cert-url"),
      transmission_id: h("paypal-transmission-id"),
      transmission_sig: h("paypal-transmission-sig"),
      transmission_time: h("paypal-transmission-time"),
      webhook_id: webhookId,
    };
    if (Object.values(fields).some((v) => !v)) return false;
    // The event goes back exactly as it came: the signature covers those
    // bytes, and parsing then re-encoding them can change them.
    const body = JSON.stringify(fields).slice(0, -1) + `,"webhook_event":${raw}}`;
    try {
      return (await api("POST", "/v1/notifications/verify-webhook-signature", body))?.verification_status === "SUCCESS";
    } catch (e) {
      if (e instanceof PayPalError && e.status >= 400 && e.status < 500) return false;
      throw e;
    }
  }

  return {
    name: "paypal",

    sells: (plan) => !!(env[planKey(plan, "monthly")] || env[planKey(plan, "yearly")]),
    sellsIntro: (plan) => !!env[planKey(plan, "monthly", false, true)],

    async checkoutUrl(plan, period, userId, trial = false, intro = false) {
      const planId = env[planKey(plan, period, trial, intro)];
      if (!planId) return null;
      try {
        const sub = await api("POST", "/v1/billing/subscriptions", {
          plan_id: planId,
          custom_id: userId,
          application_context: {
            brand_name: "Tryptify Cloud",
            shipping_preference: "NO_SHIPPING",
            user_action: "SUBSCRIBE_NOW",
            return_url: env.PAYPAL_RETURN_URL ?? "https://tryptify.com/account?paid=1",
            cancel_url: env.PAYPAL_CANCEL_URL ?? "https://tryptify.com/account",
          },
        });
        const approve = (sub?.links as { rel?: string; href?: string }[] | undefined)?.find((l) => l.rel === "approve");
        return typeof approve?.href === "string" ? approve.href : null;
      } catch (e) {
        console.error(`paypal checkout failed: ${e}`);
        return null;
      }
    },

    // PayPal has no portal per customer: people manage and cancel what they
    // pay automatically on one page of their own PayPal account.
    async portalUrl() {
      return `https://www.${sandbox ? "sandbox." : ""}paypal.com/myaccount/autopay/`;
    },

    async parseWebhook(headers, raw) {
      let event: any;
      try {
        event = JSON.parse(raw);
      } catch {
        return null;
      }
      if (!(await verified(headers, raw))) return null;
      const eventId = typeof event?.id === "string" ? event.id : null;
      const r = event?.resource;
      if (!eventId || !r) return [];
      switch (event.event_type) {
        // A trial starts with no payment, so on a trial plan this is what grants
        // the trial, until the first charge and never past TRIAL_DAYS (plus a
        // day's slack). On any other plan the first payment grants the plan.
        case "BILLING.SUBSCRIPTION.ACTIVATED": {
          const p = planOf.get(r.plan_id);
          if (!p?.trial) return [];
          const start = new Date(r.start_time ?? event.create_time);
          const next = new Date(r.billing_info?.next_billing_time);
          if (Number.isNaN(start.getTime()) || Number.isNaN(next.getTime())) {
            console.error(`paypal ${eventId}: trial with no start or next billing time; ignored`);
            return [];
          }
          const cap = new Date(start.getTime() + (TRIAL_DAYS + 1) * DAY_MS);
          return [{
            kind: "paid",
            eventId,
            userId: userOf(r.custom_id),
            plan: p.plan,
            paidUntil: next < cap ? next : cap,
            trial: true,
            customerId: r.subscriber?.payer_id ?? r.id,
            subscriptionId: r.id,
          }];
        }
        // The one event that pays for time: a completed charge on a
        // subscription, good for one period from when it was taken.
        case "PAYMENT.SALE.COMPLETED": {
          const subId = r.billing_agreement_id;
          if (r.state !== "completed" || typeof subId !== "string" || !(Number(r.amount?.total) > 0)) return [];
          const sub = await api("GET", `/v1/billing/subscriptions/${encodeURIComponent(subId)}`);
          const p = planOf.get(sub?.plan_id);
          const paidAt = new Date(r.create_time ?? event.create_time);
          if (!p || Number.isNaN(paidAt.getTime())) {
            console.error(`paypal ${eventId}: unknown plan ${sub?.plan_id} or no payment time; ignored`);
            return [];
          }
          // PayPal's own next billing date when it agrees with the period
          // (it can land a little later), never more than that.
          const byPeriod = addPeriod(paidAt, p.period);
          const next = new Date(sub.billing_info?.next_billing_time);
          const fits = next > paidAt && next.getTime() <= byPeriod.getTime() + NEXT_BILLING_SLACK_MS;
          return [{
            kind: "paid",
            eventId,
            userId: userOf(sub.custom_id) ?? userOf(r.custom),
            plan: p.plan,
            paidUntil: fits ? next : byPeriod,
            customerId: sub.subscriber?.payer_id ?? subId,
            subscriptionId: subId,
          }];
        }
        // A full refund ends access; a partial one (a goodwill credit) does
        // not. The sale's state says which, counting earlier partial refunds.
        case "PAYMENT.SALE.REFUNDED": {
          const s = typeof r.sale_id === "string" ? await sale(r.sale_id) : null;
          const subId = s?.billing_agreement_id ?? r.billing_agreement_id;
          if (s?.state !== "refunded" || typeof subId !== "string") return [];
          return [{ kind: "refunded", eventId, userId: null, subscriptionId: subId }];
        }
        // A reversal or a chargeback: the money is in question, so access ends as for a refund.
        case "PAYMENT.SALE.REVERSED": {
          const subId = r.billing_agreement_id ?? (await sale(r.sale_id ?? r.id))?.billing_agreement_id;
          return typeof subId === "string" ? [{ kind: "refunded", eventId, userId: null, subscriptionId: subId }] : [];
        }
        case "CUSTOMER.DISPUTE.CREATED": {
          for (const t of r.disputed_transactions ?? []) {
            const id = t?.seller_transaction_id;
            const subId = typeof id === "string" ? (await sale(id))?.billing_agreement_id : null;
            if (typeof subId === "string") return [{ kind: "refunded", eventId, userId: null, subscriptionId: subId }];
          }
          return [];
        }
        // Cancelled, suspended, expired, a failed payment: nothing to do. No
        // payment arrives, and access ends on its own at the paid-until date.
        default:
          return [];
      }
    },
  };
}
