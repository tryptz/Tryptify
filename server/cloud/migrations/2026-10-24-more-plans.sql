-- SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
-- Mini, Pro, Ultra and Xtra (2026-10-24). SQLite can't change a column's
-- check in place, so cloud_accounts is rebuilt with the wider list and every
-- row copied across, columns named. Run once, before the deploy that sells
-- the new plans:
--   npx wrangler d1 execute tryptify-cloud --remote --file migrations/2026-10-24-more-plans.sql
create table cloud_accounts_new (
  user_id          text    primary key,
  plan             text    check (plan in ('mini', 'lite', 'plus', 'max', 'pro', 'ultra', 'xtra')),
  paid_until       text,
  used_bytes       integer not null default 0 check (used_bytes >= 0),
  trial            integer not null default 0,
  provider         text,
  customer_id      text,
  subscription_id  text,
  version          integer not null default 0,
  last_event       text,
  updated_at       text
);
insert into cloud_accounts_new
  (user_id, plan, paid_until, used_bytes, trial, provider, customer_id, subscription_id, version, last_event, updated_at)
  select user_id, plan, paid_until, used_bytes, trial, provider, customer_id, subscription_id, version, last_event, updated_at
  from cloud_accounts;
drop table cloud_accounts;
alter table cloud_accounts_new rename to cloud_accounts;
create index if not exists cloud_accounts_subscription on cloud_accounts (provider, subscription_id);
