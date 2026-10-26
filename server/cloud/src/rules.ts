// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// The money rules, pure: plans, what a person may do, and what a billing event
// changes. No I/O, so the tests hold them exactly.
//
// The owner's rule is that a listener can never cost more than they pay, so
// access is worked out from the date they have paid until, never from a
// "cancelled" message: a renewal moves the date on, and if renewals stop for
// any reason — a lost webhook included — access ends by itself. After it ends,
// the library is read-only for READ_ONLY_DAYS, then expired (deleted).

export const GB = 1_000_000_000;
export const DAY_MS = 86_400_000;

/** Days a lapsed library stays readable (play, export) before it is deleted. */
export const READ_ONLY_DAYS = 7;

/**
 * Days a refunded library stays readable. Shorter, because a refund hands the
 * money back: every day kept after it is storage nobody paid for.
 */
export const REFUND_EXPORT_DAYS = 2;

/**
 * The free trial: TRIAL_DAYS with TRIAL_GB (or the plan's own quota, if that
 * is smaller: Mini's trial is 10 GB), once per account, card required.
 * The card is the provider's (its trial products carry the 7 days); this service only
 * holds a trial to its smaller quota and gives a trial that ends unpaid the
 * refund's short export window, so one costs at most about $0.10.
 */
export const TRIAL_DAYS = 7;
export const TRIAL_GB = 20;

/**
 * The plans, smallest first (the order the app and the site list them), from
 * docs/plans/tryptify-cloud.md ("Pricing"). Each is $3.99 per 100 GB a month,
 * yearly at two months free, and a profit in the worst case; change one only
 * by rerunning that check. Mini is the owner's exception (2026-10-24): $0.99
 * for 10 GB, still a profit but a thin one, since a payment's fixed fee is
 * half of it. Prices are what the listener is shown; what they are charged is
 * set at the payment provider, and the two must agree.
 */
export const PLANS = {
  mini: { storageGb: 10, monthly: 0.99, yearly: 9.99 },
  lite: { storageGb: 100, monthly: 3.99, yearly: 39.99 },
  plus: { storageGb: 250, monthly: 9.99, yearly: 99.99 },
  max: { storageGb: 500, monthly: 19.99, yearly: 199.99 },
  pro: { storageGb: 1_000, monthly: 39.99, yearly: 399.99 },
  ultra: { storageGb: 3_000, monthly: 119.99, yearly: 1199.99 },
  xtra: { storageGb: 10_000, monthly: 399.99, yearly: 3999.99 },
} as const;
export type PlanId = keyof typeof PLANS;
export type Period = "monthly" | "yearly";
export const isPlan = (p: unknown): p is PlanId => typeof p === "string" && Object.hasOwn(PLANS, p);

/** The quota a free trial of [plan] gets: TRIAL_GB, or the plan's own if smaller. */
export const trialGbOf = (plan: PlanId): number => Math.min(TRIAL_GB, PLANS[plan].storageGb);

export type Access =
  /** Upload and play. */
  | "full"
  /** Play and export only: over quota, or paid-up time ran out a short while ago. */
  | "read_only"
  /** Nothing; the library is deleted. Also a person who never subscribed. */
  | "none";

export interface Account {
  plan: PlanId | null;
  paidUntil: Date | null;
  usedBytes: number;
  /** The paid-until date is a free trial's end, not a payment's. */
  trial?: boolean;
  /** Given, not sold (cloud_grants): full access with no quota and no end. */
  unlimited?: boolean;
  /** The provider's subscription the account pays (or trials) with. */
  subscriptionId?: string | null;
}

/**
 * The quota an unlimited account is held to: a petabyte, far past anything
 * one listener uploads, but a real number, so the arithmetic that compares
 * against a quota keeps working.
 */
export const UNLIMITED_BYTES = 1_000_000 * GB;

export const NO_ACCOUNT: Account = { plan: null, paidUntil: null, usedBytes: 0, trial: false };

/** Whether [a] may start a free trial: only an account that has never had a plan or a trial. */
export const mayStartTrial = (a: Account): boolean => a.plan === null && !a.unlimited;

/**
 * The one place the rule lives. Only [Account.paidUntil] grants anything: a
 * plan without a future paid-until date is no plan.
 */
export function accessOf(a: Account, now: Date): { access: Access; quotaBytes: number; deleteAfter: Date | null } {
  if (a.unlimited) return { access: "full", quotaBytes: UNLIMITED_BYTES, deleteAfter: null };
  if (!a.plan || !a.paidUntil) return { access: "none", quotaBytes: 0, deleteAfter: null };
  const quotaBytes = (a.trial ? trialGbOf(a.plan) : PLANS[a.plan].storageGb) * GB;
  const graceDays = a.trial ? REFUND_EXPORT_DAYS : READ_ONLY_DAYS;
  const deleteAfter = new Date(a.paidUntil.getTime() + graceDays * DAY_MS);
  if (now >= deleteAfter) return { access: "none", quotaBytes: 0, deleteAfter };
  if (now >= a.paidUntil) return { access: "read_only", quotaBytes, deleteAfter };
  return { access: a.usedBytes >= quotaBytes ? "read_only" : "full", quotaBytes, deleteAfter: null };
}

/** Whether an upload of [bytes] more fits: never over the quota, never without full access. */
export function mayUpload(a: Account, bytes: number, now: Date): boolean {
  const { access, quotaBytes } = accessOf(a, now);
  return access === "full" && bytes > 0 && a.usedBytes + bytes <= quotaBytes;
}

/**
 * What a provider's webhook means, reduced to the one thing this service acts
 * on. `paid` moves the paid-until date to the end of the period just paid
 * for; nothing else ever extends it. `refunded` ends it now, leaving
 * REFUND_EXPORT_DAYS to export. Everything else a provider says (cancelled,
 * payment failed, paused) needs no action: no renewal arrives, and access
 * lapses on its own at the date already stored.
 */
export type BillingEvent =
  | {
      kind: "paid";
      eventId: string;
      userId: string | null;
      plan: PlanId;
      paidUntil: Date;
      /** A free trial starting (Creem reports it as a $0 payment with status "trialing"). */
      trial?: boolean;
      customerId?: string;
      subscriptionId?: string;
    }
  | { kind: "refunded"; eventId: string; userId: string | null; subscriptionId?: string };

/** The account after [e]. The paid-until date only ever moves forward on a payment. */
export function applyEvent(a: Account, e: BillingEvent, now: Date): Account {
  // Paid-until moved back so that deletion comes REFUND_EXPORT_DAYS from now.
  if (e.kind === "refunded") {
    return { ...a, paidUntil: new Date(now.getTime() - (READ_ONLY_DAYS - REFUND_EXPORT_DAYS) * DAY_MS) };
  }
  if (e.trial) {
    // A trial never takes the place of time someone paid for, and comes once.
    const paidUp = a.paidUntil !== null && !a.trial && a.paidUntil > now;
    if (paidUp || (!mayStartTrial(a) && !a.trial)) return a;
    // A trial running already takes news only of its own subscription: a
    // second trial checkout, opened before the first one's webhook came,
    // must not add its days on top.
    if (a.trial && a.subscriptionId && e.subscriptionId !== a.subscriptionId) return a;
    const later = !a.paidUntil || e.paidUntil > a.paidUntil ? e.paidUntil : a.paidUntil;
    return { ...a, plan: e.plan, paidUntil: later, trial: true };
  }
  const later = !a.paidUntil || e.paidUntil > a.paidUntil ? e.paidUntil : a.paidUntil;
  return { ...a, plan: e.plan, paidUntil: later, trial: false };
}
