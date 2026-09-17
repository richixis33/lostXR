package com.samrat.cardboardhands

import android.view.KeyEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import zone.ien.hig.CupertinoText
import zone.ien.hig.theme.CupertinoTheme

/** One button on the drawing: where it sits and which key code Android reports for it. */
private data class Spot(val keyCode: Int, val label: String, val box: Rect)

/**
 * Schematic of a Joy-Con held sideways, the way it is used in VR.
 * Each button shows what it currently presses, lights up when held, and opens rebinding on tap.
 */
@Composable
fun JoyConDiagram(
    state: Settings.State,
    left: JoyConButtons.Live,
    right: JoyConButtons.Live,
    onPick: (keyCode: Int) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Side("Левый", true, state, left, onPick, Modifier.weight(1f, fill = true))
        Side("Правый", false, state, right, onPick, Modifier.weight(1f, fill = true))
    }
}

@Composable
private fun Side(
    title: String,
    isLeft: Boolean,
    state: Settings.State,
    live: JoyConButtons.Live,
    onPick: (keyCode: Int) -> Unit,
    modifier: Modifier
) {
    val measurer = rememberTextMeasurer()
    val scheme = CupertinoTheme.colorScheme
    val spots = spots(isLeft)
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        CupertinoText(
            "$title${if (live.connected) "" else " — не подключён"}",
            style = CupertinoTheme.typography.subhead,
            color = if (live.connected) scheme.label else scheme.secondaryLabel
        )
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.62f)
                .pointerInput(spots) {
                    detectTapGestures { tap ->
                        val point = Offset(tap.x / size.width, tap.y / size.height)
                        spots.firstOrNull { it.box.contains(point) }?.let { onPick(it.keyCode) }
                    }
                }
        ) {
            drawSide(
                spots, state, live,
                body = scheme.tertiarySystemFill,
                active = scheme.accent,
                onActive = Color.White,
                onSurface = scheme.label,
                outline = scheme.secondaryLabel,
                measurer = measurer
            )
            drawStick(live, scheme.secondaryLabel, scheme.accent, isLeft)
        }
    }
}

private fun DrawScope.drawSide(
    spots: List<Spot>,
    state: Settings.State,
    live: JoyConButtons.Live,
    body: Color,
    active: Color,
    onActive: Color,
    onSurface: Color,
    outline: Color,
    measurer: TextMeasurer
) {
    drawRoundRect(
        color = body,
        topLeft = Offset(size.width * .06f, size.height * .04f),
        size = Size(size.width * .88f, size.height * .92f),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.width * .18f)
    )
    spots.forEach { spot ->
        val index = Settings.KNOWN_KEYS.indexOf(spot.keyCode)
        val held = index >= 0 && live.rawKeys and (1 shl index) != 0
        val rect = androidx.compose.ui.geometry.Rect(
            Offset(spot.box.left * size.width, spot.box.top * size.height),
            Size(spot.box.width * size.width, spot.box.height * size.height)
        )
        drawRoundRect(
            color = if (held) active else outline.copy(alpha = .35f),
            topLeft = rect.topLeft,
            size = rect.size,
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(rect.height * .35f)
        )
        val action = state.bindings[spot.keyCode] ?: Settings.Action.NONE
        val caption = "${spot.label}\n${action.title}"
        val layout = measurer.measure(
            caption,
            style = TextStyle(
                fontSize = (size.width * .045f).toSp(),
                color = if (held) onActive else onSurface,
                textAlign = TextAlign.Center
            ),
            constraints = androidx.compose.ui.unit.Constraints(maxWidth = rect.width.toInt())
        )
        drawText(
            layout,
            topLeft = Offset(
                rect.left + (rect.width - layout.size.width) / 2f,
                rect.top + (rect.height - layout.size.height) / 2f
            )
        )
    }
}

private fun DrawScope.drawStick(live: JoyConButtons.Live, outline: Color, active: Color, isLeft: Boolean) {
    val center = Offset(size.width * .5f, size.height * (if (isLeft) .30f else .30f))
    val radius = size.width * .16f
    drawCircle(outline.copy(alpha = .35f), radius, center)
    // Stick values are already in the player's frame: x to the right, y forward.
    val dot = Offset(center.x + live.stickX * radius * .7f, center.y - live.stickY * radius * .7f)
    drawCircle(if (live.stickX != 0f || live.stickY != 0f) active else outline, radius * .35f, dot)
}

private fun spots(isLeft: Boolean): List<Spot> = if (isLeft) listOf(
    Spot(KeyEvent.KEYCODE_BUTTON_L2, "ZL", Rect(.10f, .05f, .48f, .12f)),
    Spot(KeyEvent.KEYCODE_BUTTON_L1, "L / SL", Rect(.52f, .05f, .90f, .12f)),
    Spot(KeyEvent.KEYCODE_BUTTON_THUMBL, "стик", Rect(.34f, .44f, .66f, .52f)),
    Spot(KeyEvent.KEYCODE_DPAD_UP, "↑", Rect(.36f, .56f, .64f, .64f)),
    Spot(KeyEvent.KEYCODE_DPAD_LEFT, "←", Rect(.10f, .66f, .46f, .74f)),
    Spot(KeyEvent.KEYCODE_DPAD_RIGHT, "→", Rect(.54f, .66f, .90f, .74f)),
    Spot(KeyEvent.KEYCODE_DPAD_DOWN, "↓", Rect(.36f, .76f, .64f, .84f)),
    Spot(KeyEvent.KEYCODE_BUTTON_SELECT, "−", Rect(.30f, .88f, .70f, .95f)),
) else listOf(
    Spot(KeyEvent.KEYCODE_BUTTON_R1, "R / SR", Rect(.10f, .05f, .48f, .12f)),
    Spot(KeyEvent.KEYCODE_BUTTON_R2, "ZR", Rect(.52f, .05f, .90f, .12f)),
    Spot(KeyEvent.KEYCODE_BUTTON_THUMBR, "стик", Rect(.34f, .44f, .66f, .52f)),
    Spot(KeyEvent.KEYCODE_BUTTON_X, "X", Rect(.36f, .56f, .64f, .64f)),
    Spot(KeyEvent.KEYCODE_BUTTON_Y, "Y", Rect(.10f, .66f, .46f, .74f)),
    Spot(KeyEvent.KEYCODE_BUTTON_A, "A", Rect(.54f, .66f, .90f, .74f)),
    Spot(KeyEvent.KEYCODE_BUTTON_B, "B", Rect(.36f, .76f, .64f, .84f)),
    Spot(KeyEvent.KEYCODE_BUTTON_START, "+", Rect(.30f, .88f, .70f, .95f)),
)
