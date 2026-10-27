// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// The daily cleanup (a Cron Trigger): what keeps a person who stopped paying
// from costing storage. Libraries past their delete-after date are deleted,
// and uploads left open longer than STALE_UPLOAD_MS are aborted, giving back
// their reservations. R2 itself also aborts incomplete multipart uploads
// after a day (a lifecycle rule on the bucket), in case this misses one.

import { wipeLibrary, type Bucket } from "./library";
import { accessOf, REFUND_EXPORT_DAYS, DAY_MS, type PlanId } from "./rules";
import type { Db } from "./store";

export const STALE_UPLOAD_MS = 6 * 60 * 60 * 1000;

export async function cleanup(db: Db, bucket: Bucket, now: Date): Promise<{ wiped: number; aborted: number }> {
  // Any account whose paid-until is older than the shortest grace could be due.
  const due = await db
    .prepare(
      `select a.user_id, a.plan, a.paid_until, a.used_bytes, a.trial from cloud_accounts a
        where a.paid_until is not null and a.paid_until < ?1
          and not exists (select 1 from cloud_grants g where g.user_id = a.user_id)
          and (a.used_bytes > 0
               or exists (select 1 from cloud_uploads u where u.user_id = a.user_id)
               or exists (select 1 from cloud_songs s where s.user_id = a.user_id))`,
    )
    .bind(new Date(now.getTime() - REFUND_EXPORT_DAYS * DAY_MS).toISOString())
    .all<{ user_id: string; plan: PlanId | null; paid_until: string; used_bytes: number; trial: number }>();
  let wiped = 0;
  for (const a of due.results) {
    const { access } = accessOf(
      { plan: a.plan, paidUntil: new Date(a.paid_until), usedBytes: Number(a.used_bytes), trial: a.trial === 1 },
      now,
    );
    if (access !== "none") continue;
    await wipeLibrary(db, bucket, a.user_id);
    wiped++;
  }

  const stale = await db
    .prepare("select id, key, r2_upload from cloud_uploads where created_at < ?1")
    .bind(new Date(now.getTime() - STALE_UPLOAD_MS).toISOString())
    .all<{ id: string; key: string; r2_upload: string }>();
  for (const u of stale.results) {
    await bucket.resumeMultipartUpload(u.key, u.r2_upload).abort().catch(() => {});
    await db.prepare("delete from cloud_uploads where id = ?1").bind(u.id).run();
  }
  return { wiped, aborted: stale.results.length };
}
