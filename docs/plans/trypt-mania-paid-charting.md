# Trypt Mania paid charting: price (draft, 2026-10-28)

Paid charting with a 1-song trial, on the **TryptMania Parser's Railway
server only** (CPU, owner's decision 2026-10-28). The Modal GPU is not sold.
Charting on a server the listener adds themselves (Settings › Connections)
stays free and unchanged.

The address stays a secret and is still never put in the app's default API
list (owner's rule): the app reaches paid charting signed in, never by a
built-in address.

Same rule as Tryptify Cloud: **a listener can never cost more than they
pay.** Every price below is checked against the worst case, and keeps at
least 20% of what is left after fees (`tryptify-cloud.md`, Pricing).

Paid through **Creem only** (owner, 2026-10-28): no Google Play, no PayPal.

## What a minute costs

Railway bills by the minute for what a service uses (docs, Oct 2026):
$0.000463 per vCPU-minute ($20 a month) and $0.000231 per GB-minute
($10 a month). Plus 15% for rounding, as Cloud.

A song takes 0.40 s on 4 cores per second of music
(`ChartPace.DEFAULT_CPU_RATIO`, `htdemucs_6s` as ONNX).

Since Parser `03ff903` (owner: as low cost as possible) the model is let go
a minute after the last song and its memory handed back, and lyrics
(Whisper) are gone from the server, so a song holds memory only while it
runs and about a minute after.

Sold **by the minute of music** (owner, 2026-10-28). A minute of music costs
0.128¢ (0.4 minutes on 4 vCPU and 4 GB); each song adds 0.133¢ for the
minute its memory is held after. So a song counts its length **rounded up to
the minute, at least 2 minutes**, and the worst minute is a 2-minute song's:

| Song | Counts as | Costs us | Per counted minute |
|---|---|---|---|
| 1 min | 2 min | 0.26¢ | 0.13¢ |
| 2 min | 2 min | 0.39¢ | **0.19¢, the worst** |
| 4 min | 4 min | 0.64¢ | 0.16¢ |
| 10 min | 10 min | 1.41¢ | 0.14¢ |

Priced at a worst **0.2¢ a minute**.

## The trial: 1 free song

- One song, once per account, signed in. No card: it costs at most 1.5¢
  (a 10-minute song).
- Kept on the server, never trusted from the app.
- Up to 10 minutes. If the chart fails, the trial comes back.
- A daily cap on trials across all accounts (say 500 a day) as a kill switch
  against throwaway accounts: at most $7.50 a day.

## Minute packs

**2¢ a minute** (owner, 2026-10-28: the lowest prices the rule allowed,
about 0.34¢ a minute, were "kinda cheap"). One-time purchases, not a
subscription. Songs are estimated at 4 minutes, about a pop song's length;
shorter songs give more, longer ones fewer.

| Pack | Minutes | About | Price | Per minute | Worst profit |
|---|---|---|---|---|---|
| Starter | 150 | 37 songs | $2.99 | 2¢ | +$1.55 (84%) |
| Library | 500 | 125 songs | $9.99 | 2¢ | +$6.11 (86%) |
| Big library | 1,000 | 250 songs | $19.99 | 2¢ | +$12.62 (87%) |

Worst profit takes out 27% VAT, then Creem's 3.9% + 40¢, and counts every
minute at the worst counted minute (0.19¢). About 8¢ a song is still well
under stem splitting alone (LALAL.AI about 25¢ a song in bulk). No pack under
$2.99: Creem's floor is $1.00, and its 40¢ eats most of a small one.

## What others charge (searched 2026-10-28, third-party pages, unverified)

No service sells StepMania or DDR charts of the listener's own songs. The
StepMania generators found (AutoStepper, smlab, sm-autogen) are free and run
on the player's own computer. The nearest paid services:

| Service | What it does | Price |
|---|---|---|
| Beat Sage | Beat Saber maps from any song | free, Patreon-funded |
| beatmapai | Beat Saber maps | 5 free songs a month, then supporters |
| SaberForge | Beat Saber maps | 3 free a month, paid plans above |
| LALAL.AI | stem separation only | $9.99/month for 90 fast minutes; $190 for 3,000 minutes (about 25¢ a 4-minute song) |
| Moises | stem separation only | Premium $3.99/month |

So about 8¢ a song (2¢ a minute) is cheap next to stem splitting alone, but the chart makers
players know are free or give free songs every month. A free song every month
(a few cents each) is the lever if one song proves too little to hook players.

## Minute rules

- A song uses its length rounded up to the whole minute, at least 2 minutes.
- Taken on the server before the song starts; a failed chart gives it back.
- Minutes never expire. Unused minutes refundable within 14 days.
- Every song charted is recorded (account, minutes, server seconds, result),
  so both usage dashboards read the same numbers.

## Usage dashboards

- **The listener's**, in Trypt Mania: minutes left and about how many songs
  that is, what each batch used, minutes bought, and Buy more.
- **The owner's**: minutes charted, songs, free trials used, money in, the
  Railway bill, and profit, by day. Built as a claude.ai dashboard
  (2026-10-28): the Railway use is live from the Railway connector, the cost
  per minute and the packs are worked out from Railway's rates and the pack
  list; sales and minutes show "not recorded yet" until Cloud records them.
  Its link is kept out of this public repository.
- The listener's screen is in the app (`GlyphChartingScreen`, from "Generate
  charts" on Trypt Mania's menu): the meter, the free song, the packs and the
  history, read from Cloud through `ChartingAccountStore`; Buy opens the
  Creem checkout in the browser.

## The fixed cost (measured; fixed in Parser `03ff903`, live 2026-10-29)

Railway's metrics for the Parser service over the 7 days to 2026-10-28:
6.5 vCPU and 11.4 GB used on average (limits 24 and 24), about **$129 + $114
a month**. The memory is the surprise: it sits near 11 GB on average while
the server is mostly idle, and peaks at its 24 GB limit. That cost exists
with or without paid charting, and packs only cover it with enough buyers.
The 72 hours before showed the same: about 10 GB held while CPU averaged under
one core. The cause: the model stayed loaded for good after a song, and glibc
kept what each separation thread freed. The Parser now unloads it after a
minute idle and hands the memory back. Merged to `main` and deployed by
Railway on 2026-10-29 01:18 UTC: an hour later the service held **0.05 GB**
idle, down from about 10 GB. Railway's Serverless setting (sleep after about
10 idle minutes) would take idle to zero, for a cold start of a few seconds
more on the next song.

## Where it runs (built 2026-10-29, Cloud `1472f3e`)

- The Parser is untouched. Cloud (`tryptify-servers/cloud/src/charting.ts`)
  holds the minutes, the free song and the packs in D1, and
  `POST /v1/charting/analyze` takes a song's minutes, forwards the song to
  the Parser (`PARSER_URL`, a Worker secret: the app never sees the address)
  and settles from its answer. The app's stems client treats
  `/v1/charting` as a stem server, signed in with the session.
- To switch it on: run `migrations/2026-10-29-charting.sql` against D1,
  `wrangler secret put PARSER_URL`, make the three one-time products in
  Creem and put their ids in `wrangler.toml`, deploy. Until the products
  exist, the app says packs are not on sale; until `PARSER_URL` is set,
  charting there answers `charting_not_set_up` and the app falls back to the
  listener's own servers.
- Railway builds the Parser from `main` on push; the idle-memory fix
  (`03ff903`) is live there since 2026-10-29. The app no longer asks any
  server to time lyrics (`cc414473`): the Parser's Whisper went with the fix.

## Open decisions for the owner

1. The three minute packs and prices above, or other sizes.
2. Trial daily cap: 500?
3. Switch on Railway's Serverless sleep for the Parser?

Sources: [Railway pricing plans](https://docs.railway.com/pricing/plans),
[Railway: understanding your bill](https://docs.railway.com/pricing/understanding-your-bill).
