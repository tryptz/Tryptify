# Trypt Mania paid charting: price (draft, 2026-10-28)

Paid charting with a 1-song trial, on the **TryptMania Parser's Railway
server only** (CPU, owner's decision 2026-10-28). The Modal GPU is not sold.
Charting on a server the listener adds themselves (Settings › Connections)
stays free and unchanged.

The address stays a secret and is still never put in the app's default API
list (owner's rule): the app reaches paid charting signed in, never by a
built-in address.

Same rule as Tryptify Cloud: **a listener can never cost more than they
pay.** Every price below is checked against the worst case, with the same
fee path and the same 20% rule (`tryptify-cloud.md`, Pricing).

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

One-time purchases, not a subscription. Songs are estimated at 4 minutes,
about a pop song's length; shorter songs give more, longer ones fewer.

| Pack | Minutes | About | Price | Per minute | Worst profit |
|---|---|---|---|---|---|
| Starter | 600 | 150 songs | $2.99 | 0.50¢ | +$0.40 (25%) |
| Library | 2,500 | 625 songs | $9.99 | 0.40¢ | +$1.52 (23%) |
| Big library | 5,000 | 1,250 songs | $19.99 | 0.40¢ | +$3.38 (25%) |

These are the most minutes each price can carry and still pass the 20% rule.
By the minute a pack holds about twice the songs it did by the song, since
the worst case is no longer a 10-minute song. Under $2.99 the payment's fixed
fee, not the server, is what costs.

Net is the lower of the two worst fee paths Cloud uses: web with 27% VAT,
8.5% and 50¢, or Play's 15% with 27% VAT. Creem (3.9% + 40¢) is cheaper than
that path, so real profit is higher; on typical songs (0.16¢ a minute, not 0.2¢)
higher. No pack under $2.99: Creem's floor is $1.00, and the fixed fee eats
most of a small one.

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

So about 2¢ a song (0.4–0.5¢ a minute) is cheap next to stem splitting alone, but the chart makers
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
  Railway bill, and profit, by day.

## The fixed cost (measured; fixed in Parser `03ff903`, not yet deployed)

Railway's metrics for the Parser service over the 7 days to 2026-10-28:
6.5 vCPU and 11.4 GB used on average (limits 24 and 24), about **$129 + $114
a month**. The memory is the surprise: it sits near 11 GB on average while
the server is mostly idle, and peaks at its 24 GB limit. That cost exists
with or without paid charting, and packs only cover it with enough buyers.
The 72 hours before showed the same: about 10 GB held while CPU averaged under
one core. The cause: the model stayed loaded for good after a song, and glibc
kept what each separation thread freed. The Parser now unloads it after a
minute idle and hands the memory back. Not yet measured on Railway: it ships
when the branch is merged to `main`. Railway's Serverless setting (sleep
after about 10 idle minutes) would take idle to zero, for a cold start of a
few seconds more on the next song.

## Where it runs

- The Parser stays as it is for the listener's own servers. Paid charting
  adds a signed-in route: the app sends its Supabase token, the Parser asks
  Cloud (`tryptify-servers/cloud/`, which holds credits and the trial in D1)
  to take a credit, charts, and gives the credit back if it fails.
- Railway builds the Parser from `main` on push, so work on a branch does
  not deploy until it is merged.

## Open decisions for the owner

1. The three minute packs and prices above, or other sizes.
2. Trial daily cap: 500?
3. Sell on Play as well as web? (The prices above already pass both.)
4. Merge the Parser fix to `main` (deploys it), and switch on Railway's
   Serverless sleep?

Sources: [Railway pricing plans](https://docs.railway.com/pricing/plans),
[Railway: understanding your bill](https://docs.railway.com/pricing/understanding-your-bill).
