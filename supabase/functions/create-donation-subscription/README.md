# create-donation-subscription

Supabase Edge Function that backs Tryptify's **in-app recurring donations**. It
creates an *incomplete* Stripe subscription and returns the parameters the
Android `PaymentSheet` needs to complete payment without leaving the app.

The Stripe **secret key never ships in the APK** — it lives here as a Supabase
secret. The app only sends a tier (amount + interval) and the public project
anon key it already uses for auth.

## One-time setup

1. **Create the Stripe keys** in the [Stripe Dashboard](https://dashboard.stripe.com/apikeys).
   You need the **secret** key (`sk_live_…` / `sk_test_…`) and the **publishable**
   key (`pk_live_…` / `pk_test_…`).

2. **Set them as function secrets** (do not commit them):
   ```bash
   supabase secrets set STRIPE_SECRET_KEY=sk_live_xxx
   supabase secrets set STRIPE_PUBLISHABLE_KEY=pk_live_xxx
   ```

3. **Deploy the function** (anonymous donors call it with only the anon key, so
   JWT verification is disabled):
   ```bash
   supabase functions deploy create-donation-subscription --no-verify-jwt
   ```

That's it — the app targets
`https://<project>.supabase.co/functions/v1/create-donation-subscription`,
which already matches the project configured in the app.

## Request / response

`POST` JSON:
```json
{ "amount": 500, "currency": "usd", "interval": "month", "email": "optional@x.com" }
```
`amount` is in minor units (cents). Response:
```json
{
  "paymentIntentClientSecret": "pi_..._secret_...",
  "ephemeralKey": "ek_...",
  "customerId": "cus_...",
  "publishableKey": "pk_...",
  "subscriptionId": "sub_..."
}
```

## Notes

- **API version.** The ephemeral key is created with `apiVersion: "2024-06-20"`.
  If PaymentSheet logs an API-version mismatch, change both `apiVersion` values in
  `index.ts` to the version it names, then redeploy.
- **Test first.** Use test keys and card `4242 4242 4242 4242` (any future expiry
  / any CVC) before switching to live keys.
- **Google Play policy.** These are pure donations that unlock **no** app
  features, which is why processing them through Stripe rather than Play Billing
  is allowed. Keep it that way — do not gate functionality behind a donation.
- **Managing / cancelling.** Donors cancel from the Stripe receipt email, or you
  can enable the [Stripe customer portal](https://dashboard.stripe.com/settings/billing/portal).
