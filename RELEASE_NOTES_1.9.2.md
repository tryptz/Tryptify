## [1.9.2]

### Added

- **Glass tab bar.** Home, Discover, Radio, Library and Search in one liquid-glass bar. Scrolling down folds the mini player into it; scrolling up unfolds it.
- **New Home, Library and Search.** Home shows Recently Played and Liked Songs, Library switches sections with chips, and Search is its own page.
- **Wave Candy on the cover.** An FL Studio-style oscilloscope along the bottom of the art: stereo or mono, neon or shadow. Settings › Equalizer.
- **Kick punch.** The cover jumps on every kick drum. Strength and sensitivity are in the Wave Candy settings.
- **Deezer.** Search it for songs, albums and artists, and play and download in full from Deezer in FLAC or MP3 320.
- **Source tags everywhere.** Every song, album and artist shows its catalog, and the player says "via" when it plays from another.
- **One list of APIs.** Add a server once and Tryptify finds out which catalogs it serves.
- **Mixer routing.** Route any bus to any bus, up to 48, with cables and send knobs. Loops are greyed out.
- **Atmos Upmix 9.1.6.** A preset that spreads stereo into 9.1.6 surround.
- **BPM.** Each song's tempo is detected; set speed in BPM, multiplier or semitones. Push the bar under the BPM to bend the tempo with a click per beat, or long-press to type one.
- **Crossfades on a USB DAC**, across sample rates.

### Changed

- **Lighter player.** The spectrum no longer rebuilds the player on every FFT frame, it stops when playback pauses, and glass no longer keeps the tilt sensor running when nothing uses it. Glass screens also open a little faster.
- **Cleaner search.** Source filters cover every result type, the bar no longer hides them, and albums open from their own catalog.
- **Steadier navigation.** A fast double back no longer blanks the screen, and local songs open their own artist and album.
- **Mastering-style mixer presets.** Master at 0 dB with a limiter last. Tap a strip to select it, again to open it.
- **Clearer speed panel.** Same-shape buttons, with values between − and +.
- **Smoother crossfades.** No dropout as a fade starts, and speed and pitch carry through.
- **TIDAL asks before Qobuz.** When TIDAL can't play a song, you choose whether Qobuz does.
- **Better WAVs.** Float and 8-bit WAVs play right, tags and covers show, rows say "WAV 24/48", and "Artist ~ Title" names fill the artist.

### Removed

- **Nightcore button.** Use 1.10x with Preserve pitch off.
- **Catalog picker and URL fields.** Replaced by the API list.
