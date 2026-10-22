# Tryptify Cloud server

**Your personal Subsonic-API music storage, made for fast streaming.** A
listener uploads their own music and plays it anywhere, in Tryptify or any
Subsonic-compatible player, from storage that is theirs alone. The plan, prices and rules
are in [`docs/plans/tryptify-cloud.md`](../../docs/plans/tryptify-cloud.md).
It runs on **Cloudflare Workers** (`src/worker.ts`), keeps accounts and
billing events in **Cloudflare D1** (SQLite, `schema.sql`), and signs people
in with the app's Supabase account the same way as the
[Theme Store](../theme-store/README.md). Music will go in **Cloudflare R2**
(phase 1). It moved here from Railway, which was pausing deploys for Hobby
workspaces, before it ever went live there.

**Licence:** [PolyForm Noncommercial 1.0.0](LICENSE.md), unlike the rest of
the repository (GPL-3.0). Anyone may read, run and change it for
noncommercial purposes; running it as a paid service needs the author's
permission.

## Status

**Live, taking real payments in Creem live mode since 2026-10-21**, at
`https://cloud.tryptify.com` (the app's default), where the live webhook
points (`/webhooks/billing`); it is still reachable at
`https://tryptify-cloud.tryptify-cloud.workers.dev` for older builds. Before
that it ran in test mode, where accounts, billing and the trial worked end to
end: a test-card payment for Plus
reached the Worker as Creem's signed webhook and gave the account Plus for one
month in D1, recorded once. Phase 1's server is live too: uploads into R2 within the
quota, the Subsonic API over the stored library, and the daily cleanup; checked
in Cloudflare's own runtime (`wrangler dev`) with a 60 MB two-part upload that
streamed back identical, a seek across the part boundary included. The app's
screens are next. Without `CREEM_API_KEY` and `CREEM_WEBHOOK_SECRET`, checkout and the
webhook answer `billing_not_set_up`, and nobody can pay or be granted anything.

## Where it runs

| Piece | Cloudflare | Notes |
| --- | --- | --- |
| The API | Worker `tryptify-cloud` | `src/worker.ts`, config in `wrangler.toml` |
| Accounts, billing events | D1 database `tryptify-cloud` | `b2b03937-98bd-4fec-a132-ce0d0b932454`, tables from `schema.sql` |
| Music (phase 1) | R2 bucket `tryptify-cloud-music` | Binding `MUSIC`; enabled and checked 2026-10-21 (a 1 MB file written, read back identical, deleted). Empty until phase 1 |
| Secrets | Worker secrets | `CREEM_API_KEY`, `CREEM_WEBHOOK_SECRET`, `PLAY_KEY` (signs the web player's links; random, set 2026-10-22); never in the repository |

Code: `src/rules.ts` (the money rules, pure), `src/creem.ts` (checkout and
webhooks), `src/store.ts` (D1), `src/auth.ts` (Supabase sign-in),
`src/app.ts` (routes), `src/worker.ts` (the entry point).

D1 has no row locks, so a billing event is written optimistically
(`store.record`): the account row is written only if its version is unchanged
since it was read and the event is new, and the event is recorded only if that
write is the one that landed (the row then carries the event's id). A second
webhook for the same person in between turns both into no-ops and the first is
retried. A test races two payments to hold it.

## Deploying a change

```
cd server/cloud
bun install && bun run test.ts      # all must pass
npx wrangler deploy                 # needs CLOUDFLARE_API_TOKEN (Workers edit)
```

A schema change goes in `schema.sql` as `create … if not exists` / an added
column, run once against D1 (`npx wrangler d1 execute tryptify-cloud --remote
--file schema.sql`) before the deploy that uses it. Then check `/health`.

## Payments: Creem

[Creem](https://www.creem.io) is the merchant of record: it charges the card,
handles VAT and sales tax, refunds and chargebacks, and pays the owner out.
It was chosen because it needs no Stripe account. Fee 3.9% + $0.40.

What the service does with it:

- **Checkout** (`POST /v1/checkout`) asks Creem's API for a checkout link for
  the plan's product, with the signed-in person's id in `metadata.userId`.
  Only the server holds the API key, so nobody can attach someone else's id.
- **`subscription.paid`** is the only event that grants anything: the plan
  from the product id, paid until Creem's `current_period_end_date`.
- **`refund.created`** for the whole payment, and **`dispute.created`** (a
  chargeback), end access with 2 days to export. A partial refund does not.
- Everything else (cancelled, past due, paused, expired) needs nothing: no
  payment arrives, so access ends by itself at the paid-until date.
- Every webhook is checked against the `creem-signature` header (HMAC-SHA256
  of the raw body with the webhook secret) before anything in it is read.

### Setting it up (the owner, once)

1. Sign up at creem.io, pass the ID check, and add a payout account in the
   name on the ID.
2. **Start in test mode.** Create six subscription products, one per plan
   and period, at the prices in `PLANS` (Lite $3.99 / $39.99, Plus $9.99 /
   $99.99, Max $19.99 / $199.99). Tax included or added on top are both
   inside the worst case the prices were checked against.
3. Under Developers, copy the API key, and add a webhook to
   `<the service's address>/webhooks/billing` with at least
   `subscription.paid`, `refund.created` and `dispute.created`. Copy its
   secret.
4. On the Worker, set these (secrets with `wrangler secret put`, the rest in
   `wrangler.toml`; never a secret in chat or the repository):

   | Variable | Value |
   | --- | --- |
   | `CREEM_API_KEY` | the API key |
   | `CREEM_WEBHOOK_SECRET` | the webhook's secret |
   | `CREEM_API_BASE` | `https://test-api.creem.io` while testing; remove it to go live |
   | `CREEM_PRODUCT_LITE_MONTHLY` … `CREEM_PRODUCT_MAX_YEARLY` | the six product ids (`prod_…`) |
   | `CREEM_SUCCESS_URL` | optional: the page shown after paying |

5. `/health` then says `"billing": true`. Buy a plan with Creem's test card,
   and `GET /v1/account` shows it.
6. To go live, repeat 2 to 4 in live mode with the live key, secret and
   product ids, and remove `CREEM_API_BASE`.

### Test mode, as set up on 2026-10-21

The six products exist in Creem's **test** mode (USD, tax included in the
price, tax category SaaS), created through the API, and the service's own
adapter made a checkout link with them against `test-api.creem.io`. Product
ids are not secrets:

| Variable | Test-mode product |
| --- | --- |
| `CREEM_PRODUCT_LITE_MONTHLY` | `prod_6eEoCud9ol0vn8H4OyJpt5` ($3.99) |
| `CREEM_PRODUCT_LITE_YEARLY` | `prod_2JlQubP2pnowVTuzXkfiGu` ($39.99) |
| `CREEM_PRODUCT_PLUS_MONTHLY` | `prod_5pm12qQ3N2KCCG6qtZ3dtV` ($9.99) |
| `CREEM_PRODUCT_PLUS_YEARLY` | `prod_1TGw8qSxCjKxq0MSiLuyI0` ($99.99) |
| `CREEM_PRODUCT_MAX_MONTHLY` | `prod_21MRJ0oZMS7dusCMGBLX0l` ($19.99) |
| `CREEM_PRODUCT_MAX_YEARLY` | `prod_7TtInxGK5fNOs0VKHHpdY3` ($199.99) |
| `CREEM_PRODUCT_LITE_MONTHLY_TRIAL` | `prod_7B4iLXRJKPaeSjOy4BmBir` ($3.99, 7-day trial) |
| `CREEM_PRODUCT_PLUS_MONTHLY_TRIAL` | `prod_7CntKejjZyg6Lt3BbmtIkQ` ($9.99, 7-day trial) |
| `CREEM_PRODUCT_MAX_MONTHLY_TRIAL` | `prod_3S0jO8p2OwGiaVQHg3xO59` ($19.99, 7-day trial) |

### Live mode, created on 2026-10-21

The same nine exist in Creem's **live** mode (checked back: nine, USD, tax
included, trials only on the three trial products). The Worker uses them
since 2026-10-21: `wrangler.toml` has these ids and no `CREEM_API_BASE`, and
the live `CREEM_API_KEY` and the live webhook's `CREEM_WEBHOOK_SECRET` are
Worker secrets. A live checkout link was made with them before the switch:

| Variable | Live product |
| --- | --- |
| `CREEM_PRODUCT_LITE_MONTHLY` | `prod_63elKtKRItGQSMCIfqquAz` ($3.99) |
| `CREEM_PRODUCT_LITE_YEARLY` | `prod_1bjumyDR0OUeJWohM5rPWy` ($39.99) |
| `CREEM_PRODUCT_PLUS_MONTHLY` | `prod_28Df3920dPkS1izcsr6VGO` ($9.99) |
| `CREEM_PRODUCT_PLUS_YEARLY` | `prod_1HSF4zKQOe2zwqn6ulQXcF` ($99.99) |
| `CREEM_PRODUCT_MAX_MONTHLY` | `prod_4b9i4eCaYxDS9QwrZKhYV2` ($19.99) |
| `CREEM_PRODUCT_MAX_YEARLY` | `prod_6G4ubzzqvCaJKaamWsnHMt` ($199.99) |
| `CREEM_PRODUCT_LITE_MONTHLY_TRIAL` | `prod_56tTODaE022GeT50ruLqXj` ($3.99, 7-day trial) |
| `CREEM_PRODUCT_PLUS_MONTHLY_TRIAL` | `prod_OSmi11W9RYftN5oUWt9rz` ($9.99, 7-day trial) |
| `CREEM_PRODUCT_MAX_MONTHLY_TRIAL` | `prod_6dOHBryZ66HGZnk9wjwydV` ($19.99, 7-day trial) |

**Test purchase, 2026-10-21: passed end to end.** The service ran locally
against a fresh Postgres with the test products and key; a Lite monthly
checkout was made through `POST /v1/checkout`, paid on Creem's test checkout
with the test card 4111 1111 1111 1111, and Creem's real `subscription.paid`
webhook, forwarded by `creem listen` with its genuine signature, gave the
account `lite`, `full` access, paid until one month on, 100 GB. The event was
recorded once. Only Supabase sign-in was a stand-in. To test a change locally
before deploying it:

```
creem listen --environment test --forward-to http://127.0.0.1:3000/webhooks/billing \
  --events subscription.paid,refund.created,dispute.created
```

with `CREEM_WEBHOOK_SECRET` set to the secret it prints. Stopping it deletes
its temporary endpoint at Creem.

## The free trial

7 days with 20 GB, card required, once per account, monthly plans only. The
card and the 7 days are Creem's: three extra products carry
`trial_period_days: 7` (`CREEM_PRODUCT_<PLAN>_MONTHLY_TRIAL`), and
`POST /v1/checkout` with `"trial": true` uses them only for an account that
has never had a plan or a trial (`409 trial_used` otherwise). After 7 days
Creem charges the card for the first month, unless it was cancelled.

**Creem reports a trial's start as a payment.** In test mode a trial checkout
sent `subscription.trialing` and a $0 `subscription.paid`, both with status
`trialing` and the trial's end as the period end. Read as an ordinary payment,
that unlocked the whole plan (up to 500 GB) for free. So a `paid` with status
`trialing` is a trial (`trial: true`): 20 GB until it ends, never replacing
time someone paid for, and if no payment follows, the refund's 2 days to
export, not 7. The first real charge makes it the full plan. The replayed
capture is a test (`Creem's real trial start …`).

A trial that is cancelled costs at most 20 GB for 9 days, about $0.10. That is
the one place a non-payer costs anything, by the owner's choice; the test
`the trial's worst case costs pennies` keeps it so.

## Unlimited storage, given by hand

An account in `cloud_grants` (kind `unlimited`) has full access with no quota
(`UNLIMITED_BYTES`, a petabyte, so the arithmetic still works) and no end
date, whatever it has paid for. It is never sold, never in `/plans`, never
changed by a webhook, and the daily cleanup skips it. `/v1/account` says
`unlimited: true`, and the app and the site show "Unlimited storage" with
no subscription to manage. A grant needs an account row too: that row keeps
the bytes used. In D1:

```sql
insert into cloud_grants (user_id, kind, note, created_at)
  values ('<user id>', 'unlimited', '<who and why>', '<ISO date>');
insert into cloud_accounts (user_id) values ('<user id>') on conflict (user_id) do nothing;
-- to take it back:
delete from cloud_grants where user_id = '<user id>';
```

The user id is the Supabase account's (`auth.users.id`). Taken back, the
account falls back to whatever it paid for, and with nothing paid its
library is deleted by the cleanup after the grace days, like any other.

Granted so far: the owner's account (tryptz, 2026-10-22).

## The rule it enforces

A listener can never cost more than they pay. Access comes only from the date
they have paid until (`accessOf`): a payment moves it forward (`applyEvent`),
and nothing else does. If payments stop, for any reason, access ends by
itself at that date; the library is then read-only (play, export) for 7 days
and deleted. A refund leaves 2 days. A quota is never exceeded: an upload
that does not fit is refused (`mayUpload`).

## Routes

| Route | Does |
| --- | --- |
| `GET /health` | Up, with whether it has a database, music storage and a payment provider |
| `GET /plans` | The plans, prices and the trial's terms, no account needed |
| `GET /v1/account` | Plan, `access` (`full`, `read_only`, `none`), paid-until, delete-after, used and quota bytes, `trial`, `trialAvailable` |
| `POST /v1/checkout` | `{"plan", "period", "trial"?}` → `{"url"}`, a checkout link that carries the person's id |
| `POST /webhooks/billing` | Creem's webhook, signature checked; each event applied once (`cloud_billing_events`) |
| `GET /v1/library` | Used, reserved and allowed bytes, song count, part size |
| `POST /v1/uploads` | `{size, suffix, meta}` → `{id, partSize, parts}`; reserves `size` against the quota (413 `over_quota`, 402 `no_plan`, 409 `read_only`, 415 `not_audio`) |
| `PUT /v1/uploads/:id/parts/:n` | One part as the raw body: exactly `partSize` (50 MB) except the last → `{partNumber, etag}` |
| `POST /v1/uploads/:id/complete` | `{parts}` → the song; the file must be exactly the reserved size |
| `DELETE /v1/uploads/:id` | Gives the upload and its reservation up |
| `PUT /v1/songs/:id/cover` | JPEG, PNG or WebP up to 1 MB, counted in the quota, with its Content-Length (411 `length_required` without) |
| `DELETE /v1/songs/:id` | The song, its file and cover; the space comes back |
| `GET /v1/songs/:id/play` | `{url, expiresAt, contentType, suffix}`: a link the web player's `<audio>` can play for 2 hours (402 `no_plan`, 404 `not_found`; read-only still plays) |
| `GET /play/:id?u&e&s` | The song by that link, whole or a Range. The HMAC (`PLAY_KEY`) names the person, the song and the expiry, so none can be changed (403 `bad_link`, `link_expired`). CORS for tryptify.com only, so the player can draw its spectrum; the signature, not the origin, is the gate. Without `PLAY_KEY` it answers 503 |
| `POST /v1/subsonic-keys` | `{label?}` → `{server, username, password}`: one device's Subsonic sign-in (at most 10 kept) |
| `DELETE /v1/subsonic-keys` | Signs every device out of the Subsonic API |
| `/rest/<method>.view` | The Subsonic API (`src/subsonic.ts`): ping, getLicense, getMusicFolders, getAlbumList2, getAlbum, getArtists, getArtist, getSong, search3, getRandomSongs, stream and download (with Range), getCoverArt |

### Storage rules

- **An upload's size is reserved from its first request**: used + reserved +
  this ≤ quota, checked and reserved in one statement, so two uploads cannot
  both take the last space. At most 6 open at once. The finished file must be
  exactly that size, or it is deleted and not counted.
- **Covers count** against the quota as well.
- **Files** live under `u/<account id>/` in R2; nothing outside a person's own
  folder is reachable through their sign-in.
- **The daily cleanup** (Cron Trigger, 04:17 UTC) deletes libraries past their
  delete-after date and aborts uploads open longer than six hours. The bucket
  also aborts incomplete multipart uploads after one day (lifecycle rule
  `abort-stale-uploads`).
- **Streaming** goes through the Worker. Workers bill neither bandwidth nor the
  time spent waiting on R2, and R2 bills no egress, so a play costs requests
  only.

## Testing

The Worker's own routes over a real in-memory SQLite (the engine D1 is), with
stand-ins for Supabase's public keys and Creem's API. Nothing else needed:

```
bun install && bun run test.ts
```

One test reruns the pricing check from the plan against `PLANS`, so a price
or quota change that would lose money in the worst case fails here.
