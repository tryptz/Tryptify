package tf.monochrome.android.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.appwidget.updateAll
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import kotlinx.coroutines.launch
import tf.monochrome.android.R
import tf.monochrome.android.ui.navigation.LocalBottomChromeInset
import tf.monochrome.android.ui.navigation.popBackStackSafe
import tf.monochrome.android.widget.GROUND
import tf.monochrome.android.widget.NowPlayingWidget
import tf.monochrome.android.widget.WidgetLook
import tf.monochrome.android.widget.WidgetStyle
import tf.monochrome.android.widget.WidgetStyleStore
import tf.monochrome.android.widget.resolveWidgetLook

/**
 * Settings › Appearance › Home screen widget: the widget's look, with a
 * preview drawn from the same [resolveWidgetLook] the widget uses, so what is
 * shown here is what lands on the home screen. Every change is saved and
 * pushed to the placed widgets at once; the slider saves when it is let go.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WidgetSettingsScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val saved by WidgetStyleStore.flow(context).collectAsStateWithLifecycle(initialValue = null)
    var dragOpacity by remember { mutableStateOf<Int?>(null) }

    fun save(style: WidgetStyle) {
        scope.launch {
            WidgetStyleStore.write(context, style)
            NowPlayingWidget().updateAll(context)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.settings_widget)) },
            navigationIcon = {
                IconButton(onClick = { navController.popBackStackSafe() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.settings_back))
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
        )
        // Nothing until the saved style is read, so the defaults never flash.
        val style = saved ?: return@Column
        val shown = style.copy(opacity = dragOpacity ?: style.opacity)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = LocalBottomChromeInset.current)
                .padding(horizontal = 20.dp),
        ) {
            SettingsGroupHeader(stringResource(R.string.widget_style_preview))
            WidgetPreview(shown)
            Spacer(Modifier.height(20.dp))

            SettingsGroupHeader(stringResource(R.string.widget_style_glass))
            Choice(
                options = WidgetStyle.Glass.entries,
                selected = style.glass,
                label = {
                    when (it) {
                        WidgetStyle.Glass.Light -> stringResource(R.string.widget_style_light)
                        WidgetStyle.Glass.Dark -> stringResource(R.string.widget_style_dark)
                        WidgetStyle.Glass.Album -> stringResource(R.string.widget_style_album)
                    }
                },
                onSelect = { save(style.copy(glass = it)) },
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.widget_style_opacity_value, shown.opacity),
                style = MaterialTheme.typography.bodyLarge,
            )
            Slider(
                value = shown.opacity.toFloat(),
                onValueChange = { dragOpacity = it.toInt() },
                onValueChangeFinished = {
                    dragOpacity?.let { save(style.copy(opacity = it)) }
                    dragOpacity = null
                },
                valueRange = WidgetStyle.MIN_OPACITY.toFloat()..WidgetStyle.MAX_OPACITY.toFloat(),
            )
            SettingSwitchItem(
                title = stringResource(R.string.widget_style_edge),
                subtitle = stringResource(R.string.widget_style_edge_summary),
                checked = style.edge,
                onCheckedChange = { save(style.copy(edge = it)) },
            )
            SettingSwitchItem(
                title = stringResource(R.string.widget_style_cover),
                subtitle = stringResource(R.string.widget_style_cover_summary),
                checked = style.showCover,
                onCheckedChange = { save(style.copy(showCover = it)) },
            )
            SettingSwitchItem(
                title = stringResource(R.string.widget_style_progress),
                subtitle = stringResource(R.string.widget_style_progress_summary),
                checked = style.showProgress,
                onCheckedChange = { save(style.copy(showProgress = it)) },
            )
            Spacer(Modifier.height(12.dp))

            SettingsGroupHeader(stringResource(R.string.widget_style_play))
            Choice(
                options = WidgetStyle.PlayButton.entries,
                selected = style.play,
                label = {
                    when (it) {
                        WidgetStyle.PlayButton.Solid -> stringResource(R.string.widget_style_play_solid)
                        WidgetStyle.PlayButton.Glass -> stringResource(R.string.widget_style_play_glass)
                    }
                },
                onSelect = { save(style.copy(play = it)) },
            )
            Spacer(Modifier.height(20.dp))

            Text(
                text = stringResource(R.string.widget_style_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (style != WidgetStyle.DEFAULT) {
                TextButton(onClick = { save(WidgetStyle.DEFAULT) }) {
                    Text(stringResource(R.string.settings_reset_to_default))
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> Choice(options: List<T>, selected: T, label: @Composable (T) -> String, onSelect: (T) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, option ->
            SegmentedButton(
                selected = option == selected,
                onClick = { onSelect(option) },
                shape = SegmentedButtonDefaults.itemShape(i, options.size),
            ) { Text(label(option), maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

/** The preview's stand-in cover, and so its album colour. Flat, like the widget. */
private val PreviewCover = Color(0xFFE8875A)

/**
 * The medium widget over a stand-in wallpaper: a dim ground with two flat
 * shapes, so the glass's opacity shows. Drawn in Compose, not Glance, from
 * the same [WidgetLook].
 */
@Composable
private fun WidgetPreview(style: WidgetStyle) {
    val look = resolveWidgetLook(style, PreviewCover.toArgb())
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(GROUND),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(170.dp).offset(x = (-90).dp, y = 40.dp).background(Color(0xFFE5643A), CircleShape))
        Box(Modifier.size(150.dp).offset(x = 100.dp, y = (-40).dp).background(Color(0xFF9C6ADE), CircleShape))
        val shape = RoundedCornerShape(24.dp)
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .fillMaxWidth()
                .height(150.dp)
                .clip(shape)
                .background(look.pane)
                .then(look.edge?.let { Modifier.border(1.dp, it, shape) } ?: Modifier)
                .padding(16.dp),
        ) {
            Row(Modifier.fillMaxWidth().weight(1f), verticalAlignment = Alignment.CenterVertically) {
                if (style.showCover) {
                    Box(Modifier.size(88.dp).clip(RoundedCornerShape(19.dp)).background(PreviewCover))
                    Spacer(Modifier.width(14.dp))
                }
                Column(Modifier.weight(1f).height(88.dp)) {
                    Text(
                        stringResource(R.string.app_name),
                        color = look.ink, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                    )
                    Text(
                        stringResource(R.string.widget_description),
                        color = look.inkSoft, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.weight(1f))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PreviewGlyph(R.drawable.ic_glass_skip_previous, look.ink, 40.dp)
                        Spacer(Modifier.width(6.dp))
                        Box(Modifier.size(44.dp).background(look.disc, CircleShape), contentAlignment = Alignment.Center) {
                            Icon(
                                painterResource(R.drawable.ic_glass_play),
                                contentDescription = null,
                                tint = look.onDisc,
                                modifier = Modifier.size(26.dp),
                            )
                        }
                        Spacer(Modifier.width(6.dp))
                        PreviewGlyph(R.drawable.ic_glass_skip_next, look.ink, 40.dp)
                    }
                }
            }
            if (style.showProgress) {
                Spacer(Modifier.height(12.dp))
                Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(look.track)) {
                    Box(Modifier.fillMaxWidth(0.42f).fillMaxHeight().background(look.ink))
                }
            }
        }
    }
}

@Composable
private fun PreviewGlyph(res: Int, tint: Color, size: Dp) {
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Icon(painterResource(res), contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.65f))
    }
}
