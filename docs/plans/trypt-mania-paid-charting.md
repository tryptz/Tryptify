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

## What one song costs

Railway bills by the minute for what a service uses (docs, Oct 2026):
$0.000463 per vCPU-minute ($20 a month) and $0.000231 per GB-minute
($10 a month). Plus 15% for rounding, as Cloud.

A song takes 0.40 s on 4 cores per second of music
(`ChartPace.DEFAULT_CPU_RATIO`, `htdemucs_6s` as ONNX).

- **Typical:** a 4-minute song: 1.6 minutes on 4 vCPU and 4 GB, **about 0.5¢**.
- **Worst:** a 10-minute song: 4 minutes on 4 vCPU, its 4 GB held 5 minutes
  more after, **1.8¢, priced as 2¢**.

## The trial: 1 free song

- One song, once per account, signed in. No card: it costs at most 2¢.
- Kept on the server, never trusted from the app.
- Up to 10 minutes, like any one credit. If the chart fails, the trial comes back.
- A daily cap on trials across all accounts (say 500 a day, at most $10) as
  a kill switch against throwaway accounts.

## Song packs

One-time purchases, not a subscription.

| Pack | Songs | Price | Per song | Worst profit |
|---|---|---|---|---|
| Starter | 60 | $2.99 | 5¢ | +$0.40 (25%) |
| Library | 250 | $9.99 | 4¢ | +$1.52 (23%) |
| Big library | 500 | $19.99 | 4¢ | +$3.38 (25%) |

Net is the lower of the two worst fee paths Cloud uses: web with 27% VAT,
8.5% and 50¢, or Play's 15% with 27% VAT. Creem (3.9% + 40¢) is cheaper than
that path, so real profit is higher; on typical songs (0.5¢, not 2¢) far
higher. No pack under $2.99: Creem's floor is $1.00, and the fixed fee eats
most of a small one.

## Credit rules

- 1 credit = 1 song up to 10 minutes; each further 10 minutes is 1 more.
- Taken on the server before the song starts; a failed chart gives it back.
- Credits never expire. Unused credits refundable within 14 days.

## The fixed cost (measured, not yet solved)

Railway's metrics for the Parser service over the 7 days to 2026-10-28:
6.5 vCPU and 11.4 GB used on average (limits 24 and 24), about **$129 + $114
a month**. The memory is the surprise: it sits near 11 GB on average while
the server is mostly idle, and peaks at its 24 GB limit. That cost exists
with or without paid charting, and packs only cover it with enough buyers.
Worth finding what holds the memory before selling (unverified guesses: the
models staying loaded per worker, Python not handing memory back).

## Where it runs

- The Parser stays as it is for the listener's own servers. Paid charting
  adds a signed-in route: the app sends its Supabase token, the Parser asks
  Cloud (`tryptify-servers/cloud/`, which holds credits and the trial in D1)
  to take a credit, charts, and gives the credit back if it fails.
- Railway builds the Parser from `main` on push, so work on a branch does
  not deploy until it is merged.

## Open decisions for the owner

1. The three packs and prices above, or other sizes.
2. Trial daily cap: 500?
3. Sell on Play as well as web? (The prices above already pass both.)
4. Look into the held memory before or after launch?

Sources: [Railway pricing plans](https://docs.railway.com/pricing/plans),
[Railway: understanding your bill](https://docs.railway.com/pricing/understanding-your-bill).
