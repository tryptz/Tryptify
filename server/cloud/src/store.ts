// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Accounts and billing events in Cloudflare D1 (SQLite), schema in
// ../schema.sql.
//
// D1 has no row locks or interactive transactions, only atomic batches, so a
// billing event is applied optimistically: read the account and its version,
// work out the new account in code (rules.applyEvent), then write it in one
// batch that only takes effect if the version is unchanged and the event has
// not been recorded. A concurrent webhook for the same person makes the write
// a no-op, and it is retried from a fresh read.

import { applyEvent, NO_ACCOUNT, type Account, type BillingEvent, type PlanId } from "./rules";

/** The part of D1's binding this uses (so a test can stand in with SQLite). */
export interface Db {
  prepare(sql: string): Stmt;
  batch(statements: Stmt[]): Promise<unknown>;
}
export interface Stmt {
  bind(...values: unknown[]): Stmt;
  first<T = Record<string, unknown>>(): Promise<T | null>;
  all<T = Record<string, unknown>>(): Promise<{ results: T[] }>;
  run(): Promise<unknown>;
}

/** Rows a run() changed: D1 reports it as meta.changes, SQLite drivers as changes. */
export const changesOf = (result: unknown): number => {
  const r = result as { meta?: { changes?: number }; changes?: number } | null;
  return Number(r?.meta?.changes ?? r?.changes ?? 0);
};

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

interface Row {
  plan: PlanId | null;
  paid_until: string | null;
  used_bytes: number;
  trial: number;
  version: number;
}

const toAccount = (row: Row | null): Account & { version: number } =>
  row
    ? {
        plan: row.plan,
        paidUntil: row.paid_until ? new Date(row.paid_until) : null,
        usedBytes: Number(row.used_bytes),
        trial: row.trial === 1,
        version: Number(row.version),
      }
    : { ...NO_ACCOUNT, version: 0 };

async function read(db: Db, userId: string) {
  const row = await db
    .prepare("select plan, paid_until, used_bytes, trial, version from cloud_accounts where user_id = ?1")
    .bind(userId)
    .first<Row>();
  return toAccount(row);
}

/** [userId]'s account as access is judged: what they paid for, and any grant (cloud_grants). */
export const loadAccount = async (db: Db, userId: string): Promise<Account> => {
  const [account, grant] = await Promise.all([
    read(db, userId),
    db.prepare("select kind from cloud_grants where user_id = ?1").bind(userId).first<{ kind: string }>(),
  ]);
  return grant?.kind === "unlimited" ? { ...account, unlimited: true } : account;
};

const seen = async (db: Db, provider: string, eventId: string) =>
  (await db.prepare("select 1 as hit from cloud_billing_events where provider = ?1 and event_id = ?2").bind(provider, eventId).first()) !== null;

/**
 * Applies [e] once: a redelivered event finds its row and changes nothing. An
 * event with no person (a refund names only its subscription) is matched
 * through the subscription the person paid with; one matching nobody grants
 * nothing and is dropped. True when this call applied it.
 */
export async function record(db: Db, provider: string, e: BillingEvent, now = () => new Date()): Promise<boolean> {
  let userId = e.userId && UUID.test(e.userId) ? e.userId : null;
  if (!userId && e.subscriptionId) {
    const owner = await db
      .prepare("select user_id from cloud_accounts where provider = ?1 and subscription_id = ?2")
      .bind(provider, e.subscriptionId)
      .first<{ user_id: string }>();
    userId = owner?.user_id ?? null;
  }
  if (!userId) {
    console.error(`billing event ${e.eventId} (${e.kind}) names nobody this service knows; ignored`);
    return false;
  }
  const customer = e.kind === "paid" ? e.customerId ?? null : null;
  const subscription = e.kind === "paid" ? e.subscriptionId ?? null : null;

  for (let attempt = 0; attempt < 8; attempt++) {
    if (await seen(db, provider, e.eventId)) return false;
    const before = await read(db, userId);
    const at = now();
    const after = applyEvent(before, e, at);
    // Write the account only if the event is new and nobody wrote it since the
    // read; then record the event only if that write is the one that landed:
    // the row then carries this event's id and the next version. Another
    // event's write in between leaves its own id there, and both are no-ops.
    await db.batch([
      db
        .prepare(
          `insert into cloud_accounts (user_id, plan, paid_until, trial, provider, customer_id, subscription_id, version, updated_at, last_event)
           select ?1, ?2, ?3, ?4, ?5, ?6, ?7, 1, ?8, ?9
            where not exists (select 1 from cloud_billing_events where provider = ?5 and event_id = ?9)
              and coalesce((select version from cloud_accounts where user_id = ?1), 0) = ?10
           on conflict (user_id) do update set
             plan = excluded.plan,
             paid_until = excluded.paid_until,
             trial = excluded.trial,
             provider = excluded.provider,
             customer_id = coalesce(excluded.customer_id, cloud_accounts.customer_id),
             subscription_id = coalesce(excluded.subscription_id, cloud_accounts.subscription_id),
             version = cloud_accounts.version + 1,
             updated_at = excluded.updated_at,
             last_event = excluded.last_event`,
        )
        .bind(
          userId,
          after.plan,
          after.paidUntil?.toISOString() ?? null,
          after.trial ? 1 : 0,
          provider,
          customer,
          subscription,
          at.toISOString(),
          e.eventId,
          before.version,
        ),
      db
        .prepare(
          `insert into cloud_billing_events (provider, event_id, user_id, kind, received_at)
           select ?1, ?2, ?3, ?4, ?5
            where not exists (select 1 from cloud_billing_events where provider = ?1 and event_id = ?2)
              and exists (select 1 from cloud_accounts
                           where user_id = ?3 and version = ?6 and last_event = ?2)`,
        )
        .bind(provider, e.eventId, userId, e.kind, at.toISOString(), before.version + 1),
    ]);
    // Recorded now means this attempt landed (or a twin delivery did); either way, done.
    if (await seen(db, provider, e.eventId)) return true;
  }
  throw new Error(`billing event ${e.eventId}: too many concurrent writes for one account`);
}
