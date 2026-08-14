package tf.monochrome.android.ui.settings

import tf.monochrome.android.BuildConfig

/**
 * The "What's New" entries shown in About, and the version they belong to.
 *
 * Deliberately hand-written rather than generated from the changelog: the
 * changelog explains *how* something was fixed for whoever reads the diff,
 * and this has to say what changed for someone who just wants their music to
 * play. Two or three lines each, no jargon that isn't already on a settings
 * screen.
 *
 * When cutting a release, add a new [WhatsNewRelease] at the top with the new
 * versionCode. Anything with a versionCode above what the user has already
 * acknowledged is what the update notice offers them.
 */
/**
 * [section] groups consecutive entries under a heading of their own. Most
 * releases are a flat list of unrelated fixes and don't want one; a release
 * that carries a whole new page does, because reading eight lines about
 * Discover interleaved with download and equaliser notes tells you far less
 * than the same eight lines under a title saying what they are about.
 */
data class WhatsNewEntry(
    val title: String,
    val body: String,
    val section: String? = null,
)

data class WhatsNewRelease(
    val versionCode: Int,
    val versionName: String,
    val entries: List<WhatsNewEntry>,
)

object WhatsNew {

    /** The one section heading in use — the Discover page and everything under it. */
    private const val DISCOVER = "Discover (Beta)"

    /** The world radio globe and everything hanging off it. */
    private const val GLOBE = "World radio"

    /** The app's own surfaces — glass, themes, search. */
    private const val LOOK = "Look and feel"

    /** Newest first. */
    val releases: List<WhatsNewRelease> = listOf(
        WhatsNewRelease(
            versionCode = 185,
            versionName = "1.8.5",
            entries = listOf(
                WhatsNewEntry(
                    section = GLOBE,
                    title = "Land and sea stop swapping places",
                    body = "Turning the globe used to flip it inside out for a frame or two — " +
                        "the sea filling in and the continents punched out of it — several " +
                        "times a second. It looked like the theme flickering. It doesn't happen " +
                        "any more, from any angle.",
                ),
                WhatsNewEntry(
                    section = GLOBE,
                    title = "Searching the globe finds things again",
                    body = "Typing a station, city or country came back with nothing at all: the " +
                        "search ran, and had nowhere to put what it found. The countries, cities " +
                        "and stations are back under the box.",
                ),
                WhatsNewEntry(
                    section = GLOBE,
                    title = "Continents you can actually see",
                    body = "Land is filled rather than outlined, so there's no guessing which " +
                        "side of a coast is which, and the borders glow in your theme's colour. " +
                        "The city you're listening to pulses so you can find it on the disc.",
                ),
                WhatsNewEntry(
                    section = GLOBE,
                    title = "The station list scrolls on the first try",
                    body = "It used to take two or three attempts before a drag was recognised. " +
                        "The globe underneath was swallowing them.",
                ),
                WhatsNewEntry(
                    section = LOOK,
                    title = "Pick your own colours",
                    body = "Settings › Appearance gains a Custom colors switch: choose an accent " +
                        "and a background and the whole app is built from them, overriding the " +
                        "theme above. Light or dark is decided by the background you pick, and " +
                        "text is kept readable on whatever you choose.",
                ),
                WhatsNewEntry(
                    section = LOOK,
                    title = "A light version of every theme",
                    body = "All fifteen themes now have a light variant, on your choice of two " +
                        "papers — crisp white, or a warm off-white with less glare. Every one is " +
                        "checked for contrast rather than eyeballed, so nothing comes out grey " +
                        "on grey.",
                ),
                WhatsNewEntry(
                    section = LOOK,
                    title = "One search bar, everywhere",
                    body = "Every screen's search is the same bar now, opened from the icon in " +
                        "the top bar. It floats over the page rather than pushing it down, hides " +
                        "nothing when it appears, and the list slides under the glass as you " +
                        "scroll.",
                ),
                WhatsNewEntry(
                    section = LOOK,
                    title = "Search your settings",
                    body = "A search in Settings that knows where everything lives — type " +
                        "\"crossfade\", \"scrobble\" or \"battery\" and tap the result to land on " +
                        "the setting itself, scrolled into view, whichever tab or sub-screen it " +
                        "is on.",
                ),
                WhatsNewEntry(
                    section = LOOK,
                    title = "Glass gives under your finger",
                    body = "Press anything made of glass and it swells where you touched it, " +
                        "the way the player's transport always has. Buttons, bars and cards used " +
                        "to either do nothing or shrink like a flat rectangle.",
                ),
                WhatsNewEntry(
                    section = LOOK,
                    title = "Glass you can see through",
                    body = "Search bars, settings rows and the mini player were sitting on solid " +
                        "panels that defeated the point of them. The panels are gone, every " +
                        "screen runs under the mini player, and the blur behind the glass is of " +
                        "the page rather than a flat colour.",
                ),
                WhatsNewEntry(
                    section = LOOK,
                    title = "Let the album repaint the whole app",
                    body = "Dynamic Colors gains \"Tint the menus too\": the cover sets the " +
                        "accent and background everywhere, not just the player. A second switch " +
                        "keeps your theme's background if you only want the accent to move.",
                ),
                WhatsNewEntry(
                    section = LOOK,
                    title = "Set how fast the colours change",
                    body = "A slider under Light paper, in seconds, for how long the album's " +
                        "colours take to cross over on a track change — from instant to eight " +
                        "seconds. Left alone it keeps step with Blend Between Tracks as before.",
                ),
                WhatsNewEntry(
                    section = LOOK,
                    title = "The home bar stops disappearing",
                    body = "On a light theme the gesture pill was drawn dark against the black " +
                        "foot of the player and vanished into it. The player sets the system " +
                        "bars from its own background now, top and bottom judged separately.",
                ),
                WhatsNewEntry(
                    section = LOOK,
                    title = "The speed panel frosts what's behind it",
                    body = "It was the last pane in the player painting a flat slab instead of " +
                        "blurring the artwork — it was drawn in a window of its own, where there " +
                        "was nothing to blur. It sits on the player now, and frosts it.",
                ),
                WhatsNewEntry(
                    title = "A spinning record on Discord",
                    body = "The \"Listening to\" card now turns the album art as a disc, once per " +
                        "bar at the track's own tempo, instead of drawing a spectrum across the " +
                        "sleeve.",
                ),
                WhatsNewEntry(
                    title = "Your own files show their artwork on Discord",
                    body = "A track ripped from a CD showed no cover at all, because there was " +
                        "no address Discord could fetch it from. The artwork is uploaded now, " +
                        "the same way the animation is.",
                ),
                WhatsNewEntry(
                    title = "The mixer stops resetting itself",
                    body = "Settings would quietly revert to an earlier state when the track " +
                        "changed. They stay put now, and there's a reset button for when you " +
                        "actually want defaults back.",
                ),
                WhatsNewEntry(
                    title = "Top 100 opens where you are",
                    body = "Tapping it on the genre panel expands the chart in place, with " +
                        "artwork, rather than taking you to a separate page.",
                ),
            ),
        ),
        WhatsNewRelease(
            versionCode = 184,
            versionName = "1.8.4",
            entries = listOf(
                WhatsNewEntry(
                    section = DISCOVER,
                    title = "Discover is its own tab, and it's in beta",
                    body = "It used to be a couple of rows buried in Home. It's now the place " +
                        "you go to look for something new, and every shelf says why it's there. " +
                        "Beta because the genre data behind it is young and can be wrong.",
                ),
                WhatsNewEntry(
                    section = DISCOVER,
                    title = "Type any of 771 genres",
                    body = "A search box that knows what you meant — dnb, d&b, liquid, phonk, " +
                        "and a typo like \"hardstlye\" still lands on hardstyle. The row under " +
                        "it changes as you type, instead of a fixed list you scroll past.",
                ),
                WhatsNewEntry(
                    section = DISCOVER,
                    title = "Moods you can stack",
                    body = "Pick more than one — Late night and Focus together draw from both, " +
                        "not from the sliver where they overlap. Anything in the mix you don't " +
                        "want can be dropped, and it comes back when you change moods.",
                ),
                WhatsNewEntry(
                    section = DISCOVER,
                    title = "The genre map",
                    body = "Every genre as one picture, linked to its neighbours. Dots are sized " +
                        "by how many people listen, so the big scenes read as big — or switch " +
                        "the weighting to age, and the map becomes a picture of when things began.",
                ),
                WhatsNewEntry(
                    section = DISCOVER,
                    title = "The same map, laid out by year",
                    body = "One tap moves every genre to where it belongs in time, oldest left, " +
                        "newest right, along a curve. Zoom in far enough and the axis goes from " +
                        "decades to single years.",
                ),
                WhatsNewEntry(
                    section = DISCOVER,
                    title = "A Top 100 for any genre",
                    body = "Over the last 7 days, 30 days, 6 months or year. Built from what " +
                        "people are actually listening to, and cross-checked against MusicBrainz " +
                        "so the artists in it really belong to the genre.",
                ),
                WhatsNewEntry(
                    section = DISCOVER,
                    title = "Tapping a genre plays that genre",
                    body = "It used to search the catalogue for the genre's name, which is " +
                        "exactly the query machine-made filler is built to win — ask for hard " +
                        "techno, get a track called \"Hard Techno\". It plays the charts now.",
                ),
                WhatsNewEntry(
                    title = "Show what you're playing on Discord",
                    body = "A \"Listening to\" card with the track, artist, album art and a live " +
                        "progress bar. Off by default, and it needs your Discord token — read " +
                        "what the setting says about that before switching it on.",
                ),
                WhatsNewEntry(
                    title = "Connect Last.fm in a browser",
                    body = "Connecting used to ask for a session key — something no listener " +
                        "has, and nothing in the app could produce. Tap Last.fm, approve it on " +
                        "Last.fm's own page, and it comes straight back connected.",
                ),
                WhatsNewEntry(
                    title = "Your own files play first",
                    body = "Tap a song anywhere and it plays the copy already on your device " +
                        "instead of streaming it — including tracks you downloaded, which used " +
                        "to re-stream from every screen but Downloads.",
                ),
                WhatsNewEntry(
                    title = "Gapless playback actually works",
                    body = "The toggle had never been connected to anything: it saved your " +
                        "choice and nothing read it. The next track is now made ready while the " +
                        "current one plays, so on-device files and Qobuz run straight on.",
                ),
                WhatsNewEntry(
                    title = "Blend between tracks",
                    body = "One slider in Settings decides what happens between songs. At zero " +
                        "they run straight into each other with no gap; add time and they " +
                        "overlap, one fading out as the next fades in.",
                ),
                WhatsNewEntry(
                    title = "Songs start faster",
                    body = "Playback begins after a quarter of the buffering it used to need, " +
                        "and Qobuz tracks now start as soon as the first audio arrives instead " +
                        "of waiting for the whole file to download.",
                ),
                WhatsNewEntry(
                    title = "Qobuz plays from Qobuz",
                    body = "Only the first track of a Qobuz album was actually coming from " +
                        "Qobuz — every song after it was fetched from the wrong service, which " +
                        "could fail outright or play a different recording.",
                ),
                WhatsNewEntry(
                    title = "Tracks no longer stall at the start",
                    body = "A song would sometimes load and then sit at 0:00 until you pressed " +
                        "play, most often the first time you heard it. It now starts on its own.",
                ),
                WhatsNewEntry(
                    title = "A song tapped straight after opening the app plays",
                    body = "Tap one before playback had finished connecting and nothing " +
                        "happened at all — no sound, no error, nothing to retry. The tap now " +
                        "waits the moment it needs instead of being dropped.",
                ),
                WhatsNewEntry(
                    title = "A slow track is no longer skipped past",
                    body = "A song that took a moment to load could be given up on within " +
                        "milliseconds and the queue would run on through it. It now gets a few " +
                        "seconds to come good, and a blend waits for it rather than fading the " +
                        "previous song out into silence.",
                ),
                WhatsNewEntry(
                    title = "Download as many as you like",
                    body = "Downloads are a proper queue now — send it fifty tracks and three " +
                        "transfer at a time under one notification. Queueing more than one at " +
                        "once used to be able to take the app down.",
                ),
                WhatsNewEntry(
                    title = "You can see what's already downloaded",
                    body = "Song rows show a ring with a down arrow when the track is on your " +
                        "device, in Library, playlists, albums, artists, Home and search. " +
                        "Unlike the progress spinner, it is still there after a restart.",
                ),
                WhatsNewEntry(
                    title = "Auto-download liked songs",
                    body = "Turn it on in Settings › Downloads and every song you like from " +
                        "then on is saved for offline. Songs you liked earlier are left alone, " +
                        "so switching it on never kicks off a huge download.",
                ),
                WhatsNewEntry(
                    title = "Crossfeed for headphones",
                    body = "A new toggle in the audio tools sheet sends a little of each " +
                        "channel to the far ear, the way speakers do, softening hard-panned " +
                        "old mixes. Long-press the row to set the speaker angle or pick a " +
                        "classic network.",
                ),
                WhatsNewEntry(
                    title = "Cleaner effects, and a louder ladder filter",
                    body = "The Inflator and Compressor gain an Off / 2x / 4x anti-alias " +
                        "control. The ladder filter is 25 dB cleaner and 6 dB louder — it had " +
                        "been throwing away half its level — so old ladder presets may want " +
                        "pulling down.",
                ),
                WhatsNewEntry(
                    title = "ReplayGain is gone",
                    body = "It never did anything: the mode had no control anywhere in the app, " +
                        "so volume matching sat switched off on every track. It is removed " +
                        "rather than left there looking functional. Nothing sounds different.",
                ),
                WhatsNewEntry(
                    title = "Apple Music is off the menu",
                    body = "Its settings, its onboarding card and its search results are gone, " +
                        "and search now covers TIDAL and Qobuz. Apple tracks you have already " +
                        "downloaded still play.",
                ),
                WhatsNewEntry(
                    title = "Search and sort any list of tracks",
                    body = "Playlists, album and artist pages, Liked Songs and every local " +
                        "library screen share one toolbar. Play, shuffle, select and Download " +
                        "All all follow what is on screen rather than what the filter hides.",
                ),
                WhatsNewEntry(
                    title = "A track menu wherever there are tracks",
                    body = "Local album, artist, genre, folder, Songs and Downloads rows gain " +
                        "the menu they never had: play next, add to playlist, go to album or " +
                        "artist, share the file.",
                ),
                WhatsNewEntry(
                    title = "Ten fonts, built in",
                    body = "The font library used to be empty until you found a .ttf yourself. " +
                        "Ten typefaces now ship with the app, from Space Grotesk and Manrope to " +
                        "JetBrains Mono, and the list folds away when you are not picking one.",
                ),
                WhatsNewEntry(
                    title = "Settings you can find things in",
                    body = "Instances, Scrobbling and Account merge into Connections, Appearance " +
                        "and Interface become one tab, and Playback, Equalizer, Library and " +
                        "Downloads each hold what belongs to them. Every tab looks the same now.",
                ),
                WhatsNewEntry(
                    title = "The player changes colour at the speed the music does",
                    body = "The background, the accents, the blurred backdrop and the album art " +
                        "itself now cross into the next track across your blend time, so nothing " +
                        "repaints while the previous song is still playing. Skip by hand and it " +
                        "still changes at once.",
                ),
                WhatsNewEntry(
                    title = "Tap the artwork to play",
                    body = "Album art in a song row plays the song instead of opening the album, " +
                        "and the artist and album links are harder to hit by accident.",
                ),
                WhatsNewEntry(
                    title = "Lighter to move around",
                    body = "The player rebuilt itself four times a second for the clock alone, " +
                        "long lists redrew from any change downwards, and screens kept working " +
                        "in the background. Scrolling and navigation are smoother for it.",
                ),
            ),
        ),
        // Written after the fact, from the commits between the 1.8.1 and 1.8.3
        // releases. 1.8.3 shipped without notes — the entry above it was written
        // to cover "the whole cycle that produced 1.8.4", which is all 1.8.4
        // work, so none of this was ever described anywhere a listener looks.
        WhatsNewRelease(
            versionCode = 183,
            versionName = "1.8.3",
            entries = listOf(
                WhatsNewEntry(
                    title = "Correct each ear separately",
                    body = "AutoEQ gains a stereo mode: pick a measurement for the left ear and " +
                        "another for the right, and tune the bands per side. The preamp stays " +
                        "shared, so it still guards both ears' headroom rather than letting one " +
                        "side clip.",
                ),
                WhatsNewEntry(
                    title = "Import an EqualizerAPO profile",
                    body = "Point it at a .txt from EqualizerAPO or AutoEq and it becomes bands " +
                        "you can edit. Anything it has to clamp or skip is listed before you " +
                        "commit, and handing it a measurement curve by mistake sends you to the " +
                        "right importer instead of failing.",
                ),
                WhatsNewEntry(
                    title = "Surround folds down properly",
                    body = "Multichannel music played in stereo used the ITU BS.775 fold, which " +
                        "pulls the surrounds toward the middle. It uses a fixed hard-pan matrix " +
                        "now, so the sides stay at the sides — and 16-channel 9.1.6 tracks are " +
                        "understood.",
                ),
                WhatsNewEntry(
                    title = "See what each effect is doing",
                    body = "Every plugin in the FX chain draws its own visualisation from a live " +
                        "tap of the audio passing through it, so a compressor shows its gain " +
                        "reduction and a filter shows its curve rather than a row of numbers.",
                ),
                WhatsNewEntry(
                    title = "One tip button",
                    body = "Stripe donations are gone. There is a single Ko-fi button in " +
                        "Settings instead.",
                ),
            ),
        ),
    )

    /** The release the app is currently running, if it has notes. */
    val current: WhatsNewRelease? get() = releases.firstOrNull()

    /**
     * Releases the user hasn't acknowledged yet — everything newer than
     * [seenVersionCode]. Empty when they're up to date.
     */
    fun unseen(seenVersionCode: Int): List<WhatsNewRelease> =
        releases.filter { it.versionCode > seenVersionCode }

    /**
     * Whether the update notice should appear.
     *
     * Someone upgrading has never written this preference either, so there is
     * no way to tell them apart from a fresh install by the stored value
     * alone. Showing it to both is the safe side of that: a new user sees one
     * dismissible bar describing the app they just installed, whereas the
     * other choice silently hides the notes from every existing user on the
     * upgrade this exists for.
     */
    fun shouldNotify(seenVersionCode: Int, neverShow: Boolean): Boolean {
        if (neverShow) return false
        return unseen(seenVersionCode).isNotEmpty()
    }

    /** The versionCode to record once the notes have been read or dismissed. */
    val currentVersionCode: Int get() = BuildConfig.VERSION_CODE
}
