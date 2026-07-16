# Stripe Dashboard Setup — Runbook for Claude for Chrome

A step-by-step browser runbook for setting up the Stripe account behind Tryptify's
in-app donations and delivering the two keys into Supabase. Written for a browser
agent (Claude for Chrome) driving a real Chrome window where the user is signed in.

**End state:** `STRIPE_SECRET_KEY` and `STRIPE_PUBLISHABLE_KEY` are saved as
Supabase Edge Function secrets, and Stripe is configured to charge recurring
donations that show up as "Tryptify" with an emailed receipt.

---

## Guardrails (read first — do not skip)

1. **The secret key (`sk_...`) is a live credential.** Only ever paste it into the
   **Supabase secret value field** (Step 6). Never type or paste it into chat, a
   commit, a doc, a Google search, a URL bar, or any Stripe field. Never read it
   aloud or echo it back.
2. Use the dashboard's **Copy** button to move a key. Do not screenshot a revealed
   secret key. After copying, paste it straight into its destination, then move on.
3. **Stay in Test mode** until the user explicitly says "go live." Switching to
   live keys means real money — confirm with the user before doing it.
4. Do **not** change unrelated Stripe settings (payout schedule, other payment
   methods, team members, webhooks). Touch only what this runbook lists.
5. If a page asks for identity/bank details you don't have, **stop and ask the
   user** — do not invent information.
6. If you are ever unsure whether a field is safe to paste a key into, stop and
   ask. The only safe destination for `sk_...` is the Supabase secret.

---

## Step 0 — Prerequisites

- A Chrome window signed in to the user's **Stripe** account (dashboard.stripe.com).
  If they have no account, create one at https://dashboard.stripe.com/register and
  verify the email, then continue in **Test mode** (no bank/identity needed to test).
- The same window able to reach the user's **Supabase** dashboard.
- Confirm with the user: "Test setup first, go live later?" Default = yes.

---

## Step 1 — Turn on Test mode

1. Go to https://dashboard.stripe.com .
2. Top-right, toggle **Test mode** ON (the switch reads "Test mode"). Everything
   below stays in test until the user asks to go live.

---

## Step 2 — Set the public-facing name (so charges read "Tryptify")

1. Open https://dashboard.stripe.com/settings/public .
2. Set **Public business name** to `Tryptify`.
3. Set a **statement descriptor** (what appears on the card statement) to
   `TRYPTIFY` or `TRYPTIFY DONATION` (≤ 22 chars, letters/numbers/spaces).
4. Save.
5. Optional branding: https://dashboard.stripe.com/settings/branding — set the icon
   and accent color; these show on the Stripe checkout/receipt.

---

## Step 3 — Turn on donation receipt + subscription emails

1. Open https://dashboard.stripe.com/settings/emails .
2. Enable **"Successful payments"** (emails the donor a receipt).
3. Enable subscription-related emails if listed (upcoming renewal / card expiring),
   so recurring donors are never surprised.
4. Save.

---

## Step 4 — (Optional) Let donors cancel themselves

1. Open https://dashboard.stripe.com/settings/billing/portal .
2. Under **Cancellations**, allow customers to cancel subscriptions.
3. Activate the portal link and **Save**. (Donors can already cancel by replying to
   Stripe support, but the portal is cleaner. Not required for donations to work.)

---

## Step 5 — Copy the API keys

1. Open https://dashboard.stripe.com/test/apikeys (Test mode keys).
2. **Publishable key** — starts with `pk_test_`. Click its **Copy** button. This one
   is public; it is fine to hold on the clipboard.
3. **Secret key** — the row labeled "Secret key". Click **Reveal test key**, then its
   **Copy** button. Treat it per the guardrails: clipboard → Supabase only.
   - Do not create a restricted key unless the user asks; the standard secret key is
     what the function expects.

> When the user later goes live: use https://dashboard.stripe.com/apikeys instead,
> which gives `pk_live_` / `sk_live_`. Requires the Stripe account to be activated
> (business + bank details). Confirm with the user before switching.

---

## Step 6 — Paste the keys into Supabase (the only safe destination for the secret)

1. Open the Edge Function secrets page:
   https://supabase.com/dashboard/project/lvzorvfhhopillzlwgau/settings/functions
2. In the **Edge Function Secrets** section, click **Add new secret** and add:
   - Name `STRIPE_PUBLISHABLE_KEY`  → value = the `pk_test_...` from Step 5.
   - Name `STRIPE_SECRET_KEY`  → value = the `sk_test_...` from Step 5.
3. **Save**. Names must match exactly (case-sensitive). No redeploy is needed — the
   function reads them on the next request.
4. Clear the clipboard afterward (copy any harmless text) so the secret key is not
   left sitting on it.

---

## Step 7 — Verify

1. Trigger a donation from the app (Settings › About › Support) **or** ask the user
   to, using test card `4242 4242 4242 4242`, any future expiry, any CVC, any ZIP.
2. Confirm success in Stripe: https://dashboard.stripe.com/test/subscriptions should
   show a new active subscription, and
   https://dashboard.stripe.com/test/payments a succeeded payment.
3. If the app shows an error, check the function logs in Supabase and match the
   symptom in `supabase/AGENTS.md` (Troubleshooting map).

---

## Step 8 — Going live (only when the user says so)

1. Confirm explicitly with the user: "Switch Tryptify donations to live money?"
2. Activate the Stripe account: https://dashboard.stripe.com/account/onboarding
   (business details + bank account). The user must supply this info — do not guess.
3. Turn **Test mode** OFF, open https://dashboard.stripe.com/apikeys , and repeat
   Step 5 + Step 6 with the `pk_live_` / `sk_live_` keys, overwriting the two
   Supabase secrets.
4. Do a single real small donation ($1) to confirm, then refund it from the Stripe
   dashboard if desired.

---

## Notes

- These are **pure donations** — they must unlock no app features. That is the only
  reason Stripe (not Google Play Billing) is allowed. Do not add any "supporter
  perk" that gates functionality.
- The function that consumes these keys is already deployed to the `TryptaMusic`
  Supabase project as `create-donation-subscription`. This runbook only sets up the
  Stripe side and the two secrets.
