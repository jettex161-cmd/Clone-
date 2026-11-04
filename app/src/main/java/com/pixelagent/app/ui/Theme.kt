package com.pixelagent.app.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

val Black = Color(0xFF0A0A0A)
val DarkGray = Color(0xFF141414)
val Panel = Color(0xFF1E1E1E)
val Panel2 = Color(0xFF2A2A2A)
val Gold = Color(0xFFFFD700)
val DarkGold = Color(0xFFB8860B)
val Cyan = Color(0xFF00E5FF)
val Danger = Color(0xFFFF5252)
val Ok = Color(0xFF3DDC84)
val Dim = Color(0xFF9E9E9E)

@Composable
fun PixelAgentTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Gold,
            onPrimary = Black,
            secondary = Cyan,
            onSecondary = Black,
            background = Black,
            onBackground = Color.White,
            surface = Panel,
            onSurface = Color.White,
            surfaceVariant = Panel2,
            onSurfaceVariant = Dim,
            error = Danger,
            outline = Color(0xFF555555)
        ),
        content = content
    )
}

@Composable
fun fieldColors(): TextFieldColors = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Cyan,
    unfocusedBorderColor = Color(0xFF555555),
    focusedLabelColor = Cyan,
    unfocusedLabelColor = Dim,
    cursorColor = Cyan,
    focusedTextColor = Color.White,
    unfocusedTextColor = Color.White
)

@Composable
fun SectionTitle(text: String) {
    Text(
        text,
        color = Gold,
        fontSize = 15.sp,
        modifier = Modifier.padding(top = 18.dp, bottom = 6.dp)
    )
}

@Composable
fun SwitchRow(title: String, desc: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 14.sp)
            if (desc.isNotEmpty()) Text(desc, color = Dim, fontSize = 12.sp, lineHeight = 16.sp)
        }
        Spacer(Modifier.size(8.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = Gold, checkedThumbColor = Black)
        )
    }
}

/** Decodes a downsampled thumbnail off the main thread. */
@Composable
fun Thumb(path: String, size: Dp, modifier: Modifier = Modifier) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, key1 = path) {
        value = withContext(Dispatchers.IO) {
            try {
                val f = File(path)
                if (!f.isFile) {
                    null
                } else {
                    val bounds = BitmapFactory.Options()
                    bounds.inJustDecodeBounds = true
                    BitmapFactory.decodeFile(path, bounds)
                    var sample = 1
                    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 300) sample *= 2
                    val o = BitmapFactory.Options()
                    o.inSampleSize = sample
                    BitmapFactory.decodeFile(path, o)?.asImageBitmap()
                }
            } catch (e: Throwable) {
                null
            }
        }
    }
    Box(
        modifier = modifier.size(size).clip(RoundedCornerShape(8.dp)).background(Panel2),
        contentAlignment = Alignment.Center
    ) {
        val b = bitmap
        if (b != null) {
            Image(bitmap = b, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(size))
        } else {
            Text("🖼", fontSize = 18.sp)
        }
    }
}

@Composable
fun Gap(h: Dp = 8.dp) {
    Spacer(Modifier.height(h))
}

@Composable
fun SpacedColumn(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}
