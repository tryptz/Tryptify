package tf.monochrome.android.data.donation

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Calls the Supabase Edge Function that creates a Stripe subscription and returns
 * the [DonationSubscription] parameters the in-app PaymentSheet needs.
 *
 * Why an Edge Function: the Stripe **secret** key must never ship inside an APK
 * (it can be extracted and used to move money), so all secret-key work happens
 * server-side. We reuse the app's existing Supabase project — the same base URL
 * and public anon key already used for auth — so there is no new infrastructure
 * or config to embed here; only the function itself has to be deployed with the
 * secret set as a Supabase secret (see `supabase/functions/`).
 */
@Singleton
class DonationBackend @Inject constructor(
    private val httpClient: HttpClient,
) {
    suspend fun createSubscription(request: CreateDonationRequest): Result<DonationSubscription> {
        return try {
            val response = httpClient.post("$FUNCTIONS_BASE_URL/create-donation-subscription") {
                // The anon key is a public, publishable credential (it is already
                // committed in SupabaseAuthManager). Edge Functions require it to
                // pass the gateway; the function itself is what holds the Stripe key.
                header("Authorization", "Bearer $SUPABASE_ANON_KEY")
                header("apikey", SUPABASE_ANON_KEY)
                contentType(ContentType.Application.Json)
                setBody(request)
            }
            if (response.status.isSuccess()) {
                Result.success(response.body<DonationSubscription>())
            } else {
                val detail = runCatching { response.body<String>() }.getOrDefault("")
                Log.w(TAG, "Subscription create failed: ${response.status} $detail")
                Result.failure(
                    IllegalStateException("Couldn't start the donation (${response.status.value}).")
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Subscription create error", e)
            Result.failure(e)
        }
    }

    /** Cancels the given subscription. Idempotent server-side: an already-cancelled
     *  or missing subscription is reported as success so the local record can clear. */
    suspend fun cancelSubscription(subscriptionId: String): Result<Unit> {
        return try {
            val response = httpClient.post("$FUNCTIONS_BASE_URL/cancel-donation-subscription") {
                header("Authorization", "Bearer $SUPABASE_ANON_KEY")
                header("apikey", SUPABASE_ANON_KEY)
                contentType(ContentType.Application.Json)
                setBody(CancelDonationRequest(subscriptionId))
            }
            if (response.status.isSuccess()) {
                Result.success(Unit)
            } else {
                val detail = runCatching { response.body<String>() }.getOrDefault("")
                Log.w(TAG, "Subscription cancel failed: ${response.status} $detail")
                Result.failure(
                    IllegalStateException("Couldn't cancel the donation (${response.status.value}).")
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Subscription cancel error", e)
            Result.failure(e)
        }
    }

    private companion object {
        const val TAG = "DonationBackend"

        // Matches the project configured in SupabaseAuthManager. Kept in sync
        // deliberately — both point at the same Supabase project.
        const val SUPABASE_URL = "https://lvzorvfhhopillzlwgau.supabase.co"
        const val FUNCTIONS_BASE_URL = "$SUPABASE_URL/functions/v1"
        const val SUPABASE_ANON_KEY =
            "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6Imx2em9ydmZoaG9waWxsemx3Z2F1Iiwicm9sZSI6ImFub24iLCJpYXQiOjE3NzQzNTc0NDQsImV4cCI6MjA4OTkzMzQ0NH0.Y_TN9r19WS96HyVZSQeNa0TyOqyBGuqFARaj8-7Ylow"
    }
}
