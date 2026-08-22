// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.glyph

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.performScrollTo
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tf.monochrome.android.audio.stepmania.StepManiaDifficulty
import tf.monochrome.android.domain.model.AudioCodec
import tf.monochrome.android.glyph.asset.GlyphAssetRepository
import tf.monochrome.android.glyph.asset.GlyphLane
import tf.monochrome.android.glyph.data.GlyphChartState
import tf.monochrome.android.glyph.engine.GlyphJudgement
import tf.monochrome.android.glyph.data.GlyphSong
import tf.monochrome.android.ui.glyph.GlyphEvent
import tf.monochrome.android.ui.glyph.GlyphChipRow
import tf.monochrome.android.ui.glyph.GlyphHomeScreen
import tf.monochrome.android.ui.glyph.GlyphJudgementFx
import tf.monochrome.android.ui.glyph.GlyphLaneInput
import tf.monochrome.android.ui.glyph.GlyphResultsScreen
import tf.monochrome.android.ui.glyph.GlyphResultsUi
import tf.monochrome.android.ui.glyph.GlyphTypography
import tf.monochrome.android.ui.glyph.GlyphSectionResult
import tf.monochrome.android.ui.glyph.GlyphUiState
import tf.monochrome.android.glyph.data.GlyphAttempt

/**
 * The mode's screens, driven as a user would.
 *
 * Run under Robolectric rather than as an instrumented suite so
 * `testDebugUnitTest` covers them: a device-only test is one that does not run
 * on most changes, and navigation and accessibility labels are exactly the
 * things that rot silently.
 *
 * The assertions are about *labels*, not layout. Every control in this mode is
 * an unlabelled pictogram from the pack, so a missing content description is
 * not a cosmetic problem — it makes the control unusable, and nothing about the
 * rendered pixels would show it.
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

        assertEquals(listOf(GlyphEvent.SelectSong("local_1")), events)
    }

    // ── navigation ──────────────────────────────────────────────────────

    @Test
    fun `the selection panel offers both ways to start`() {
        val events = mutableListOf<GlyphEvent>()
        compose.setContent {
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
        compose.onNodeWithText("Training Ground").assertIsDisplayed()
    }

    @Test
    fun `a song without a chart offers generation instead of play`() {
        val events = mutableListOf<GlyphEvent>()
        val ungenerated = song(state = GlyphChartState.NOT_GENERATED)
        compose.setContent {
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
        assertEquals(listOf(GlyphEvent.GenerateChart), events)
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
        // The back control is an unlabelled pack glyph; without this
        // description there is no way to reach it without sight.
        compose.onNodeWithContentDescription("Back").performClick()
        assertEquals(1, backs)
    }

    @Test
    fun `generation progress is announced with its stage and percentage`() {
        compose.setContent {
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
            GlyphLaneInput(onPress = { pressed += it }, onRelease = {})
        }
        // The lanes are unlabelled touch zones over artwork; without these
        // names there is no way to tell them apart without sight.
        for (lane in GlyphLane.entries) {
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
                    GlyphLaneInput(onPress = {}, onRelease = {}, hitboxScale = 0.55f)
                }
                androidx.compose.foundation.layout.Box(
                    modifier = androidx.compose.ui.Modifier.size(400.dp, 100.dp),
                ) {
                    GlyphLaneInput(onPress = {}, onRelease = {}, hitboxScale = 1f)
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
                    typography = GlyphTypography(androidx.compose.ui.text.font.FontFamily.Monospace),
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
    fun `a judgement announces itself with its combo`() {
        // The letterforms are graphics to a screen reader, so without a live
        // region a judgement lands silently. The old flat wordmark had the
        // same problem and the same fix; the FX path must not lose it.
        compose.setContent {
            GlyphJudgementFx(
                judgement = GlyphJudgement.MARVELOUS,
                shownAtMs = 1L,
                combo = 48,
                reducedMotion = true,
            )
        }
        compose.onNodeWithText("MARVELOUS").assertIsDisplayed()
        compose.onNodeWithText("48").assertIsDisplayed()
    }

    @Test
    fun `reduced motion still says what was hit`() {
        // The flat path drops the wave, the glass and the frame clock. What it
        // must not drop is the word.
        compose.setContent {
            GlyphJudgementFx(
                judgement = GlyphJudgement.MISS,
                shownAtMs = 1L,
                combo = 0,
                reducedMotion = true,
            )
        }
        compose.onNodeWithText("MISS").assertIsDisplayed()
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
        compose.onNodeWithContentDescription("Marvelous: 200").performScrollTo().assertIsDisplayed()
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
