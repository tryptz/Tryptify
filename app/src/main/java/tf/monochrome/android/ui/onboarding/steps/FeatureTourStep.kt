package tf.monochrome.android.ui.onboarding.steps

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import tf.monochrome.android.ui.theme.MonoDimens
import androidx.compose.ui.res.stringResource
import tf.monochrome.android.R

private data class TourSlide(
    val icon: ImageVector,
    @androidx.annotation.StringRes val title: Int,
    @androidx.annotation.StringRes val text: Int,
)

private val tourSlides = listOf(
    TourSlide(Icons.Default.Tune, R.string.tour_sound_title, R.string.tour_sound_text),
    TourSlide(Icons.Default.Equalizer, R.string.tour_dsp_title, R.string.tour_dsp_text),
    TourSlide(Icons.Default.HighQuality, R.string.tour_hires_title, R.string.tour_hires_text),
    TourSlide(Icons.Default.Radio, R.string.tour_radio_title, R.string.tour_radio_text),
)

/** Swipeable 4-slide feature tour; the button reads Next until the last slide. */
@Composable
fun FeatureTourStep(onDone: () -> Unit) {
    val pagerState = rememberPagerState(pageCount = { tourSlides.size })
    val scope = rememberCoroutineScope()
    val onLastPage = pagerState.currentPage == tourSlides.lastIndex

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = MonoDimens.spacingXl)
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) { page ->
            val slide = tourSlides[page]
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = slide.icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(72.dp)
                )
                Text(
                    text = stringResource(slide.title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = MonoDimens.spacingLg)
                )
                Text(
                    text = stringResource(slide.text),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(
                        top = MonoDimens.spacingSm,
                        start = MonoDimens.spacingLg,
                        end = MonoDimens.spacingLg
                    )
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = MonoDimens.spacingLg),
            horizontalArrangement = Arrangement.Center
        ) {
            repeat(tourSlides.size) { index ->
                Box(
                    modifier = Modifier
                        .padding(horizontal = 3.dp)
                        .size(6.dp)
                        .background(
                            color = if (index == pagerState.currentPage) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f)
                            },
                            shape = MonoDimens.shapeCircle
                        )
                )
            }
        }

        Button(
            onClick = {
                if (onLastPage) onDone()
                else scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
            },
            shape = MonoDimens.shapePill,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
        ) {
            Text(
                text = if (onLastPage) stringResource(R.string.action_done) else stringResource(R.string.action_next),
                style = MaterialTheme.typography.titleMedium
            )
        }
        androidx.compose.foundation.layout.Spacer(
            modifier = Modifier.height(MonoDimens.spacingLg)
        )
    }
}
