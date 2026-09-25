# Promotional screenshot capture

The **Promotional screenshots** GitHub Action installs a published Tryptify APK
on an accelerated Android 15 emulator and navigates its real UI. It does not
rebuild or modify the application, require signing keys, or sign into accounts.

## Run

After this workflow is on the repository's default branch:

1. Open **Actions → Promotional screenshots → Run workflow**.
2. Leave `release_tag` at `latest`, or enter an existing release tag such as `1.9.0`.
3. Leave `strict` enabled to fail the job if any planned destination is missed.
4. Download **tryptify-promo-<run number>** from the run's Artifacts section.
5. Extract the ZIP and open `index.html`. Review `coverage.md` before choosing
   images for promotional layouts.

A push changing the workflow or capture scripts on `tryptz/promo-screenshots`
also starts a validation run, including before the workflow is merged.

## Output

- `screenshots/`: original 1080 × 2400 PNGs and matching accessibility XML.
- `index.html`: local, responsive image gallery.
- `coverage.md` / `coverage.json`: attempted screens, failures and scroll limits.
- `diagnostics/`: failure images, UI hierarchies and logcat.
- `build.json`: captured release, APK SHA-256, capture-script commit and device.
- `demo-media/`: generated, clearly labeled audio fixtures.
- `tryptify.apk`: the exact APK used by the run.

Artifacts upload even after capture failures and remain available for 30 days.
They are not posted publicly as marketing material by this workflow.

## Coverage

`capture.py:inventory()` declares the destinations. The initial inventory covers
Home, Discover, World radio, library pages and local categories, account and
search, all ten settings tabs, Precision AutoEQ, Parametric EQ, compressor,
inflator, Atmos configuration, four Visual Studio tabs, Now Playing, the mixer,
audio tools and the output picker. Long settings pages capture up to eight
additional viewports; Visual Studio captures up to four. A report flag means the
scroll bound was reached, not that all content was captured.

Every target starts from a fresh launch with existing demo data. The runner uses
English text/accessibility labels, verifies tab selection, and refuses to label
screenshots from another foreground package as Tryptify. Selectors can need
updates for older/newer releases. A failed target records diagnostics and the
runner continues with the remaining inventory. With `strict=false`, missed
targets remain visible in the report but do not fail the capture step.

This is an initial inventory, **not exhaustive coverage of every state**.
Catalog album/artist/playlist details, genre charts and shelf details, every DSP
insert editor, onboarding steps, arbitrary dialogs, signed-in statistics and
physical DAC/Bluetooth states need additional fixtures or device captures.
Likewise, capturing visualizer settings does not demonstrate live visualization.
Generated tones carry tags but no album artwork or lyrics. Use your own cleared
promotional media if you need those populated. Review loading/offline/empty
states and native graphics rendering before publishing any image.

## Maintenance

Add destinations to `inventory()` using existing public UI labels. `click`
searches down a page, `tab` searches the horizontal tab strip and verifies its
selected semantics, and `expect` waits for a destination label. Avoid steps that
change account data or press purchase/share/delete buttons.

Local checks:

```sh
python -m unittest discover -s tools/promo -p 'test_*.py'
python -m py_compile tools/promo/capture.py tools/promo/prepare.py
bash -n tools/promo/capture.sh
```

To reproduce capture with a connected disposable emulator, install
`requirements.txt`, run `prepare.py` with `GITHUB_REPOSITORY=tryptz/Tryptify` and an
authenticated `gh` CLI, then run `bash tools/promo/capture.sh`.
The setup changes emulator display settings and installs the release APK; do not
point it at your everyday phone.
