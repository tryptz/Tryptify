# Trypt Mania GPU charting: price (draft, 2026-10-28)

Paid Fast charting: Tryptify runs the GPU, the listener buys songs. Charting
on a server the listener adds themselves (Settings › Connections) stays free
and unchanged.

Same rule as Tryptify Cloud: **a listener can never cost more than they
pay.** Every price below is checked against the worst case, with the same
fee path and the same 20% rule (`tryptify-cloud.md`, Pricing).

## What one song costs us

Modal, per second while a container is up (pricing page, July to Aug 2026):

| Part | Rate |
|---|---|
| L4 GPU | $0.000222/s ($0.80/h) |
| 2 CPU cores | $0.0000262/s |
| 16 GiB memory | $0.0000355/s |
| Plus 15% (Worker, D1, rounding), as Cloud | |
| **Total** | **$0.000326/s ($1.17/h)** |

A song takes 0.15 s of GPU per second of music (`ChartPace.DEFAULT_GPU_RATIO`,
measured on the L4 with htdemucs_ft).

- **Typical:** a 4-minute song in a batch, warm GPU: 36 s, **about 1.2¢**.
- **Worst:** a 10-minute song charted alone: 90 s of work, a 20 s cold start
  and 30 s idle before the GPU sleeps: 140 s, **4.6¢, priced as 5¢**.

The worst case is charted alone, because start and idle are paid once per
batch, and a listener can always send one song at a time. So a song is
priced at 5¢ of cost whatever the pack size.

## The trial: 1 free song

- One song, once per account, signed in. No card: it costs at most 5¢.
- Kept on the server (like Cloud's `mayStartTrial`), never trusted from the app.
- Up to 10 minutes, like any one credit. If the chart fails, the trial comes back.
- A daily cap on trials across all accounts (say 500 a day, at most $25)
  as a kill switch against throwaway accounts.

## Song packs

One-time purchases, not a subscription: GPU time is paid per use.

| Pack | Songs | Price | Per song | Worst profit |
|---|---|---|---|---|
| Starter | 25 | $2.99 | 12¢ | +$0.35 (22%) |
| Library | 100 | $9.99 | 10¢ | +$1.52 (23%) |
| Big library | 500 | $49.99 | 10¢ | +$8.46 (25%) |

Net is the lower of the two worst fee paths Cloud uses: web with 27% VAT,
8.5% and 50¢, or Play's 15% with 27% VAT. Creem (3.9% + 40¢) is cheaper
than that path, so real profit is higher; on typical songs (1.2¢, not 5¢)
it is far higher.

**Why no bulk discount:** the worst case per song does not shrink with the
pack, and Play's 15% grows with it. A cheaper big pack would fail the check.
If real usage shows songs nearly always come in batches, rerun the numbers;
never guess (Cloud's first draft lost money).

No pack under $2.99: Creem's floor is $1.00, and at $0.99 the fixed fee
leaves room for about 2 songs.

## Credit rules

- 1 credit = 1 song up to 10 minutes; each further 10 minutes is 1 more.
- Checked and taken on the server before the GPU starts; a failed chart
  gives the credit back.
- Credits never expire. Unused credits refundable within 14 days.

## Where it runs

- A Cloud endpoint (`tryptify-servers/cloud/`) checks the account's credits,
  then calls the GPU. The GPU's address and key stay server secrets.
- This is **not** the TryptMania Parser chart server, and nothing is added to
  the default API list (owner's rule). The app asks Cloud, signed in.
- The batch confirmation already shows time and cost (`ChartPace`); for paid
  charting it shows credits instead: "12 songs, 12 credits, about 3 minutes".

## Open decisions for the owner

1. The three packs and prices above, or other sizes.
2. Idle before the GPU sleeps: 30 s (priced above) vs longer (faster
   back-to-back songs, higher worst case, fewer songs per pack).
3. Trial daily cap: 500?
4. Sell on Play as well as web? (Prices above already pass both.)

Sources: [Modal pricing](https://modal.com/pricing),
[computeprices L4 on Modal](https://computeprices.com/providers/modal/gpus/l4),
[budgetforge Modal 2026](https://www.budgetforge.dev/tools/modal-pricing-2026).
