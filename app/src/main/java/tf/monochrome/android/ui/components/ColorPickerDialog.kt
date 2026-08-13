package tf.monochrome.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import tf.monochrome.android.ui.theme.MonoDimens
import kotlin.math.roundToInt

/**
 * Pick one colour, by hue and by a saturation/value square.
 *
 * Hand-built rather than pulled from a library: the app takes one dependency per
 * job it cannot do itself, and an HSV picker is a gradient, a strip and two
 * draggable dots. Alpha is deliberately absent — the two things this picks, a
 * theme's accent and its ground, are both opaque by definition, and a
 * half-transparent background is a bug, not a choice.
 *
 * The hex field and the sliders are the same value read two ways, so typing
 * `1DB954` moves the dots and dragging the dots rewrites the hex.
 */
@Composable
fun ColorPickerDialog(
    initial: Color,
    title: String,
    onDismiss: () -> Unit,
    onConfirm: (Color) -> Unit,
) {
    val hsv = remember { FloatArray(3) }
    remember(initial) {
        android.graphics.Color.colorToHSV(initial.toArgb(), hsv)
        true
    }
    var hue by remember { mutableStateOf(hsv[0]) }
    var sat by remember { mutableStateOf(hsv[1]) }
    var value by remember { mutableStateOf(hsv[2]) }

    val current = Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, value)))

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(20.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(16.dp))

            // Saturation (x) against value (y), over the current hue.
            SaturationValueField(
                hue = hue,
                saturation = sat,
                value = value,
                onChange = { s, v -> sat = s; value = v },
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1.4f)
                    .clip(RoundedCornerShape(12.dp)),
            )

            Spacer(Modifier.height(16.dp))
            HueStrip(
                hue = hue,
                onChange = { hue = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(28.dp)
                    .clip(CircleShape),
            )

            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(current)
                        .border(
                            1.dp,
                            MaterialTheme.colorScheme.outline,
                            RoundedCornerShape(10.dp),
                        ),
                )
                Spacer(Modifier.size(12.dp))
                HexField(
                    color = current,
                    onColor = { c ->
                        android.graphics.Color.colorToHSV(c.toArgb(), hsv)
                        hue = hsv[0]; sat = hsv[1]; value = hsv[2]
                    },
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(Modifier.height(20.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Spacer(Modifier.size(8.dp))
                TextButton(onClick = { onConfirm(current) }) { Text("Select") }
            }
        }
    }
}

/** The saturation/value square, with a draggable thumb over it. */
@Composable
private fun SaturationValueField(
    hue: Float,
    saturation: Float,
    value: Float,
    onChange: (sat: Float, value: Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    val hueColor = Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, 1f, 1f)))

    fun report(offset: Offset) {
        if (size.width == 0 || size.height == 0) return
        val s = (offset.x / size.width).coerceIn(0f, 1f)
        val v = 1f - (offset.y / size.height).coerceIn(0f, 1f)
        onChange(s, v)
    }

    Box(
        modifier = modifier
            .onSizeChanged { size = it }
            // White → hue across x, then transparent → black down y, which is
            // exactly the HSV square: left edge greyscale, top-right the pure hue.
            .background(Brush.horizontalGradient(listOf(Color.White, hueColor)))
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
            .pointerInput(hue) {
                detectTapGestures { report(it) }
            }
            .pointerInput(hue) {
                detectDragGestures { change, _ -> report(change.position) }
            },
    ) {
        Box(
            modifier = Modifier
                .graphicsLayer {
                    translationX = saturation * size.width - 9.dp.toPx()
                    translationY = (1f - value) * size.height - 9.dp.toPx()
                }
                .size(18.dp)
                .border(2.dp, Color.White, CircleShape)
                .border(3.dp, Color.Black.copy(alpha = 0.35f), CircleShape),
        )
    }
}

/** The rainbow hue strip, with a draggable thumb. */
@Composable
private fun HueStrip(
    hue: Float,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    var width by remember { mutableStateOf(0) }
    val spectrum = remember {
        (0..360 step 30).map { Color(android.graphics.Color.HSVToColor(floatArrayOf(it.toFloat(), 1f, 1f))) }
    }

    fun report(x: Float) {
        if (width == 0) return
        onChange((x / width).coerceIn(0f, 1f) * 360f)
    }

    Box(
        modifier = modifier
            .onSizeChanged { width = it.width }
            .background(Brush.horizontalGradient(spectrum))
            .pointerInput(Unit) { detectTapGestures { report(it.x) } }
            .pointerInput(Unit) { detectDragGestures { change, _ -> report(change.position.x) } },
    ) {
        Box(
            modifier = Modifier
                .graphicsLayer { translationX = (hue / 360f) * width - 8.dp.toPx() }
                .padding(vertical = 2.dp)
                .size(width = 16.dp, height = 24.dp)
                .border(2.dp, Color.White, RoundedCornerShape(6.dp))
                .border(3.dp, Color.Black.copy(alpha = 0.3f), RoundedCornerShape(6.dp)),
        )
    }
}

/** `#RRGGBB`, typed or shown. Rejects anything that isn't six hex digits. */
@Composable
private fun HexField(
    color: Color,
    onColor: (Color) -> Unit,
    modifier: Modifier = Modifier,
) {
    val hex = remember(color) { "#" + color.toArgb().and(0xFFFFFF).toString(16).padStart(6, '0').uppercase() }
    var text by remember(hex) { mutableStateOf(hex) }
    val keyboard = LocalSoftwareKeyboardController.current

    fun commit() {
        val cleaned = text.removePrefix("#").trim()
        if (cleaned.length == 6) {
            runCatching { android.graphics.Color.parseColor("#$cleaned") }
                .getOrNull()
                ?.let { onColor(Color(it)) }
        }
        keyboard?.hide()
    }

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = text,
            onValueChange = { text = it.take(7) },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(
                color = MaterialTheme.colorScheme.onSurface,
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = KeyboardType.Ascii,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { commit() }),
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { commit() }) { Text("Set") }
    }
}

/** Compose's [Color] to a packed ARGB int, the form the store and HSV want. */
private fun Color.toArgb(): Int = android.graphics.Color.argb(
    (alpha * 255).roundToInt(),
    (red * 255).roundToInt(),
    (green * 255).roundToInt(),
    (blue * 255).roundToInt(),
)

/**
 * A tappable colour swatch with its label — the row that opens the picker.
 *
 * Shows the colour it holds, and enough of a border that a swatch the same
 * colour as the surface it sits on is still a shape rather than a hole.
 */
@Composable
fun ColorSwatchRow(
    label: String,
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .pointerInput(Unit) { detectTapGestures { onClick() } }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        val hex = "#" + (color.toArgb() and 0xFFFFFF).toString(16).padStart(6, '0').uppercase()
        Text(
            hex,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 12.dp),
        )
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(color)
                .border(
                    width = 1.dp,
                    color = if (color.luminance() > 0.5f) {
                        MaterialTheme.colorScheme.outline
                    } else {
                        Color.White.copy(alpha = 0.4f)
                    },
                    shape = CircleShape,
                ),
        )
    }
}
