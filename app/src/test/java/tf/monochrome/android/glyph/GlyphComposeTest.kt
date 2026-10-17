// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.glyph

import androidx.compose.foundation.layout.size
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tf.monochrome.android.R
import tf.monochrome.android.audio.stepmania.StepManiaDifficulty
import tf.monochrome.android.domain.model.AudioCodec
import tf.monochrome.android.glyph.asset.GlyphAssetRepository
import tf.monochrome.android.glyph.asset.GlyphLane
import tf.monochrome.android.glyph.data.GlyphAttempt
import tf.monochrome.android.glyph.data.GlyphChartState
import tf.monochrome.android.glyph.data.GlyphSong
import tf.monochrome.android.glyph.engine.GlyphJudgement
import tf.monochrome.android.ui.glyph.GlyphChipRow
import tf.monochrome.android.ui.glyph.GlyphEvent
import tf.monochrome.android.ui.glyph.GlyphHomeScreen
import tf.monochrome.android.ui.glyph.GlyphJudgementFx
import tf.monochrome.android.ui.glyph.GlyphLaneInput
import tf.monochrome.android.ui.glyph.GlyphResultsScreen
import tf.monochrome.android.ui.glyph.GlyphResultsUi
import tf.monochrome.android.ui.glyph.GlyphSectionResult
import tf.monochrome.android.ui.glyph.GlyphUiState
import tf.monochrome.android.ui.glyph.rememberStepFonts

/**
 * The mode's screens, driven as a user would.
 *
 * Run under Robolectric rather than as an instrumented suite so
 * `testDebugUnitTest` covers them: a device-only test is one that does not run
 * on most changes, and navigation and accessibility labels are exactly the
 * things that rot silently.
 *
 * The assertions are about *labels*, not layout. The top bars' actions, the
 * lanes and the graphs are icons, touch zones and drawings, so a missing
 * content description is not a cosmetic problem — it makes the control
 * unusable, and nothing about the rendered pixels would show it.
 */
@RunWith(RobolectricTestRunner::class)
// A plain Application, not the app's. The real one is a Hilt entry point that
// stands up Supabase, the player service and the scanner on create — none of
// which these screens touch, all of which fail without a device. The screens
// take their dependencies as parameters precisely so they can be exercised
// without any of that.
@Config(sdk = [34], application = android.app.Application::class)
class GlyphComposeTest {

    @get:Rule
    val compose = createComposeRule()

    private val assets = GlyphAssetRepository(
        androidx.test.core.app.ApplicationProvider.getApplicationContext(),
        Json { ignoreUnknownKeys = true; isLenient = true },
    )


    /**
     * Render [content] with glass switched off.
     *
     * The home screen's panels are real glass over a haze source, and haze
     * invalidates continuously — Compose never reports idle, so the test times
     * out rather than fails. Switching it off takes the same flat path a
     * low-tier device takes, which is a path worth covering anyway; what these
     * tests assert is the panels' content, not their material.
     */
    private fun setFlatContent(content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.setContent {
            androidx.compose.runtime.CompositionLocalProvider(
                tf.monochrome.android.performance.LocalLowPerformance provides
                    tf.monochrome.android.performance.LowPerformanceSettings(
                        disableLiquidGlass = true,
                    ),
            ) { content() }
        }
    }

    private fun song(
        title: String = "Test Song",
        state: GlyphChartState = GlyphChartState.READY,
        difficulties: List<StepManiaDifficulty> = listOf(
            StepManiaDifficulty.EASY,
            StepManiaDifficulty.MEDIUM,
            StepManiaDifficulty.HARD,
        ),
    ) = GlyphSong(
        trackId = "local_1",
        chartId = "abc123",
        title = title,
        artist = "Tryptify",
        filePath = "/music/test.flac",
        artworkUri = null,
        durationSeconds = 214,
        codec = AudioCodec.FLAC,
        bpm = if (state == GlyphChartState.READY) 148f else null,
        difficulties = if (state == GlyphChartState.READY) difficulties else emptyList(),
        chartState = state,
    )

    // ── the song list ───────────────────────────────────────────────────

    @Test
    fun `the empty library says what to do about it`() {
        compose.setContent {
            GlyphHomeScreen(
                state = GlyphUiState(songs = emptyList(), isLoadingSongs = false),
                assets = assets,
                onEvent = {},
                onBack = {},
            )
        }
        // An empty list with no explanation reads as a broken screen.
        compose.onNodeWithText(
            "No MP3 or FLAC files in your library yet. " +
                "Import one, or generate a chart from a file.",
        ).assertIsDisplayed()
    }

    @Test
    fun `the loading state is distinguishable from an empty one`() {
        compose.setContent {
            GlyphHomeScreen(
                state = GlyphUiState(isLoadingSongs = true),
                assets = assets,
                onEvent = {},
                onBack = {},
            )
        }
        compose.onNodeWithText("Reading the library…").assertIsDisplayed()
    }

    @Test
    fun `a search that matches nothing says so rather than showing an empty list`() {
        compose.setContent {
            GlyphHomeScreen(
                state = GlyphUiState(
                    songs = listOf(song()),
                    isLoadingSongs = false,
                    songQuery = "zzzz",
                ),
                assets = assets,
                onEvent = {},
                onBack = {},
            )
        }
        compose.onNodeWithText("Nothing matches \"zzzz\".").assertIsDisplayed()
    }

    @Test
    fun `a song row speaks its chart state and metadata`() {
        compose.setContent {
            GlyphHomeScreen(
                state = GlyphUiState(songs = listOf(song()), isLoadingSongs = false),
                assets = assets,
                onEvent = {},
                onBack = {},
            )
        }
        // The row is one node to a screen reader, carrying everything the
        // sighted reading gives: title, artist, length, format, tempo, tiers.
        compose.onNodeWithContentDescription(
            "Test Song, Tryptify, 3:34, FLAC, 148 BPM, 3 difficulties",
        ).assertIsDisplayed()
    }

    @Test
    fun `a song with no chart says so instead of showing nothing`() {
        compose.setContent {
            GlyphHomeScreen(
                state = GlyphUiState(
                    songs = listOf(song(state = GlyphChartState.NOT_GENERATED)),
                    isLoadingSongs = false,
                ),
                assets = assets,
                onEvent = {},
                onBack = {},
            )
        }
        compose.onNodeWithContentDescription(
            "Test Song, Tryptify, 3:34, FLAC, No chart",
        ).assertIsDisplayed()
    }

    @Test
    fun `selecting a song emits the event rather than changing state locally`() {
        val events = mutableListOf<GlyphEvent>()
        compose.setContent {
            GlyphHomeScreen(
                state = GlyphUiState(songs = listOf(song()), isLoadingSongs = false),
                assets = assets,
                onEvent = { events += it },
                onBack = {},
            )
        }
        compose.onNodeWithContentDescription(
            "Test Song, Tryptify, 3:34, FLAC, 148 BPM, 3 difficulties",
        ).performClick()

        // The wheel also reports what is at its centre; the choice is the tap's.
        assertEquals(listOf(GlyphEvent.SelectSong("local_1")), events.filterNot { it is GlyphEvent.WheelFocus })
    }

    private fun string(id: Int): String =
        androidx.test.core.app.ApplicationProvider
            .getApplicationContext<android.content.Context>()
            .getString(id)

    @Test
    fun `the search bar is closed until it is asked for`() {
        // A bar that is part of the screen holds a permanent gap and throws the
        // keyboard up on arrival. This one is summoned, so at rest there is
        // nothing but the app's search action in the top bar — labelled, as
        // every icon in a top bar has to be.
        setFlatContent {
            GlyphHomeScreen(
                state = GlyphUiState(songs = listOf(song()), isLoadingSongs = false),
                assets = assets,
                onEvent = {},
                onBack = {},
            )
        }
        compose.onNodeWithContentDescription(string(R.string.tab_search)).assertIsDisplayed()
        // The song under it is not covered while the bar is shut.
        compose.onNodeWithContentDescription(
            "Test Song, Tryptify, 3:34, FLAC, 148 BPM, 3 difficulties",
        ).assertIsDisplayed()
    }

    @Test
    fun `closing the search clears what was typed`() {
        // Leaving a stale query behind means reopening the bar shows a filtered
        // list with no visible reason for it.
        val events = mutableListOf<GlyphEvent>()
        setFlatContent {
            GlyphHomeScreen(
                state = GlyphUiState(songs = listOf(song()), isLoadingSongs = false),
                assets = assets,
                onEvent = { events += it },
                onBack = {},
            )
        }
        compose.onNodeWithContentDescription(string(R.string.tab_search)).performClick()
        // Both the top bar's action and the bar's own button close it; either
        // must clear the query.
        compose.onAllNodesWithContentDescription(string(R.string.action_close_search))
            .onFirst()
            .performClick()
        assertTrue(events.contains(GlyphEvent.Search("")))
    }

    @Test
    fun `back closes the search before it leaves the mode`() {
        // Back while searching used to leave Trypt Mania altogether, throwing
        // away the screen to dismiss a search bar.
        val events = mutableListOf<GlyphEvent>()
        var backs = 0
        lateinit var dispatcher: androidx.activity.OnBackPressedDispatcher
        compose.setContent {
            dispatcher = androidx.activity.compose.LocalOnBackPressedDispatcherOwner.current!!
                .onBackPressedDispatcher
            androidx.compose.runtime.CompositionLocalProvider(
                tf.monochrome.android.performance.LocalLowPerformance provides
                    tf.monochrome.android.performance.LowPerformanceSettings(
                        disableLiquidGlass = true,
                    ),
            ) {
                GlyphHomeScreen(
                    state = GlyphUiState(songs = listOf(song()), isLoadingSongs = false),
                    assets = assets,
                    onEvent = { events += it },
                    onBack = { backs += 1 },
                )
            }
        }
        compose.onNodeWithContentDescription(string(R.string.tab_search)).performClick()
        compose.runOnIdle { dispatcher.onBackPressed() }
        compose.waitForIdle()

        assertTrue(events.contains(GlyphEvent.Search("")))
        assertEquals(0, backs)
        // Closed: the action is back to opening the search.
        compose.onNodeWithContentDescription(string(R.string.tab_search)).assertIsDisplayed()
    }

    // ── navigation ──────────────────────────────────────────────────────

    @Test
    fun `the selection panel offers both ways to start`() {
        val events = mutableListOf<GlyphEvent>()
        setFlatContent {
            GlyphHomeScreen(
                state = GlyphUiState(
                    songs = listOf(song()),
                    isLoadingSongs = false,
                    selectedSong = song(),
                    selectedDifficulty = StepManiaDifficulty.MEDIUM,
                    simfile = null,
                ),
                assets = assets,
                onEvent = { events += it },
                onBack = {},
            )
        }
        // Play and Training Ground are peers. Training is not an advanced
        // option hidden behind Play.
        compose.onNodeWithText("Play").assertIsDisplayed()
        compose.onNodeWithText("Train").assertIsDisplayed()
    }

    @Test
    fun `trypt mania hides the mini player, keeps the tab bar, and gives it back`() {
        val chrome = tf.monochrome.android.ui.components.AppChrome()
        val inMode = androidx.compose.runtime.mutableStateOf(true)
        compose.setContent {
            androidx.compose.runtime.CompositionLocalProvider(
                tf.monochrome.android.ui.components.LocalAppChrome provides chrome,
            ) {
                if (inMode.value) tf.monochrome.android.ui.components.HideMiniPlayer(true)
            }
        }
        compose.runOnIdle {
            assertTrue(chrome.miniPlayerHidden)
            // The tab bar is not part of it.
            assertTrue(!chrome.hidden)
        }
        inMode.value = false
        compose.runOnIdle { assertTrue(!chrome.miniPlayerHidden) }
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun `on a phone held upright the card shows the groove radar`() {
        setFlatContent {
            GlyphHomeScreen(
                state = GlyphUiState(songs = listOf(song()), isLoadingSongs = false, selectedSong = song()),
                assets = assets,
                onEvent = {},
                onBack = {},
            )
        }
        compose.onNodeWithContentDescription("Groove radar", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Play").assertIsDisplayed()
    }

    @Test
    fun `on a short screen the card steps aside for the wheel and play`() {
        // The default test screen is 470dp tall, a phone on its side: no room
        // for the card, the wheel and the panel together.
        setFlatContent {
            GlyphHomeScreen(
                state = GlyphUiState(songs = listOf(song()), isLoadingSongs = false, selectedSong = song()),
                assets = assets,
                onEvent = {},
                onBack = {},
            )
        }
        compose.onNodeWithContentDescription("Groove radar", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Play").assertIsDisplayed()
    }

    @Test
    fun `a song without a chart offers generation instead of play`() {
        val events = mutableListOf<GlyphEvent>()
        val ungenerated = song(state = GlyphChartState.NOT_GENERATED)
        setFlatContent {
            GlyphHomeScreen(
                state = GlyphUiState(
                    songs = listOf(ungenerated),
                    isLoadingSongs = false,
                    selectedSong = ungenerated,
                ),
                assets = assets,
                onEvent = { events += it },
                onBack = {},
            )
        }
        compose.onNodeWithText("Generate chart").performClick()
        assertEquals(listOf(GlyphEvent.GenerateChart), events.filterNot { it is GlyphEvent.WheelFocus })
    }

    @Test
    fun `back is reachable and labelled`() {
        var backs = 0
        compose.setContent {
            GlyphHomeScreen(
                state = GlyphUiState(isLoadingSongs = false),
                assets = assets,
                onEvent = {},
                onBack = { backs += 1 },
            )
        }
        // The top bar's Back is an icon; without its description there is no
        // way to reach it without sight.
        compose.onNodeWithContentDescription(string(R.string.action_back)).performClick()
        assertEquals(1, backs)
    }

    @Test
    fun `generation progress is announced with its stage and percentage`() {
        setFlatContent {
            GlyphHomeScreen(
                state = GlyphUiState(
                    isLoadingSongs = false,
                    songs = listOf(song()),
                    generation = tf.monochrome.android.ui.glyph.GlyphGenerationState(
                        trackId = "local_1",
                        fraction = 0.42f,
                        stage = "Separating drums",
                    ),
                ),
                assets = assets,
                onEvent = {},
                onBack = {},
            )
        }
        compose.onNodeWithText("Separating drums").assertIsDisplayed()
        // The meter carries its reading, so progress is not width-only.
        compose.onNodeWithContentDescription("Separating drums, 42 percent").assertIsDisplayed()
    }

    @Test
    fun `each lane is reachable and named`() {
        val pressed = mutableListOf<GlyphLane>()
        compose.setContent {
            GlyphLaneInput(onPress = { lane, _ -> pressed += lane }, onRelease = { _, _ -> })
        }
        // The lanes are unlabelled touch zones over artwork; without these
        // names there is no way to tell them apart without sight.
        for (lane in GlyphLane.DANCE) {
            compose.onNodeWithContentDescription("${lane.label} lane").assertIsDisplayed()
        }
    }

    @Test
    fun `narrowing the hitbox leaves gutters between the lanes`() {
        // The setting only goes one way: four lanes already tile the width, so
        // there is nothing to widen into and narrowing is the real knob. This
        // pins that a narrowed zone is genuinely smaller than a full one, which
        // is what makes the control more than a stored number.
        //
        // Both scales are composed at once because the rule allows one
        // setContent per test; the two "Left lane" nodes come back in
        // composition order.
        compose.setContent {
            androidx.compose.foundation.layout.Column {
                androidx.compose.foundation.layout.Box(
                    modifier = androidx.compose.ui.Modifier.size(400.dp, 100.dp),
                ) {
                    GlyphLaneInput(onPress = { _, _ -> }, onRelease = { _, _ -> }, hitboxScale = 0.55f)
                }
                androidx.compose.foundation.layout.Box(
                    modifier = androidx.compose.ui.Modifier.size(400.dp, 100.dp),
                ) {
                    GlyphLaneInput(onPress = { _, _ -> }, onRelease = { _, _ -> }, hitboxScale = 1f)
                }
            }
        }

        val zones = compose.onAllNodesWithContentDescription("Left lane")
        val narrow = zones[0].fetchSemanticsNode().size.width
        val full = zones[1].fetchSemanticsNode().size.width

        assertTrue("narrow=$narrow full=$full", narrow < full)
        // The narrow zone is 55% of the lane, so it lands near 55% of the full
        // one. A range rather than an equality: the width is rounded to whole
        // pixels at whatever density the test host reports.
        assertTrue(
            "narrow=$narrow should be about 55% of full=$full",
            narrow in (full * 50 / 100)..(full * 60 / 100),
        )
    }

    @Test
    fun `every difficulty stays reachable in a narrow chip row`() {
        // Five chips with their meters need roughly 450dp; a phone lane gives
        // about 358. As a plain Row the hardest tier fell off the right edge
        // with nothing on screen to say it existed.
        val picked = mutableListOf<StepManiaDifficulty>()
        compose.setContent {
            androidx.compose.foundation.layout.Box(
                modifier = androidx.compose.ui.Modifier.size(358.dp, 120.dp),
            ) {
                GlyphChipRow(
                    options = StepManiaDifficulty.entries.toList(),
                    selected = StepManiaDifficulty.MEDIUM,
                    label = { "${it.sscName} ${it.meter}" },
                    onSelect = { picked += it },
                )
            }
        }

        // Every tier is in the tree and can be scrolled to and chosen — the
        // assertion that would have failed before the row could scroll.
        for (difficulty in StepManiaDifficulty.entries) {
            compose.onNodeWithText("${difficulty.sscName} ${difficulty.meter}")
                .performScrollTo()
                .assertIsDisplayed()
        }
        compose.onNodeWithText("Challenge 14").performScrollTo().performClick()
        assertEquals(listOf(StepManiaDifficulty.CHALLENGE), picked)
    }

    @Test
    fun `a judgement says what was hit and shows its combo`() {
        // StepMania's judgement is a sprite, so it is named for a screen
        // reader; the combo beside it is plain text.
        compose.setContent {
            GlyphJudgementFx(
                judgement = GlyphJudgement.MARVELOUS,
                early = true,
                shownAtMs = 1L,
                combo = 48,
                fullCombo = null,
                fonts = rememberStepFonts(),
            )
        }
        compose.onNodeWithContentDescription("Flawless").assertIsDisplayed()
        compose.onNodeWithText("48").assertIsDisplayed()
    }

    @Test
    fun `a miss is named and shows no combo`() {
        compose.setContent {
            GlyphJudgementFx(
                judgement = GlyphJudgement.MISS,
                early = false,
                shownAtMs = 1L,
                combo = 0,
                fullCombo = null,
                fonts = rememberStepFonts(),
            )
        }
        compose.onNodeWithContentDescription("Miss").assertIsDisplayed()
        compose.onNodeWithText("0").assertDoesNotExist()
    }

    // ── results ─────────────────────────────────────────────────────────

    private fun attempt() = GlyphAttempt(
        id = "a1",
        chartId = "abc123",
        songTitle = "Test Song",
        difficulty = StepManiaDifficulty.HARD,
        playedAtEpochMs = 1L,
        score = 934_120,
        accuracy = 0.9341f,
        maxCombo = 312,
        judgementCounts = mapOf(
            "MARVELOUS" to 200, "PERFECT" to 80, "GREAT" to 20,
            "GOOD" to 6, "BOO" to 2, "MISS" to 4,
        ),
        early = 120,
        late = 188,
        meanOffsetMs = 7.4f,
        deviationMs = 21.2f,
    )

    @Test
    fun `the results screen states the grade in words as well as artwork`() {
        compose.setContent {
            GlyphResultsScreen(
                results = GlyphResultsUi(attempt = attempt(), previousBest = null),
                assets = assets,
                onEvent = {},
            )
        }
        // The badge is an image; the grade has to be readable without it.
        compose.onNodeWithContentDescription("Grade S").assertIsDisplayed()
        compose.onNodeWithText("93.41%").assertIsDisplayed()
    }

    @Test
    fun `judgement counts are readable as pairs not as stray numbers`() {
        compose.setContent {
            GlyphResultsScreen(
                results = GlyphResultsUi(attempt = attempt(), previousBest = null),
                assets = assets,
                onEvent = {},
            )
        }
        // Scrolled to rather than merely asserted to exist: the results screen
        // scrolls, and a label that can never be brought on screen is no more
        // reachable than a missing one.
        // StepMania's names for the windows: W1 is Flawless.
        compose.onNodeWithContentDescription("Flawless: 200").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Miss: 4").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `early late balance is stated numerically`() {
        compose.setContent {
            GlyphResultsScreen(
                results = GlyphResultsUi(attempt = attempt(), previousBest = null),
                assets = assets,
                onEvent = {},
            )
        }
        compose.onNodeWithContentDescription("120 hits early, 188 hits late")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun `the ring says every judgement and its share`() {
        compose.setContent {
            GlyphResultsScreen(
                results = GlyphResultsUi(attempt = attempt(), previousBest = null),
                assets = assets,
                onEvent = {},
            )
        }
        compose.onNodeWithContentDescription(
            "Judgements: 200 flawless, 80 perfect, 20 great, 6 good, 2 bad, 4 miss, of 312",
        ).assertIsDisplayed()
    }

    @Test
    fun `once counted, a judgement line lights its share of the ring`() {
        compose.setContent {
            GlyphResultsScreen(
                results = GlyphResultsUi(attempt = attempt(), previousBest = null),
                assets = assets,
                onEvent = {},
            )
        }
        // The counts have run out by now (the test clock runs them through).
        compose.onNodeWithContentDescription("Perfect: 80").performScrollTo().performClick()
        compose.onNodeWithText("PERFECT").assertExists()
        compose.onNodeWithText("25% of 312 steps").assertExists()
    }

    @Test
    fun `the timing curve says what it draws`() {
        compose.setContent {
            GlyphResultsScreen(
                results = GlyphResultsUi(attempt = attempt(), previousBest = null),
                assets = assets,
                onEvent = {},
            )
        }
        compose.onNodeWithContentDescription("Timing curve: hits spread 21 milliseconds either side of 7 late")
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText("7 ms late on average").assertExists()
    }

    @Test
    fun `timing is corrected from recent runs, and only when they agree`() {
        val events = mutableListOf<GlyphEvent>()
        val recent = androidx.compose.runtime.mutableStateOf<Int?>(null)
        compose.setContent {
            GlyphResultsScreen(
                results = GlyphResultsUi(attempt = attempt(), previousBest = null),
                assets = assets,
                onEvent = { events += it },
                recentLateMs = recent.value,
            )
        }
        compose.onNodeWithText("Centre my timing", substring = true).assertDoesNotExist()
        recent.value = 12
        compose.onNodeWithText("Centre my timing (recent runs 12 ms late)").performScrollTo().performClick()
        assertEquals(listOf(GlyphEvent.ApplyRecentTiming), events)
    }

    @Test
    fun `a weak section leads straight into training ground`() {
        val events = mutableListOf<GlyphEvent>()
        val sections = listOf(
            GlyphSectionResult(0, 0f, 20f, 0.98f, 40, 0),
            GlyphSectionResult(1, 20f, 40f, 0.62f, 44, 9),
            GlyphSectionResult(2, 40f, 60f, 0.95f, 38, 1),
        )
        compose.setContent {
            GlyphResultsScreen(
                results = GlyphResultsUi(
                    attempt = attempt(),
                    previousBest = null,
                    sections = sections,
                ),
                assets = assets,
                onEvent = { events += it },
            )
        }

        // The whole point of the graph: the weakest passage is one tap from
        // being a practice loop, not something to hunt for by hand.
        compose.onNodeWithText("Practise your weakest section (0:20)")
            .performScrollTo()
            .performClick()
        assertEquals(listOf(GlyphEvent.PractiseSection(1)), events)
    }

    @Test
    fun `the accuracy graph describes itself and names the weakest section`() {
        val sections = listOf(
            GlyphSectionResult(0, 0f, 20f, 0.98f, 40, 0),
            GlyphSectionResult(1, 20f, 40f, 0.62f, 44, 9),
        )
        compose.setContent {
            GlyphResultsScreen(
                results = GlyphResultsUi(
                    attempt = attempt(),
                    previousBest = null,
                    sections = sections,
                ),
                assets = assets,
                onEvent = {},
            )
        }
        compose.onNodeWithContentDescription(
            "Accuracy by section. Weakest at 0:20, 62 percent. " +
                "Tap a bar to select a section to practise.",
        ).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `a training segment is marked as not a full run`() {
        compose.setContent {
            GlyphResultsScreen(
                results = GlyphResultsUi(
                    attempt = attempt().copy(
                        segmentStartSeconds = 30f,
                        segmentEndSeconds = 45f,
                    ),
                    previousBest = null,
                ),
                assets = assets,
                onEvent = {},
            )
        }
        // A fifteen-second loop must never look like a personal best.
        compose.onNodeWithText("Training segment — not filed as a full run.")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun `beating a previous best is stated plainly`() {
        compose.setContent {
            GlyphResultsScreen(
                results = GlyphResultsUi(
                    attempt = attempt(),
                    previousBest = attempt().copy(id = "old", score = 900_000),
                ),
                assets = assets,
                onEvent = {},
            )
        }
        compose.onNodeWithText("New best, up 34,120 points").performScrollTo().assertIsDisplayed()
    }
}
