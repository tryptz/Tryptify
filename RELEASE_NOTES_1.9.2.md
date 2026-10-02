## [1.9.2]

### Added

#### A glass tab bar, and pages rebuilt around it
- **Home, Discover, Radio, Library and Search sit in one liquid-glass bar.** It is the mini player's material, built the mini player's way: a sibling of the haze source, its slab drawn solid with the glyphs punched out of it, falling back to the frosted pane wherever the glass shader cannot run.
- **Scrolling down folds the mini player into the bar; scrolling up unfolds it.** It is driven by nested scroll at the nav host, so every list drives it without knowing, and lists pad by the bar's expanded height so nothing jolts mid-scroll.
- **Home shows Recently Played and Liked Songs. Library switches sections with chips. Search is its own page.** There is still one pager over one flat list of pages; the tab bar and the Library chips both drive it.

#### Wave Candy on the cover
- **An FL Studio-style oscilloscope along the bottom quarter of the artwork**, stereo (left across the top, right across the bottom) or mono, with a neon glow or a soft shadow. The cover stays readable above it.
- **The scope is native.** It slides its window smoothly between audio chunks rather than jumping when one lands, which is what read as a low frame rate before, and scrolls on its own clock so it is smooth on every song.
- **Settings live under Settings › Equalizer.**

#### Kick punch
- **The cover jumps on every kick drum.** A kick is the low band jumping above its own recent average, so a quiet intro and a wall of hardstyle both trigger on their kicks rather than on their loudness. Strength and sensitivity are in the Wave Candy settings, and it holds still with "Disable animations" on.

#### Deezer
- **Search asks Deezer for songs, albums and artists** whenever a server in the API list serves it.
- **Deezer songs play and download in full from Deezer**, in FLAC or MP3 320.

#### Source tags everywhere
- **Every song, album and artist shows the catalog it came from**, and the player says "via" when a track plays from a different one.

#### One list of APIs
- **Add a server once and Tryptify works out which catalogs it serves.** Each service is probed with its own request, and only a real answer counts.

#### Mixer
- **Route any bus to any bus**, up to 48, with cables and send knobs. Routes that would make a loop are greyed out.
- **Atmos Upmix 9.1.6**, a preset that spreads stereo into 9.1.6 surround.

#### BPM and a turntable bend
- **Each song's tempo is detected**, and speed can be set in BPM as well as a multiplier or semitones.
- **Push the bar under the BPM to bend the tempo smoothly**, with a detent click on every whole beat per minute. Long-press the BPM to type one.

#### Crossfades on a USB DAC
- **Crossfade works while Tryptify drives your DAC directly**, across sample rate changes.

### Changed

#### The player stops spending frames on things that are not moving
- **The spectrum no longer rebuilds the player on every FFT frame.** The analyzer publishes a fresh array 15 to 60 times a second, depending on the device, and the player read it in order to pass it down, so the whole hero and the artwork inside it recomposed at that rate. The array is now read only by the overlay that draws it. The spectrum preview in Settings had the same problem and the same fix.
- **Pausing really pauses the spectrum.** The analyzer kept running its FFT on frozen audio and handing out a new, equal picture every frame. It now stops once it has settled and starts again when audio arrives, and the overlay stops redrawing once it has caught up.
- **No tilt sensor unless something uses it.** Tilt reactivity defaults to zero, yet the mini player, tab bar and search bars kept the gravity sensor running at 50 Hz on every screen and redrew their glass on each reading. The sensor now runs only while a surface actually responds to tilt. The glass looks exactly the same.
- **Glass screens open a little faster.** Checking whether the glass shader works used to compile the whole shader again for every glass surface on the screen. It is checked once per launch now.

#### Search
- **Source filters cover every result type**, the bar no longer hides them, and albums open from their own catalog.

#### Navigation
- **A fast double back no longer blanks the screen**, and local songs open their own artist and album.

#### Mixer
- **Mastering-style presets:** the master sits at 0 dB with a limiter last.
- **Tap a strip to select it, and tap again to open it.**

#### Speed panel
- **Same-shape buttons, with the values between − and +.**

#### Playback
- **Smoother crossfades:** no dropout as a fade starts, and speed and pitch carry through it.
- **TIDAL asks before Qobuz.** When TIDAL cannot play a song, you choose whether Qobuz plays it instead, rather than being switched silently.

#### Better WAVs
- **32-bit float and 8-bit WAVs play correctly**, tags and covers show, rows say "WAV 24/48", and files named "Artist ~ Title" fill in the artist.

### Removed

#### The Nightcore button
- **Use 1.10x with Preserve pitch off** for the same sound.

#### The catalog picker and per-catalog URL fields
- **Replaced by the API list**, which detects what each server serves.
