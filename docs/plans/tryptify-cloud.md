# Tryptify Cloud: plan

Agreed with the owner on 2026-10-21. Billing, the trial and phase 1's server
(uploads, the Subsonic API, cleanup) are live on Cloudflare in Creem test mode;
the app's screens are next. Positioning, the owner's words: **a personal
Subsonic-API storage made for fast music streaming**. A paid subscription that
stores a listener's own music and streams it anywhere, for people who do not
want to host a server. Build it one phase at a time, each
finished against its gate before the next starts.

## What it is

Upload your own songs from the phone; play them from any phone signed in to
the same Tryptify account. Private: a listener sees and plays only what they
uploaded. The alternative to Settings › Connections › Music server for someone
with no computer or NAS.

## Decisions

- **It speaks the Subsonic API.** The app already plays any Subsonic server
  (`data/subsonic/`, the My server page, Search, `PlaybackSource.Subsonic`), so
  a cloud that answers the same calls needs almost no new playback code. Only
  the subset the app uses: `ping`, `getAlbumList2`, `getAlbum`, `getArtists`,
  `getArtist`, `search3`, `getRandomSongs`, `stream` (with HTTP Range, so
  seeking works), `getCoverArt`. Plus its own upload and account routes.
- **Hosting: Cloudflare** (changed 2026-10-21 from Railway, which was pausing
  deploys): a Worker for the API, D1 for accounts and billing, R2 for the
  files (same $0.015/GB as the Railway bucket, with free egress written into
  its price list). Sign-in as in the Theme Store. Sign-in is the app's Supabase account, checked the way the Theme
  Store checks it (public JWKS, or Supabase Auth for HS256). The service holds
  no Supabase secret.
- **Subsonic sign-in for the cloud:** the app cannot send a Supabase JWT as
  `u`/`t`/`s`. The cloud issues a per-device API password when the app
  connects (POST with the Supabase token), and the app stores it as an ordinary
  `SubsonicServer` in token mode. Revoked on sign-out or cancellation.
- **Private locker, never a distributor** (see Legal). No sharing, no public
  links, and no cross-user de-duplication: two people uploading the same song
  store two copies.
- **Payments: both paths, one entitlement.**
  - Play Store installs: Google Play Billing subscriptions, verified on the
    server with the Play Developer API, renewals and cancellations from Real-time
    developer notifications.
  - APK / website installs: a merchant of record (Lemon Squeezy or Paddle, to
    be picked), which handles VAT and sales tax, refunds and receipts; its
    webhooks update the same record.
  - Both write one `entitlements` row per account (plan, storage limit,
    status, period end, source). The service reads only that row.
- **Files are kept as uploaded.** No transcoding in phase 1.

## Phases and gates

1. **Server, free private beta.** Accounts, upload (resumable, size and type
   checked, tags read on the server), storage quota per account, the Subsonic
   subset above, delete. Owner-only allow-list, no payments.
   Gate: the app's existing Subsonic code plays it with no changes; a second
   account sees none of the first's music; quota refuses an upload over the
   limit; a `test.ts` like the Theme Store's.
2. **App.** "Tryptify Cloud" in Settings › Connections › Music server (sign in
   with the Tryptify account, no address to type), upload from Local (a song,
   an album, a folder) with progress, usage shown against the limit. Strings in
   all seven languages, `docs/ui-invariants.md` updated.
3. **Payments.** Play Billing and the web checkout, webhooks, the entitlement
   row, grace period on failed payment, what happens to files on cancel
   (read-only, then deleted after a stated time, with a warning).
4. **Launch.** Everything under Legal done, pricing set from measured costs,
   the allow-list removed.

## Legal (have a lawyer review before charging money)

- Terms of Service and a privacy policy: what is stored (files, tags, account,
  payment status), where, for how long, and deletion on request (GDPR).
- Private locker only, as above. Sharing turns it into distribution, which
  needs licences from rights holders.
- Copyright takedowns: register a DMCA agent with the US Copyright Office;
  notice-and-action under the EU Digital Services Act; a repeat-infringer rule.
- Refund and cancellation terms; taxes through the merchant of record (web) and
  Google (Play).
- The open items from the 2026-10-21 licensing review still stand and should
  be done first: a root LICENSE (done 2026-10-21: the app GPL-3.0, the
  cloud server PolyForm Noncommercial 1.0.0),
  the upstream Monochrome licence, an in-app open-source licences screen, a
  "not affiliated" line for the services the app names, and Android backup
  rules that leave sign-ins out.

## Pricing (agreed 2026-10-21)

The owner's rule: **a listener can never cost more than they pay.** Every
plan's worst case is a profit, and the only cost not tied to a subscriber is
Railway's plan fee.

**$3.99 per 100 GB a month**, yearly at two months free:

| Plan | Storage | Monthly | Yearly | Full-quota cost | Worst profit / month |
|---|---|---|---|---|---|
| Lite | 100 GB | $3.99 | $39.99 | $1.72 | +$0.51 (yearly) |
| Plus | 250 GB | $9.99 | $99.99 | $4.31 | +$1.26 (yearly) |
| Max | 500 GB | $19.99 | $199.99 | $8.62 | +$2.53 (yearly) |
| Pro | 1 TB | $39.99 | $399.99 | $17.25 | +$5.06 (yearly) |
| Studio | 3 TB | $119.99 | $1,199.99 | $51.75 | +$15.18 (yearly) |
| Vault | 10 TB | $399.99 | $3,999.99 | $172.50 | +$50.60 (yearly) |

Pro, Studio and Vault were added on 2026-10-24 at the same $3.99 per 100 GB,
each with at least 20% of its worst-case net left over. `/plans` lists a plan
only once the payment provider has a product for it, so they appear in the app
and on the site when their PayPal plans exist.

How the numbers were made (research on 2026-10-21, sources in the session):

- Cost: Railway bucket storage $0.015 per GB-month; bucket egress and S3
  calls free. Plus 15% for the database, compute and rounding. A listener's
  cost is therefore set by their quota alone, whatever they stream.
- Worst fee path: web checkout through Lemon Squeezy with a non-US PayPal
  payment (5% + 1.5% + 1.5% + 0.5% subscription + 50¢, charged on the
  tax-inclusive total) in the EU country with the highest VAT (27%). Play is
  15% (10% service + 5% billing in the US, UK and EEA since 30 Jun 2026).
- A quota is the largest that leaves at least 20% of the worst-case net as
  profit, rounded down. Raising a quota or adding a discount means rerunning
  that check, never guessing: a first draft at $2.99/100 GB lost money.

Rules that keep the guarantee (all phase 1 or 3 requirements):

1. **Hard quota.** The server checks the quota before it issues an upload URL;
   an upload over it is refused, never billed extra. Downgrading below what is
   stored makes the library read-only until the listener is under the new
   limit.
2. **Audio never passes through the service.** Uploads are presigned PUTs and
   playback is presigned GETs, straight to the bucket. Service egress is
   $0.05/GB and unbounded: one hi-res listener streamed through the service
   could cost $12/month.
3. **No free tier. One free trial, with a card** (owner's decision,
   2026-10-21): 7 days with 20 GB, once per account, monthly plans only,
   charged at day 7 unless cancelled. Unpaid, it gets 2 days to export, so it
   costs at most about $0.10: the only cost a non-payer can cause.
4. **When payment stops: 7 days read-only, then deleted**, with reminders and
   Export everything. At full Lite quota those 7 days cost $0.40, inside the
   last payment's worst profit ($0.58). Play's grace period is set to match.
5. **No country priced below the floor.** Play converts one price into local
   prices; every country's price must still pass the worst-case check, so
   cheaper regional templates are not used without rerunning it.
6. **A dormant streaming limit**, a per-plan setting off by default, so that
   if the host ever bills bucket egress (Railway publishes no fair-use policy)
   it can be switched on without an app update. Keep the storage code plain S3
   so the bucket can move to Cloudflare R2: same storage price, free egress in
   its published terms.
7. **Fixed cost only grows with paying listeners.** Cloudflare Workers is free
   up to 100,000 requests a day and D1 up to 5 GB; the $5-a-month Workers Paid
   plan is needed only past that, which 10 Lite subscribers cover even in the
   worst case. (Written for Railway first, at $20 a month and 9 subscribers.)

What sells it to listeners, at no cost: originals kept as uploaded (lossless
stays lossless, nothing transcoded), uploads from the phone, a clear usage
meter, one price rule, cancel any time, and **Export everything** at any time,
which answers the fear left by Google Play Music and Amazon closing their
lockers. Competitors on 2026-10-21: YouTube Music uploads free (100k songs,
no phone uploads), iBroadcast free at 128 kbps or $3.99/month for lossless,
PikaPods hosted Navidrome from about $1.80/month, iTunes Match $24.99/year.

Later, for cheaper large plans: Backblaze B2 or Hetzner store at about
$7/TB, roughly half, which would let the same prices hold about twice the
storage. Their free egress is
pooled and limited (B2: three times what is stored), so only after measuring
how much listeners stream compared with what they store.

## Web payments: Creem (chosen 2026-10-21)

The owner cannot use Stripe, which also rules out Lemon Squeezy (Stripe's,
moving to Stripe Managed Payments) and Polar (Stripe Connect). Creem is a
merchant of record with its own ID check, 3.9% + $0.40, signed webhooks and a
test mode; payouts by bank transfer, USDC or Alipay, not PayPal. Paddle
(5% + $0.50, PayPal payouts, custom terms under $10) is the fallback, and
Dodo Payments a third. All three fit inside the worst case the prices were
checked against. Built into `server/cloud/` (see its README for the setup).
