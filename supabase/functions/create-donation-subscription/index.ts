// Supabase Edge Function: create-donation-subscription
//
// Creates an *incomplete* Stripe subscription for a recurring donation and returns
// everything the Android app's PaymentSheet needs to finish payment in-app. This
// is the only place the Stripe SECRET key lives — it must never ship in the APK.
//
// Deploy:
//   supabase functions deploy create-donation-subscription --no-verify-jwt
//
// Secrets (set once, never commit these):
//   supabase secrets set STRIPE_SECRET_KEY=sk_live_xxx
//   supabase secrets set STRIPE_PUBLISHABLE_KEY=pk_live_xxx
//
// `--no-verify-jwt` lets anonymous donors call it with only the project anon key
// (which the app already sends). The function does its own input validation.

import Stripe from "npm:stripe@17";

const stripe = new Stripe(Deno.env.get("STRIPE_SECRET_KEY") ?? "", {
  // Must match the version the Stripe Android SDK expects for the ephemeral key.
  // If PaymentSheet logs an API-version warning, set this to the value it names.
  apiVersion: "2024-06-20",
  httpClient: Stripe.createFetchHttpClient(),
});

const PUBLISHABLE_KEY = Deno.env.get("STRIPE_PUBLISHABLE_KEY") ?? "";

const CORS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers":
    "authorization, x-client-info, apikey, content-type",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};

const MIN_AMOUNT = 100; // $1.00 — Stripe's practical floor for card fees
const MAX_AMOUNT = 100_000; // $1,000 — a sane ceiling to blunt abuse
const ALLOWED_INTERVALS = new Set(["day", "week", "month", "year"]);

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...CORS, "Content-Type": "application/json" },
  });
}

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: CORS });
  if (req.method !== "POST") return json({ error: "Method not allowed" }, 405);

  if (!Deno.env.get("STRIPE_SECRET_KEY") || !PUBLISHABLE_KEY) {
    return json({ error: "Stripe keys are not configured on the server." }, 500);
  }

  let payload: {
    amount?: number;
    currency?: string;
    interval?: string;
    email?: string | null;
  };
  try {
    payload = await req.json();
  } catch {
    return json({ error: "Invalid JSON body." }, 400);
  }

  const amount = Math.round(Number(payload.amount));
  const currency = (payload.currency ?? "usd").toLowerCase();
  const interval = payload.interval ?? "month";
  const email = payload.email?.trim() || undefined;

  if (!Number.isFinite(amount) || amount < MIN_AMOUNT || amount > MAX_AMOUNT) {
    return json({ error: "Donation amount is out of range." }, 400);
  }
  if (!ALLOWED_INTERVALS.has(interval)) {
    return json({ error: "Unsupported billing interval." }, 400);
  }

  try {
    // Reuse an existing customer for this email so a repeat donor doesn't pile up
    // duplicate Stripe customers; otherwise create a fresh one.
    let customer: Stripe.Customer | undefined;
    if (email) {
      const existing = await stripe.customers.list({ email, limit: 1 });
      customer = existing.data[0];
    }
    if (!customer) {
      customer = await stripe.customers.create({
        email,
        metadata: { source: "tryptify-donation" },
      });
    }

    const ephemeralKey = await stripe.ephemeralKeys.create(
      { customer: customer.id },
      { apiVersion: "2024-06-20" },
    );

    const subscription = await stripe.subscriptions.create({
      customer: customer.id,
      items: [{
        price_data: {
          currency,
          product_data: { name: "Tryptify Supporter" },
          unit_amount: amount,
          recurring: { interval: interval as Stripe.PriceCreateParams.Recurring.Interval },
        },
      }],
      payment_behavior: "default_incomplete",
      payment_settings: { save_default_payment_method: "on_subscription" },
      expand: ["latest_invoice.payment_intent"],
      metadata: { source: "tryptify-donation" },
    });

    const invoice = subscription.latest_invoice as Stripe.Invoice;
    const paymentIntent = invoice?.payment_intent as Stripe.PaymentIntent | null;
    const clientSecret = paymentIntent?.client_secret;

    if (!clientSecret) {
      return json({ error: "Could not initialize payment." }, 500);
    }

    return json({
      paymentIntentClientSecret: clientSecret,
      ephemeralKey: ephemeralKey.secret,
      customerId: customer.id,
      publishableKey: PUBLISHABLE_KEY,
      subscriptionId: subscription.id,
    });
  } catch (err) {
    console.error("create-donation-subscription failed", err);
    const message = err instanceof Error ? err.message : "Unexpected error.";
    return json({ error: message }, 500);
  }
});
