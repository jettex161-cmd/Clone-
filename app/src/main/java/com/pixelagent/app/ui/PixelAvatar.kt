package com.pixelagent.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

enum class AvatarState { IDLE, THINKING, TALKING }

// 16x16 pixel sprite. Letters map to the palette below; '.' is transparent.
private val SPRITE = listOf(
    "....HHHHHHHH....",
    "..HHHHHHHHHHHH..",
    ".HHHHHHHHHHHHHH.",
    ".EEHhHHHHHHhHHH.",
    "hHHHSSSSSSSSHHHh",
    "hHHsSWESSWESsHHh",
    "dhHsSEeSSEeSsHhd",
    "dhHHSSSSSSSSHHhd",
    "dhHHSSSPPSSSHHhd",
    "hHHH.sSSSSs.HHHh",
    ".hHHcCCCCCCcHHh.",
    ".....CWccCC.....",
    ".....cccccc.....",
    "...bBBBBBBBBb...",
    "..BBBBBBBBBBBB..",
    ".BBBBBBBBBBBBBB."
)

private val PALETTE: Map<Char, Color> = mapOf(
    'H' to Color(0xFFE8E8F0), 'h' to Color(0xFFB8B8C8), 'd' to Color(0xFF808090),
    'S' to Color(0xFFFFF0E8), 's' to Color(0xFFE8D8D0),
    'E' to Color(0xFF00E5FF), 'e' to Color(0xFF006080), 'W' to Color(0xFFFFFFFF),
    'P' to Color(0xFFFFA0B8), 'p' to Color(0xFFFF6090), 'O' to Color(0xFF202030),
    'C' to Color(0xFF00A0C0), 'c' to Color(0xFF006080),
    'B' to Color(0xFF1A1A2E), 'b' to Color(0xFF2A2A3E)
)

private val PARTICLES = listOf(Color(0xFF00E5FF), Color(0xFFFF00E5), Color(0xFFFFFF00), Color(0xFF00FF88))

@Composable
fun PixelAvatar(modifier: Modifier = Modifier, state: AvatarState = AvatarState.IDLE, avatarSize: Dp = 64.dp) {
    val transition = rememberInfiniteTransition(label = "avatar")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Restart),
        label = "phase"
    )
    var blink by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(Random.nextLong(2500, 5500))
            blink = true
            delay(140)
            blink = false
        }
    }
    val talking = state == AvatarState.TALKING
    val thinking = state == AvatarState.THINKING

    Canvas(modifier = modifier.size(avatarSize)) {
        val s = this.size.minDimension
        val cell = s / 20f                       // sprite is 16 cells wide inside a 20-cell frame
        val pad = (s - cell * 16f) / 2f
        val bounce = (sin(phase * 2f * PI.toFloat()) * 0.5f + 0.5f) * cell * 0.9f
        val glow = 0.18f + 0.14f * (sin(phase * 2f * PI.toFloat() + 1f) * 0.5f + 0.5f)
        val cx = s / 2f
        val cy = s / 2f

        drawCircle(Color(0xFF00E5FF).copy(alpha = glow), radius = s * 0.46f, center = Offset(cx, cy))
        drawCircle(Color(0xFF00E5FF).copy(alpha = glow * 0.6f), radius = s * 0.49f, center = Offset(cx, cy), style = Stroke(width = 1.5f))

        val mouthOpen = talking && ((phase * 10f).toInt() % 2 == 0)
        for (y in SPRITE.indices) {
            val row = SPRITE[y]
            for (x in row.indices) {
                var ch = row[x]
                if (ch == '.') continue
                if (blink && (ch == 'W' || ch == 'E' || ch == 'e') && (y == 5 || y == 6)) {
                    ch = if (y == 5) 'S' else 'O'
                }
                if (ch == 'P' && mouthOpen) ch = 'p'
                val color = PALETTE[ch] ?: continue
                drawRect(color, Offset(pad + x * cell, pad + y * cell + bounce), Size(cell + 0.6f, cell + 0.6f))
            }
        }
        if (mouthOpen) {
            val c = PALETTE['p'] ?: Color.Red
            drawRect(c, Offset(pad + 7 * cell, pad + 9 * cell + bounce), Size(cell * 2f + 0.6f, cell + 0.6f))
        }

        // orbiting pixels; faster while thinking
        val speed = if (thinking) 3f else 1f
        for (i in 0 until 8) {
            val a = i * (2f * PI.toFloat() / 8f) + phase * 2f * PI.toFloat() * speed * (if (i % 2 == 0) 1f else -1f)
            val r = s * (0.44f + 0.04f * sin(phase * 2f * PI.toFloat() * 2f + i))
            val alpha = 0.35f + 0.5f * (sin(phase * 2f * PI.toFloat() * 3f + i * 1.7f) * 0.5f + 0.5f)
            val px = cx + cos(a) * r
            val py = cy + sin(a) * r * 0.95f
            drawRect(
                PARTICLES[i % PARTICLES.size].copy(alpha = alpha),
                Offset(px - cell * 0.4f, py - cell * 0.4f),
                Size(cell * 0.8f, cell * 0.8f)
            )
        }
    }
}
