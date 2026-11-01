package app.diary.daily

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

class Palette(val bg: Color, val tx: Color, val mu: Color, val ac: Color)

fun palette(dark: Boolean) = if (dark) {
    Palette(Color(0xFF141311), Color(0xFFF1EDE7), Color(0xFF6B665F), Color(0xFFF09A89))
} else {
    Palette(Color(0xFFFAF8F5), Color(0xFF1D1B19), Color(0xFFB9B3AB), Color(0xFFD4705F))
}

@Composable
fun Modifier.press(onClick: () -> Unit): Modifier =
    this.clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        onClick = onClick,
    )

fun fmtTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

fun fmtNum(n: Int): String = n.toString().reversed().chunked(3).joinToString(" ").reversed()
