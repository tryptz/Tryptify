package tf.monochrome.android.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import tf.monochrome.android.ui.onboarding.steps.AudioOutputStep
import tf.monochrome.android.ui.onboarding.steps.DoneStep
import tf.monochrome.android.ui.onboarding.steps.DownloadLocationStep
import tf.monochrome.android.ui.onboarding.steps.FeatureTourStep
import tf.monochrome.android.ui.onboarding.steps.FoldersStep
import tf.monochrome.android.ui.onboarding.steps.PermissionsStep
import tf.monochrome.android.ui.onboarding.steps.StreamingStep
import tf.monochrome.android.ui.onboarding.steps.WelcomeStep
import tf.monochrome.android.ui.theme.MonoDimens

/**
 * First-run wizard root. Lives outside the nav graph (MainActivity swaps
 * between this and MonochromeNavHost on the `onboarding_complete` flag).
 *
 * @param onFinished invoked when the user leaves the flow; the argument is
 *   an optional route the main app should open first ("library",
 *   "settings?tab=7") or null for the default.
 */
@Composable
fun OnboardingScreen(
    onFinished: (postRoute: String?) -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val step by viewModel.step.collectAsState()

    BackHandler(enabled = step != OnboardingStep.WELCOME) { viewModel.back() }

    val finish: (String?) -> Unit = { postRoute ->
        // Record the landing route before the flag flip recomposes the tree.
        onFinished(postRoute)
        viewModel.completeOnboarding()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars)
    ) {
        StepProgressDots(
            current = step.ordinal,
            total = OnboardingStep.entries.size,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = MonoDimens.spacingXl, bottom = MonoDimens.spacingSm)
        )

        AnimatedContent(
            targetState = step,
            transitionSpec = {
                val forward = targetState.ordinal >= initialState.ordinal
                val slideSpec = spring<IntOffset>(
                    dampingRatio = Spring.DampingRatioLowBouncy,
                    stiffness = Spring.StiffnessMediumLow
                )
                if (forward) {
                    (slideInHorizontally(slideSpec) { it } + fadeIn()) togetherWith
                        (slideOutHorizontally(slideSpec) { -it / 3 } + fadeOut())
                } else {
                    (slideInHorizontally(slideSpec) { -it } + fadeIn()) togetherWith
                        (slideOutHorizontally(slideSpec) { it / 3 } + fadeOut())
                }
            },
            label = "onboardingStep",
            modifier = Modifier.fillMaxSize()
        ) { currentStep ->
            when (currentStep) {
                OnboardingStep.WELCOME -> WelcomeStep(
                    onGetStarted = { viewModel.next() },
                    onSkipSetup = { finish(null) }
                )
                OnboardingStep.PERMISSIONS -> PermissionsStep(viewModel)
                OnboardingStep.FOLDERS -> FoldersStep(viewModel)
                OnboardingStep.DOWNLOADS -> DownloadLocationStep(viewModel)
                OnboardingStep.STREAMING -> StreamingStep(
                    viewModel = viewModel,
                    onQobuzSetup = { finish("settings?tab=7") }
                )
                OnboardingStep.AUDIO_OUTPUT -> AudioOutputStep(viewModel)
                OnboardingStep.TOUR -> FeatureTourStep(onDone = { viewModel.next() })
                OnboardingStep.DONE -> DoneStep(
                    viewModel = viewModel,
                    onStartListening = { finish("library") }
                )
            }
        }
    }
}

@Composable
private fun StepProgressDots(current: Int, total: Int, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(total) { index ->
            val active = index == current
            val width by animateDpAsState(
                targetValue = if (active) 20.dp else 6.dp,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMedium
                ),
                label = "dotWidth"
            )
            Box(
                modifier = Modifier
                    .padding(horizontal = 3.dp)
                    .width(width)
                    .height(6.dp)
                    .background(
                        color = if (active) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f),
                        shape = MonoDimens.shapePill
                    )
            )
        }
    }
}

/**
 * Shared layout for a wizard step: large heading, one-line subtitle,
 * scrollable body, and a pinned bottom action row with one primary button
 * and an optional skip/secondary text action.
 */
@Composable
internal fun OnboardingStepScaffold(
    title: String,
    subtitle: String? = null,
    primaryLabel: String,
    onPrimary: () -> Unit,
    primaryEnabled: Boolean = true,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = MonoDimens.spacingXl)
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = MonoDimens.spacingXl)
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = MonoDimens.spacingSm)
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = MonoDimens.spacingXl),
                content = content
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = MonoDimens.spacingLg),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Button(
                onClick = onPrimary,
                enabled = primaryEnabled,
                shape = MonoDimens.shapePill,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                Text(primaryLabel, style = MaterialTheme.typography.titleMedium)
            }
            if (secondaryLabel != null && onSecondary != null) {
                TextButton(
                    onClick = onSecondary,
                    modifier = Modifier.padding(top = MonoDimens.spacingXs)
                ) {
                    Text(
                        secondaryLabel,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
