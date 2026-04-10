package tf.monochrome.android.ui.eq

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import tf.monochrome.android.ui.theme.MonoDimens

private data class TutorialStep(
    val title: String,
    val description: String,
    val icon: ImageVector,
    val drawableRes: String // resource name for future Gemini-generated asset
)

private val tutorialSteps = listOf(
    TutorialStep(
        title = "PRECISION AUTOEQ",
        description = "AutoEQ analyzes your headphone's frequency response and generates precise correction filters to match a professional target curve — bringing studio-grade sound to any headphone.",
        icon = Icons.Filled.GraphicEq,
        drawableRes = "tutorial_autoeq_concept"
    ),
    TutorialStep(
        title = "SELECT YOUR HEADPHONES",
        description = "Choose from our database of measured headphones, or upload your own frequency response measurement file for a custom correction tailored to your exact pair.",
        icon = Icons.Filled.Headphones,
        drawableRes = "tutorial_autoeq_select"
    ),
    TutorialStep(
        title = "REVIEW & APPLY",
        description = "Preview the generated EQ curve on the frequency graph, fine-tune individual bands if needed, then save as a preset. Your correction loads automatically on next launch.",
        icon = Icons.Filled.Tune,
        drawableRes = "tutorial_autoeq_apply"
    )
)

@Composable
fun AutoEqTutorialDialog(
    onDismiss: () -> Unit
) {
    var currentStep by remember { mutableIntStateOf(0) }
    val isLastStep = currentStep == tutorialSteps.size - 1

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .wrapContentHeight(),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = MonoDimens.cardAlpha),
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Step content with slide animation
                AnimatedContent(
                    targetState = currentStep,
                    transitionSpec = {
                        val direction = if (targetState > initialState) 1 else -1
                        (slideInHorizontally(
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioLowBouncy,
                                stiffness = Spring.StiffnessLow
                            )
                        ) { direction * it / 3 } + fadeIn(tween(200)))
                            .togetherWith(
                                slideOutHorizontally(
                                    animationSpec = tween(200)
                                ) { -direction * it / 3 } + fadeOut(tween(150))
                            )
                    },
                    label = "tutorial_step"
                ) { step ->
                    val tutorialStep = tutorialSteps[step]
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        // Icon with gradient background
                        val context = LocalContext.current
                        @Suppress("DiscouragedApi", "LocalContextResourcesRead")
                        val drawableId = context.resources.getIdentifier(
                            tutorialStep.drawableRes, "drawable", context.packageName
                        )

                        if (drawableId != 0) {
                            // Use Gemini-generated image asset
                            Image(
                                painter = painterResource(id = drawableId),
                                contentDescription = tutorialStep.title,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(180.dp)
                                    .clip(RoundedCornerShape(12.dp)),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            // Fallback: icon with gradient circle
                            Box(
                                modifier = Modifier
                                    .size(96.dp)
                                    .clip(CircleShape)
                                    .background(
                                        Brush.linearGradient(
                                            colors = listOf(
                                                MaterialTheme.colorScheme.primary,
                                                MaterialTheme.colorScheme.tertiary
                                            )
                                        )
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = tutorialStep.icon,
                                    contentDescription = null,
                                    modifier = Modifier.size(48.dp),
                                    tint = MaterialTheme.colorScheme.onPrimary
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(20.dp))

                        // Title
                        Text(
                            text = tutorialStep.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 1.5.sp,
                            color = MaterialTheme.colorScheme.primary,
                            textAlign = TextAlign.Center
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        // Description
                        Text(
                            text = tutorialStep.description,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            lineHeight = 20.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Dot indicators
                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    tutorialSteps.forEachIndexed { index, _ ->
                        val dotAlpha by animateFloatAsState(
                            targetValue = if (index == currentStep) 1f else 0.3f,
                            animationSpec = tween(300),
                            label = "dot_alpha_$index"
                        )
                        Box(
                            modifier = Modifier
                                .padding(horizontal = 4.dp)
                                .size(if (index == currentStep) 8.dp else 6.dp)
                                .clip(CircleShape)
                                .alpha(dotAlpha)
                                .background(MaterialTheme.colorScheme.primary)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Navigation buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(
                            "SKIP",
                            letterSpacing = 1.sp,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Row {
                        if (currentStep > 0) {
                            TextButton(onClick = { currentStep-- }) {
                                Text(
                                    "BACK",
                                    letterSpacing = 1.sp,
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                        }

                        TextButton(
                            onClick = {
                                if (isLastStep) onDismiss() else currentStep++
                            }
                        ) {
                            Text(
                                if (isLastStep) "GET STARTED" else "NEXT",
                                letterSpacing = 1.sp,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }
    }
}
