// Supabase Edge Function: cancel-donation-subscription
//
// Cancels a recurring donation so the donor is not charged again. Uses the Stripe
// SECRET key, which is why this lives server-side and shares the same project
// secrets as create-donation-subscription (no extra setup — set the keys once).
//
// Idempotent: a subscription that is already cancelled or no longer exists is
// reported as `{ canceled: true }` so the app can safely clear its local record.
//
// Deployed with verify_jwt disabled so the app can call it with only the project
// anon key, matching create-donation-subscription.

import Stripe from "npm:stripe@17";

// Lazy so an unset secret returns a clean error instead of crashing the worker
// on boot (new Stripe("") throws).
let stripeClient: Stripe | null = null;
function getStripe(): Stripe {
  if (!stripeClient) {
    stripeClient = new Stripe(Deno.env.get("STRIPE_SECRET_KEY")!, {
      apiVersion: "2024-06-20",
      httpClient: Stripe.createFetchHttpClient(),
    });
  }
  return stripeClient;
}

const CORS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers":
    "authorization, x-client-info, apikey, content-type",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...CORS, "Content-Type": "application/json" },
  });
}

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: CORS });
  if (req.method !== "POST") return json({ error: "Method not allowed" }, 405);

  if (!Deno.env.get("STRIPE_SECRET_KEY")) {
    return json({ error: "Stripe keys are not configured on the server." }, 500);
  }

  let payload: { subscriptionId?: string };
  try {
    payload = await req.json();
  } catch {
    return json({ error: "Invalid JSON body." }, 400);
  }

  const subscriptionId = payload.subscriptionId?.trim();
  if (!subscriptionId || !subscriptionId.startsWith("sub_")) {
    return json({ error: "Missing or invalid subscriptionId." }, 400);
  }

  try {
    const sub = await getStripe().subscriptions.cancel(subscriptionId);
    return json({ canceled: true, status: sub.status });
  } catch (err) {
    // Treat "already cancelled / no longer exists" as success so the client's
    // local record clears cleanly (e.g. donor cancelled from the Stripe email).
    const code = (err && typeof err === "object" && "code" in err)
      ? (err as { code?: string }).code
      : undefined;
    const message = err instanceof Error ? err.message : "";
    if (
      code === "resource_missing" ||
      /already been canceled|already canceled|no such subscription/i.test(message)
    ) {
      return json({ canceled: true, status: "canceled" });
    }
    console.error("cancel-donation-subscription failed", err);
    return json({ error: message || "Unexpected error." }, 500);
  }
});
