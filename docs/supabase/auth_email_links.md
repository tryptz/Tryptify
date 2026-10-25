# Email links and sending

## Sending

Supabase's built-in mailer sends a couple of emails an hour for the whole
project; past that, sign-up fails with `over_email_send_rate_limit`. Since
2026-10-24 the project sends through Resend (Authentication › Emails › SMTP
Settings): host `smtp.resend.com`, port 465, user `resend`, the password a
Resend API key, sending as `noreply@tryptify.com` (domain verified in Resend
by DNS records in Cloudflare). The limit is set under Authentication › Rate
Limits.

What a broken setup looks like in the auth logs, on `/signup`:

- `535 "Authentication credentials invalid"`: the user or the key is wrong.
- `550 "The tryptify.com domain is not verified"`: Resend's domain check.

The app shows both, and the rate limit, as `auth_email_send_failed`.

## The link

Keep the Confirm signup template's link as `{{ .ConfirmationURL }}`. It goes
through Supabase's `/verify`, which redirects to `login-callback?code=…`, and
the app takes only that (`SupabaseAuthManager.handleDeepLink` is PKCE only, on
purpose: a link carrying tokens could sign the phone into another account).
The code works only on the phone where the sign-up started. A failed link
comes back with `error_code` and shows `auth_link_expired`.

A `token_hash` page on tryptify.com that handed the app a session in the
fragment was tried and dropped: Chrome strips the fragment when it opens the
app, and the app would refuse it anyway.
