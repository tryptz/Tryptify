# Tryptify: instruction manual

A short guide to what Tryptify does and where each thing is. It is written to
be read by a person or by an assistant acting for one: every setting is given
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
| Speakers-in-front sound on headphones | `Settings › Audio › Crossfeed` |

A USB DAC is driven bit-perfect by the app's own driver when one is plugged in.

## 5. Sound shaping

| Tool | Where | What it does |
| --- | --- | --- |
| AutoEQ | `Settings › Equalizer › AutoEQ headphone profile` | Corrects your headphones from 4,000+ measurements against 10 targets |
| Equalizer | `Settings › Equalizer › Equalizer` | Bands by hand |
| Parametric EQ | `Settings › Equalizer › Parametric EQ` | Filters by frequency, gain and width |
| System-wide AutoEQ | `Settings › Equalizer › System-wide AutoEQ` | The correction for other apps too |
| Mixer | `Settings › Audio › Mixer`, or Mixer in the player | A console of buses and processors with loudness meters |
| Music links | On sliders and mixer knobs | Makes a control move with the kick, vocals, snare or hi-hats |

## 6. Dolby Atmos

All of it is on `Settings › Audio › Spatial renderer` (the Atmos Renderer
Configuration page).

| To do this | Use |
| --- | --- |
| Hear Atmos on headphones | Binaural render (SOFA HRTF): objects play from a 15.1.12 room of virtual speakers |
| More or less room sound | Room |
| Choose the room | Rooms: Built-in room, a measured room, or My room |
| Get a measured room | Browse room database |
| Build your own room | My room › Size and shape: presets, Width, Depth, Height, Damping, Roundness, Ceiling width, Ceiling depth |
| Use your own ears' data | SOFA presets, Browse HRTF database, Load .sofa file (`Settings › Audio › HRTF database`) |
| Keep high sounds overhead | Trapezoid panning, with Ceiling width, Ceiling depth, Wall curve |
| Atmos on real speakers | Render Atmos to speakers |
| Fold surround to stereo | Downmix multichannel to stereo (`Settings › Audio › Multichannel downmix`), with Downmix preamp |
| Atmos mixes from TIDAL | TIDAL Dolby Atmos (`Settings › Audio › TIDAL Dolby Atmos`) |
| Test it | Add Atmos test track to library |

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
| Share links | A tryptify.com link to a song or album that plays in anyone's Tryptify |
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
| Send the log live to someone helping you | Debug log › Live log (30 minutes, sign-in needed, off at every start) |
| Keep crash reports | `Settings › System › Save crash reports` |
| Move to a new phone | `Settings › System › Backup and restore` |
| Free space | `Settings › System › Clear cache` |
| Update | `Settings › System › Check for updates` |
| See what changed | `Settings › About › What's new`, `Version` |
| Run the first-start guide again | `Settings › System › Restart onboarding` |

## 14. Languages

English, 简体中文, 日本語, Français, Español, Türkçe, Deutsch, Italiano.
