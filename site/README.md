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
are the same, standing still. Motion is switched on by `js/theme-boot.js`,
which adds `.motion` to `<html>` unless "reduce motion" is set; `site.js` does
nothing without it. (Moving the head script into that file once dropped this
line, and every page stood still until it was put back.) The hero's words are
split with `Intl.Segmenter` in the page's language, since Chinese and Japanese
put no spaces between words.

The menu is liquid glass after the app's tab bar. Where Chromium allows
`backdrop-filter: url()` (`.lens`), each piece of glass gets its own SVG lens:
a displacement map drawn for its size and rounded outline, still in the
middle and bending harder toward the rim, with red, green and blue bent by
slightly different amounts. Elsewhere it is the same glass without the bend.
Over it: a rim lit at two corners, a soft shade inside the bottom edge, a
sheen, a glint under the pointer, and a glass bubble that springs to the
current page or the hovered link. Its two ends are separate springs, the one in
front quicker, so it stretches toward the link and settles onto it; its height
and round ends never change, so the glass keeps its shape. A tap on a link
leaves where the bubble stood (`sessionStorage` key `tryptify-pill`), and the
next page slides it on from there. On phones the menu leaves the header for a
floating tab bar at the bottom, which rises in on arrival but not again on the
pages it leads to. Its tabs are as wide as their words, so a long one
(Reproductor, プレーヤー) takes room from short ones. Between phone and desktop
(561 to 900 px) the tabs tighten, below 700 px the wordmark gives way and
below 620 px What's new, so the menu stays on one line in every language.
Coming back to a page the browser kept (Back), the pill is put back on that
page's tab.

The glass itself (the lens, and the glint that follows the pointer) is
`js/glass.js`, shared with the web player. Its bend map is drawn in the
glass's own pixels; sized as a percentage, it took the filter's larger area
and the rim smeared colour across the middle of the bar.

The web player's bar is the menu's glass (`.glass` in `styles.css`, with its
lens and its glint from `js/glass.js`): the page lightly blurred and bent at
the rim, a little of the page colour under it, a rim lit at two corners, a
sheen and a glint that follows the pointer. Only its shape is its own: it
floats 12 px from the edges (8 on a phone) with 26 px corners. The cover's
vibrant colour (`TryptifyGlass.vibrant`, the same pick the full-screen player
makes) is on the play button and the timeline, not on the glass. Each lens is
drawn again when the design changes. Its icons are glyph holes, as in the
app's mini player (`punchHoles` in `js/wp-app.js`): the tint is a layer of
its own with each icon's shape masked out, and through the holes the frosted
page shows, lit in the icon's colour (the green of shuffle or repeat when on);
the play disc keeps its drawn icon. The holes are drawn again when an icon
changes, the bar is resized, or the design switches (after its 0.35 s fade).
The strip for a song playing on another device (Tryptify Connect) is the same
glass, floating over the bar, with Connect's green kept for its phone, its
progress line and Play here.

The player takes its colours from the playing song's cover, as the app's
Dynamic Colors do (`paintCover`): the cover's vibrant colour is the accent,
moved toward white on Black or black on White just until text in it reads at
4.5:1, a quieter one for links, and its dominant colour washes the top of the
page (`TryptifyGlass.palette`). Backgrounds and text stay the design's; with no
cover, or one that cannot be read, the design's own colours. The colours are
registered with `@property`, so a new song's fade in. The play button
is the same glass in a disc and squeezes to 92% on a spring. The timeline is
the app's glass tube: one outline that swells around the playhead (a raised
cosine, 44 px long), tinted up to it, swelling more while dragged; the range
input stays on top, unseen, its thumb as wide as the swelling so its travel
matches the drawing. The volume is the same tube, smaller (a 26 px
swelling), hidden on a phone as the slider was. `--bar-h` on the player is the room kept under the
pages, the bar's measured height plus its gap. The player loads no
`site.js`, so `theme-boot.js` leaves `.motion` off there.

There are two colour designs, Black (`dark`) and White (`white`), set as
`<html data-design>` by each page's head script: the one last picked with the
sun/moon button at the end of the nav bar (`localStorage` key
`tryptify-theme`), else the device's own light or dark setting, which the page
follows live until the button is used.

## The changelog

`/changelog` is every release's notes from `docs/releases/<version>.md`, newest
first, with a version list to jump between them and a label on each New,
Changed, Removed and Fixed line. It is built, not written:

```
python3 tools/site_changelog/build.py
```

That rewrites `public/changelog.html` (header and footer from `refunds.html`)
and moves the notes' pictures to the site: a JPG or PNG becomes a WebP under
`public/img/releases/<version>/`, a GIF a silent looping MP4 with a WebP poster
under `public/video/releases/<version>/` (served with ranges by `src/worker.js`,
like the other clips). A note's pictures can be addressed as GitHub reads
them, relatively (`1.9.5/connect-app.jpg`) or by their raw.githubusercontent
address. Run it after changing a note, and commit what it writes. The menu's
What's new link is hidden on the narrowest phones (380 px and under) and
between 561 and 620 px; the footer and the Download page link it there.

## Languages

The site is in the app's eight languages: English at `/`, and Simplified
Chinese, Japanese, French, Spanish, Turkish, German and Italian under `/zh`,
`/ja`, `/fr`, `/es`, `/tr`, `/de` and `/it`. The English pages are the source;
the others are built from them and never edited by hand:

```
python3 tools/site_l10n/site_l10n.py extract   # English text to site/l10n/source.json and source-js.json
python3 tools/site_l10n/site_l10n.py check     # every language complete, markup and {blanks} intact
python3 tools/site_l10n/site_l10n.py build     # writes public/<lang>/*.html and public/js/l10n/<lang>.js
```

- **Pages:** `extract` takes each page's text (headings, paragraphs, links,
  `alt`, `title`, `aria-label`, `placeholder`, the pricing switch's
  `data-monthly`/`data-yearly`, the description and Open Graph text) into
  `site/l10n/source.json`, keyed by a hash of the English. Each language is
  `site/l10n/<lang>.json` with the same keys; inline tags (`<strong>`, `<a>`)
  stay in the translation, and `check` fails when they differ.
- **Scripts:** text the scripts write is wrapped in `T("…")` (defined in
  `js/theme-boot.js`), with `{name}` blanks for anything filled in. `extract`
  collects every `T()` call's string into `source-js.json`, translated in
  `site/l10n/js-<lang>.json`, and `build` turns those into
  `public/js/l10n/<lang>.js`, loaded ahead of the scripts on that language's
  pages. A string passed to `T()` must be a plain literal, a whole sentence;
  a count that can be 1 gets a pair of strings.
- **Left in English:** the changelog's release notes (`translate="no"`),
  brand names, and the 404 page.
- **Picking a language:** the footer lists all eight (in the player, the
  sidebar's foot; not on a phone). The first visit to an English page goes to
  the browser's language when the site has it; picking one from the list is
  kept (`localStorage` key `tryptify-lang`), English included. A sign-in
  coming back (`?code=`) is never moved. Each page links its other languages
  with `hreflang`.
- **Legal pages:** Terms, Privacy and Refunds say in each translation that the
  English version applies if the two differ (`data-l10n-only`).
- **Words:** the app's own (`app/l10n/strings.tsv`, `GLOSSARY.md`) for every
  setting, button and screen the site names, so the site and the app agree.
  Prices stay in US dollars as written; counts are written the language's way.

After changing an English page or a script's text: `extract`, translate the
new keys in all seven languages, `check`, `build`, and commit the lot. The
generated folders are committed, like `changelog.html`; run
`tools/site_changelog/build.py` before `build`, since the changelog is a source
page too.

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
- `/autoeq` (`js/autoeq.js`, `js/measures.js`, `js/graphs.js`), linked only from the footer (AutoEQ ranking), not the menu or the home page: the AutoEQ ranking, the measures explained, and the frequency-response and EQ graphs (curves in `data/autoeq-curves.json`), from a snapshot of
  SeapEngine's bench results in `data/autoeq-bench.json` (SeapEngine sends no
  CORS header, so it cannot be read live). Refresh it from
  `https://seapengine.lol/rankings/bench.json` keeping only the metrics.
- `/download`: the APK lives in the R2 bucket `tryptify-downloads` (too big
  for a static file) and `src/worker.js` serves it at
  `/download/tryptify.apk`, with ranges. Publish a new one with
  `wrangler r2 object put tryptify-downloads/tryptify.apk --remote --file … --content-type application/vnd.android.package-archive --content-disposition 'attachment; filename="Tryptify-<version>.apk"'`
  and update `data/apk.json` (version, size, SHA-256). The public APK is a
  normal release build, `./gradlew :app:assembleRelease`: the chart server
  is never built in, and the rooms server is when its key is set. Check it
  with the BuildConfig greps in AGENTS.md before upload.

- `/party` (`party.html`, `party.css`, `js/party.js`), linked only from the
  footer: the listening party for guests, moved here from the rooms server's
  own page. `/party` asks for a code; an invite, `/party/<code>` (and
  `/<lang>/party/<code>`), is served `party.html` by `src/worker.js` with the
  address kept, and the script reads the code from it (or `?code=`). It talks
  to the rooms server at `https://rooms.tryptify.com` (`wss://…/ws`, in the
  policy's `connect-src`; its HTTP routes send CORS for tryptify.com and take
  the song token as `?token=`). Served from `wrangler dev`, `?rooms=<url>`
  points it at a test server. Songs are downloaded whole into the tab's
  memory and played at the party's moment and speed, as the app plays them.

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

## The web player

`/player` (`player.html`, `player.css`, `js/wp-engine.js`, `js/wp-sources.js`,
`js/wp-app.js`) plays music in the browser through the same AutoEQ as the
app: a dashboard (now playing, AutoEQ, the signal path, sources, playback,
up next), a menu on the left and the bar at the bottom.

- **The sound** (`wp-engine.js`): two `<audio>` elements take turns, so the
  next song loads early (Gapless) and starts as this one ends, into one Web
  Audio chain: preamp, the AutoEQ bands as IIR filters made from the engine's
  own matched-Z coefficients (`TryptifyAutoEq.coeffs`) at the context's rate,
  mono, volume. Speed (0.25–4×) and keeping the pitch are the elements' own.
  Nothing runs on a timer and the context asks for playback-sized buffers, so
  a paused or quiet tab costs next to nothing. Every source sends CORS (the
  elements are `crossOrigin="anonymous"`), or Web Audio would hear silence.
- **The music** (`wp-sources.js`), each turned into the same queue track:
  - Files: a folder read in the browser, never uploaded (File System Access;
    Chrome and Edge keep it between visits, in IndexedDB; other browsers pick
    it each visit). Folders are the library; tags and covers are read with
    jsmediatags for the song playing and the next one only.
  - Tryptify Cloud: `/v1/songs` and its signed play links, with the Supabase
    session `/account` signs in.
  - Liked songs and playlists: the account's `favorite_tracks`,
    `user_playlists` and `playlist_tracks` in Supabase, as the app syncs them
    (RLS keeps each row its owner's). Their songs are TIDAL ids, so they play
    through Trypt HiFi.
  - Trypt HiFi: the listener's TrypT-hifi server, by its `/api/tidal` routes
    (the ones that send CORS): `get-music`, `get-album`, and `stream-file` for
    the audio. Its root HiFi API routes send no CORS, so they are not used.
    It is locked until its API URL is in: until then nothing names it (no
    menu entry, search, pill or button; `#/hifi` opens the dashboard), only
    "Add an API URL" under Sources. A URL is kept only once it answers, and
    Remove locks it again.
    A server with a password (trypt-hifi's `ACCESS_PASSWORD`) gets it from
    the Password box beside the URL: `X-Access-Key` on the fetches, and
    `access_key=` in the stream link, since `<audio>` sends no headers. It is
    kept in this browser only (`tryptify-player-hifi-key`), never sent to
    Connect, and Remove forgets it with the URL.
  - Music server: Navidrome or any Subsonic server, by `/rest/*.view` with
    `f=json`. Like the app's music server, a token and salt are kept (in
    localStorage), never the password, unless the server refuses tokens
    (error 41/42), and then the password's hex. Songs stream with
    `format=raw`, so lossless stays lossless. The server must send CORS for
    tryptify.com, or the browser cannot read it.
- **AutoEQ**: the view is `/autoeq`'s try-out, the same markup and
  `js/tryout.js`, with the band count, top frequency and method as the
  ranking's chips. Each fit fires `tryptify:fit`; the player plays it and
  keeps it, and `TryptifyTryout.restore` fits the same headphone again when
  the view next opens. Settings are kept in `tryptify-player-*` keys.

- **Tryptify Connect** (`wp-connect.js`, server in
  [`server/connect`](../server/connect/README.md)): signed in, this browser is
  one of the listener's devices. A strip over the bar and a tile on the
  dashboard show what plays on another device, with its controls and **Play
  here**; the tile and the Devices button in the bar list them and moves the music to any of them, at the same second
  and with the queue. When another device starts playing, this one pauses.
  While this browser plays nothing (or is paused) and another device plays,
  the player bar follows it, as Spotify's does: its song, "on …", its second
  moving in step (worked out from that device's last word and the server's
  clock), a drag on the bar seeks that device and the bar's buttons steer it.
  The strip shows the same elapsed and total time.
  One WebSocket, a message only on change, and no connection while the tab is
  hidden with nothing playing.

- **The full-screen player** (`wp-full.js`): the bar's expand button or its
  cover opens it over the page, under the bar, which keeps its controls. It
  shows what the bar shows, this browser's song or the one another device
  plays while the bar follows it. Two views:
  - **Lyrics**: the cover, large, on its own art blurred, and the lyrics from
    LRCLIB (`/api/get` by title, artist, album and length, then `/api/search`
    within 8 s of the length). Timed lyrics light the line being sung, scroll
    it to a third of the way down (held for 4 s after a scroll by hand), and a
    tap on a line jumps there. Untimed ones are shown as they are.
  - **Waterfall**: the app's spectrum waterfall at its defaults (Lines), from
    an analyser on the music before the EQ and volume (`TryptifyEngine.analyser`):
    a log axis from 20 Hz, +4 dB an octave around 1 kHz, centred on the
    200 Hz–2 kHz mean, the app's smoothing, a line every 2.5/48 s, 48 lines
    and the live one in perspective, full strength for 0.75 s then fading,
    in the cover's own colour (or the app's #7EB6FF when it cannot be read)
    lightening toward the top. It needs the sound, so it draws only what
    plays in this browser.
  Fill the screen uses the Fullscreen API; Esc or ✕ closes it. Nothing runs
  while it is shut or the tab is hidden.

`node site/tests/webplayer.mjs` checks the pure parts: file names into a
library, the sources' rows into tracks, cover ids, addresses, MD5, and that
the coefficients the player hands to its filters give the engine's curve.

## Security headers

`public/_headers` gives every page a Content-Security-Policy (only the
site's own scripts run; no inline script, so the theme picker is
`js/theme-boot.js`), no framing, `nosniff`, HSTS and a Permissions-Policy.
A new script, font or picture from another site has to be added to the policy
there, or the browser refuses it. Cloudflare adds two scripts of its own to
every page, both allowed: Web Analytics' beacon (`static.cloudflareinsights.com`,
reporting to `/cdn-cgi/rum`) and the bot check, an inline script. That one is
let through by a nonce: `src/worker.js` runs for every page (not for scripts,
styles, pictures or fonts, see `run_worker_first` in `wrangler.toml`) and puts
a fresh nonce in that page's copy of the policy, and Cloudflare marks its
script with the nonce it finds there. After changing either, serve the site with
`npx wrangler dev` and open every page with the browser console open: a
refused resource is logged as a Content Security Policy error.
