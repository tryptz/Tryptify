# Tryptify website

The public site for the Tryptify app and Tryptify Cloud: what they are, the
AutoEQ ranking (SeapEngine's Rankings tab, embedded from seapengine.lol), the
Cloud's plans and prices, Terms, Privacy, Refunds and Contact. Creem (the
payment provider) reviews it before the store can take live payments.

Plain static files in `public/`, served by Cloudflare (Workers static assets,
no code), at https://tryptify.com (and www). Support mail to
support@tryptify.com is forwarded by Cloudflare Email Routing.

```
cd site
CLOUDFLARE_API_TOKEN=... npx wrangler deploy
```

Keep the prices, the trial and the grace periods in step with
`server/cloud/src/rules.ts`; the app reads them from the server, this page does
not. All text is Outfit, one of the app's fonts, a variable Latin WOFF2 for
every weight under `public/fonts/`, with its licence in
`public/fonts/LICENSES.txt`.

## The home page's screens and numbers

The screenshots in `public/img/` are WebP copies (540 px wide for a phone
screen, 1000 px for a wide one) of the app's own pictures in
`docs/screenshots/` and `docs/releases/`, except the listening party's two
(`party-app.webp`, `party-web.webp`), which the owner took, with the status
bar and a screen recorder's button removed, and the three themes-and-fonts
screens (`typography`, `theme-store`, `share-my-look`), also the owner's. The
music links clip in `public/video/` is the owner's screen recording, silent, as WebM
(VP9, played first) and MP4 (H.264, for Safari) with a poster frame; the same
MP4 is in `docs/releases/1.9.4/`. The Trypt Mania clip (`trypt-mania-play`)
is the first 20 seconds of the owner's recording, made the same way. Trypt Mania multiplayer has no
screenshot yet, so it is a drawing (`.diagram.multi`) with made-up players;
swap in a real room when there is one. The numbers and the signal path
repeat the app, so check them when it changes:

- 36 mixer plugins in six groups: the named entries of `SnapinType.kt`.
- 9,795 visualizer presets: `app/src/main/projectm-assets/presets`, all of
  them passing the crash scan (`KnownCrashPresets.kt` is empty).
- 9,951 headphones: `public/data/headphones.json`, the AutoEQ browser's list.
- 8 languages: the columns of `app/l10n/strings.tsv`.
- The signal path's order (decode, mixer, AutoEQ and parametric EQ, speed
  and pitch, out): the processor list in `PlaybackService.kt`.

## Motion, glass and colours

`js/site.js` animates the pages with Motion (motion.dev, MIT), served from
`js/motion-12.23.12.js` with its licence beside it: the hero's live spectrum,
words arriving one by one, sections sliding in, screenshots dealt and tilted,
the pricing switch counting, springy buttons. All of it is decoration: with
"reduce motion" on, without JavaScript, or if Motion fails to load, the pages
are the same, standing still.

The menu is liquid glass after the app's tab bar. Where Chromium allows
`backdrop-filter: url()` (`.lens`), each piece of glass gets its own SVG lens:
a displacement map drawn for its size and rounded outline, still in the
middle and bending harder toward the rim, with red, green and blue bent by
slightly different amounts. Elsewhere it is the same glass without the bend.
Over it: a rim lit at two corners, a soft shade inside the bottom edge, a
sheen, a glint under the pointer, and a glass bubble that springs to the
current page or the hovered link, flattening a little as it slides. On phones
the menu leaves the header for a floating tab bar at the bottom.

There are two colour designs, Black (`dark`) and White (`white`), set as
`<html data-design>` by each page's head script: the one last picked with the
sun/moon button at the end of the nav bar (`localStorage` key
`tryptify-theme`), else the device's own light or dark setting, which the page
follows live until the button is used.

## Pages with code

- `/account` (`js/account.js`): Your Cloud, the web app. Signs in with the
  same Supabase account as the app (`js/vendor/supabase-2.117.3.js`) and uses
  the cloud server's `/v1` API like the app does: plan, checkout, billing
  portal, uploads in the server's parts (tags and covers read in the browser
  by `js/vendor/jsmediatags-3.9.7.min.js`), the library, and the devices
  signed in to the Subsonic API. The library plays in the page
  (`js/player.js`): each song comes from the cloud's storage through a
  six-hour signed link (`/v1/songs/:id/play`), never relayed or transcoded,
  so a play costs nothing but the request. A queue from the list, shuffle,
  repeat, seeking, the phone's lock-screen controls (Media Session) and a
  spectrum read from the audio. A format the browser can't play (ALAC and
  AIFF in Chrome, APE, WMA) is marked "App only", or skipped if it fails. Google sign-in needs
  `https://tryptify.com/account` in Supabase's Auth › URL Configuration ›
  Redirect URLs.
- `/autoeq` (`js/autoeq.js`, `js/measures.js`, `js/graphs.js`): the AutoEQ ranking, the measures explained, and the frequency-response and EQ graphs (curves in `data/autoeq-curves.json`), from a snapshot of
  SeapEngine's bench results in `data/autoeq-bench.json` (SeapEngine sends no
  CORS header, so it cannot be read live). Refresh it from
  `https://seapengine.lol/rankings/bench.json` keeping only the metrics.
- `/download`: the APK lives in the R2 bucket `tryptify-downloads` (too big
  for a static file) and `src/worker.js` serves it at
  `/download/tryptify.apk`, with ranges. Publish a new one with
  `wrangler r2 object put tryptify-downloads/tryptify.apk --remote --file … --content-type application/vnd.android.package-archive --content-disposition 'attachment; filename="Tryptify-<version>.apk"'`
  and update `data/apk.json` (version, size, SHA-256). The public APK is a
  normal release build, `./gradlew :app:assembleRelease`, which leaves the
  chart server out by default (never pass `-Ptryptmania.withCpuServer=true`
  for it) and keeps the rooms server when its key is set. Check it with the
  BuildConfig greps in AGENTS.md before upload: the chart server's must print
  `1`.

## The AutoEQ try-out

`/autoeq`'s "Try it" runs the app's AutoEQ in the browser:
`js/autoeq-engine.js` is a port of `AutoEqEngine.kt`, `EqDspKernels.kt`, the
measurement parser and `AutoPreamp.peakBoostDb`. `WebAutoEqFixtureTest` (app
unit tests) holds the Kotlin engine to `tests/autoeq-engine-fixtures.json`, and
`node site/tests/engine-parity.mjs` holds the port to the same answers (same
bands, gains within 0.01 dB; exact digits are impossible across the JVM and a
browser, see the script). Change the engine, rewrite the fixtures, port it.

The headphone list, `data/headphones.json`, comes from
`python3 tools/headphone_index/build.py` (AutoEq's `results/INDEX.md` and the
twelve squig.link phone books the app searches); re-run it now and then. The
targets in `data/targets/` are copies of the app's `assets/autoeq/targets/`.
