# Tryptify: instruction manual

A short guide to what Tryptify does and where each thing is. It is written to
be read by a person or by a tool acting for one: every setting is given
by its path, as `Settings › Tab › Row`, in the app's English names.

Sources: the README's feature list, the settings search index
(`ui/settings/SettingsSearchIndex.kt`) and `CHANGELOG.md`. Where this manual
and the app disagree, the app is right; fix the manual. It gives where a
thing is, not every option inside it: for those, open the row.

## 1. What it is

A music player for Android that plays every source through one audio chain:
ReplayGain, then headphone AutoEQ, then a mixing console, then the phone's
audio or a USB DAC written to directly.

## 2. Getting around

The bar at the bottom has five places:

| Tab | What is there |
| --- | --- |
| Home | What to play next |
| Discover | New music, and a map of genres |
| Radio | Stations made from what you play |
| Library | Your songs, albums, artists, playlists and downloads, and the library galaxy (artists as stars, albums as planets, tracks as moons) |
| Search | Every source at once |

The mini player sits above the bar. Tap it for the full player; from there:
lyrics, the queue, Timer, Sound, Speed, Mixer, Visualizer and Waveform.

Settings has ten tabs: Appearance, Fonts, Audio, Equalizer, Library,
Downloads, Connections, Radio, System, About. The search at the top of
Settings finds any row by name or by what it does ("bass", "battery",
"cache").

## 3. Music sources

| Source | How to add it |
| --- | --- |
| Files on the phone (FLAC, ALAC, WAV, MP3, AAC, Opus) | `Settings › Library › Local folders` and `Music folders` |
| TIDAL, Qobuz, Deezer | A TrypT HiFi server you add under `Settings › Connections` (Add API) |
| Navidrome or another Subsonic server | `Settings › Connections › Music server (Subsonic)` |
| Tryptify Cloud (paid storage) | `Settings › Connections › Tryptify Cloud` |
| YouTube | Built in, no sign-in |
| SoundCloud | Built in, with a Tryptify account |

Playlists come in through `Settings › Library › Playlist import`.

## 4. Playback

| To do this | Go to |
| --- | --- |
| No gap between tracks | `Settings › Audio › Gapless playback` |
| Fade one track into the next | `Settings › Audio › Crossfade` |
| Even loudness | `Settings › Audio › ReplayGain` |
| Speed, pitch or tempo (0.25× to 3.0×) | `Settings › Audio › Playback speed`, or Speed in the player |
| Keep pitch when the speed changes | `Settings › Audio › Preserve Pitch` |
| Streaming quality | `Settings › Audio › Streaming quality` |
| Play at each file's own sample rate | `Settings › Audio › Never Resample Between Tracks` |
| Speakers-in-front sound on headphones | Crossfeed, held in the player's Audio tools (or found by Settings search): Off, Crossfeed, or Room (the Binaural Room on its own, with its own HRTF, an LFE that keeps the bass out of the room, and a 2D or 3D view) |

A USB DAC is driven bit-perfect by the app's own driver when one is plugged in
(`Settings › Audio › Exclusive USB DAC (bypass Android audio)`). Under that
switch:

| To do this | Use |
| --- | --- |
| See how many channels the DAC has | Channels. A 5.1, 7.1 or Atmos speaker mix stays surround on a multichannel interface or an AVR; a song with fewer channels plays in the first ones, untouched. DSD files play as PCM |
| Hand the DAC to other apps and take it back | Quick Settings tile: a tap adds an Exclusive USB DAC tile to Quick Settings |

## 5. Sound shaping

| Tool | Where | What it does |
| --- | --- | --- |
| AutoEQ | `Settings › Equalizer › AutoEQ headphone profile` | Corrects your headphones from 4,000+ measurements against 10 targets |
| AutoEQ with no connection | The download icon in the headphone list (Offline measurements) | Keeps every measurement on the phone; its dialog gives the count and the size first. A saved preset also brings its own headphone and curves back with no network |
| Equalizer | `Settings › Equalizer › Equalizer` | Bands by hand |
| Parametric EQ | `Settings › Equalizer › Parametric EQ` | Filters by frequency, gain and width |
| System-wide AutoEQ | `Settings › Equalizer › System-wide AutoEQ` | The correction for other apps too: an ear each in 2-channel mode, kept running with Tryptify closed, with a limiter that acts only when a boost would clip |
| Exact system-wide EQ | `Settings › Equalizer › Exact parametric EQ (Shizuku)`, under System-wide AutoEQ | Other apps play through the real parametric filters instead of a 31-band copy of them. Needs the Shizuku app (someone else's, free) running and allowed, and Android 13 or newer. The first time it is turned on the setup steps are shown; the Shizuku line under the switch says what is in the way, and a tap on it shows the steps again. Shizuku is started again after each restart of the phone; without it the standard system-wide EQ plays. Beta |
| System-wide AutoEQ from anywhere | `Settings › Equalizer › Quick Settings tile`, under those two | A tile that turns System-wide AutoEQ on and off over any app |
| Mixer | `Settings › Audio › Mixer`, or Mixer in the player | A console of buses and processors with loudness meters |
| Binaural Room | Mixer › FX Chain › Add Effect › Space | The Atmos page's headphone room for any song: stereo from two speakers standing in that room. Room, Binaural, Angle and Output knobs; My room swaps the built-in room for one of your own Width, Depth, Height and Damping. On the master of a surround mix or the 9.1.6 upmix it is one room for the whole mix, each channel at its own speaker |
| 9.1.6 upmix | The mixer's preset bar: the folder icon (Load Preset) › Atmos Upmix 9.1.6 | Spreads a stereo song around you, a bus for each group of speakers |
| Music links | On sliders and mixer knobs | Makes a control move with the kick, vocals, snare or hi-hats |

## 6. Dolby Atmos

All of it is on `Settings › Audio › Atmos Renderer Configuration`.

| To do this | Use |
| --- | --- |
| Hear Atmos on headphones | Binaural render (SOFA HRTF): objects play from a 15.1.12 room of virtual speakers |
| More or less room sound | Room |
| Choose the room | Rooms: Built-in room, a measured room, or My room |
| Get a measured room | Browse room database |
| Build your own room | My room › Size and shape: presets, Width, Depth, Height, Damping, Roundness |
| Keep a room you made, or pass it on | My room › Size and shape: Save preset names the room as it is set and keeps it under Saved rooms (tap one to use it, its cross deletes it). Export writes it as a small `.room.json` file; Import reads such a file, saves it and uses it. |
| Use your own ears' data | SOFA presets, Browse HRTF database, Load .sofa file (`Settings › Audio › HRTF database`). High-resolution sets (160 MB and more, such as the Aachen KEMAR) can be downloaded too: the download shows its progress, and the first time such a set is used it takes some seconds to read. With any set, each of the room's 27 speakers plays the file's own measurement for its direction. |
| Keep high sounds overhead | Trapezoid panning, with Ceiling width, Ceiling depth, Wall curve |
| Make height feel real for your own ears | Ear tuner (shown when Binaural render is on): drag the dot sideways for Ear size and up or down for the height band's Volume (up marks sounds from above more and sounds at ear level less, so it is heard in any song), pull the white handle on the ring for Q, and move Height band for its pitch. The graph shows the result; it is heard at once. Reset puts the head back as it is. |
| Fold surround to stereo | Downmix multichannel to stereo (`Settings › Audio`), with Downmix preamp and LFE low-pass under it |
| Atmos mixes from TIDAL | TIDAL Dolby Atmos (`Settings › Audio › TIDAL Dolby Atmos`) |
| Test it | Add Atmos test track to library |
| Learn what the page does | The ? in the top bar: each part of the page in a few plain lines, and a way to start |

The 3D room at the top of that page shows the speakers lighting as they play,
the mix's objects, and the sound as grain in the air. Drag sideways to turn
it. Show sound in the room turns the grain off. Scrolled past, a picture of
the room stays at the top; tap it to go back.

## 7. Looks

| To change | Go to |
| --- | --- |
| Theme, light paper, colours from the cover | `Settings › Appearance › Theme`, `Light paper`, `Dynamic colours`, `Custom colors` |
| The glow behind the cover | `Settings › Appearance › Glow behind album art`, `Glow radius`, `Glow brightness` |
| Fonts and text sizes | `Settings › Fonts › Typography`, `Font size`, `Letter width`, `Custom font`, `Use system font size` |
| The player's glass and the lyrics' effects | Player Visuals Studio (search Settings for it) |
| Lyrics starting from the left, or softer lyrics | Player Visuals Studio › Lyrics FX › Typography: Line start (Middle or From the left) and Soft focus |
| An old-TV look | `Settings › Appearance › CRT effect` |
| A whole look made by someone else | Theme Store |
| A plainer, lighter app | `Settings › Appearance › Legacy player`, `Remove liquid glass`, `Disable animations` |
| Badges and lyric script | `Settings › Appearance › Show Explicit Badges`, `Song row badges`, `Romaji Lyrics` |
| The bottom bar and library layout | `Settings › Library › Nav bar`, `Hide mini player when the nav bar shows`, `Library sections` |

## 8. Visuals

| Visual | Where |
| --- | --- |
| Spectrum waterfall, six styles | `Settings › Equalizer › Spectrum analyzer` |
| Wave Candy (scopes over the player) | `Settings › Equalizer › Wave Candy` |
| MilkDrop visualizer, 9,795 presets | Visualizer in the player |
| Home screen widget, three sizes | `Settings › Appearance › Home screen widget` |

## 9. Downloads

`Settings › Downloads`: Download quality, Download lyrics, Auto-download
liked, Save location, and the Download centre for what is in progress.

## 10. With other people and devices

| Feature | What it does |
| --- | --- |
| Tryptify Connect | Moves playback between your phone and tryptify.com at the same second, or steers one from the other |
| Listening parties | The same moment of the same song with friends, with a shared queue, chat and vote skip |
| Share links | A tryptify.com link to a song or album that plays in anyone's Tryptify. A file on the phone can be shared too when it is a song from TIDAL, Qobuz or Deezer: it is found by its ISRC tag, or by its name and length. |
| Last.fm, ListenBrainz | `Settings › Connections › Last.fm scrobbling`, `ListenBrainz` |
| Discord | `Settings › Connections › Show what I'm playing` |
| Android Auto | Works when the phone is connected to the car |

## 11. Radio

`Settings › Radio › AI radio` and `Radio weights` set how stations choose
what comes next.

## 12. Trypt Mania

A rhythm game on your own songs, charted for you, with packs, multiplayer
rooms and a live scoreboard. Add StepMania packs under
`Settings › Library › Add StepMania packs`. Measure your Bluetooth
headphones' delay once and the game, lyrics and visuals all wait for it.

## 13. Upkeep and trouble

| To do this | Go to |
| --- | --- |
| Make a slow phone smoother | `Settings › System › Performance`, `Frame rate limit` |
| See the frame rate | `Settings › System › FPS counter` |
| Read or export the app's log | `Settings › System › Debug log` |
| Send the log once, with a note | `Settings › System › Send logs` (sign-in needed; the dialog says what leaves the phone) |
| Draw less for a cooler, smoother phone | `Settings › System › Performance › Rendering quality`: High, Balanced or Fast |
| Send the log live to someone helping you | Debug log › Live log (30 minutes, sign-in needed, off at every start) |
| Keep crash reports | `Settings › System › Save crash reports` |
| Move to a new phone | `Settings › System › Backup and restore` |
| Free space | `Settings › System › Clear cache` |
| Update | `Settings › System › Check for updates` |
| See what changed | `Settings › About › What's new`, `Version` |
| Run the first-start guide again | `Settings › System › Restart onboarding` |

## 14. Languages

English, 简体中文, 日本語, Français, Español, Türkçe, Deutsch, Italiano.
