package com.ahjin.pr.ui

import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import com.ahjin.pr.Layout

/**
 * The pictures the buttons wear in the game (`assets/icons`, the same ones the engine has built in),
 * by name. They are pixel art, so they are scaled without smoothing. A picture that cannot be read is
 * left out; the editor then shows the button's letter.
 */
@Composable
fun rememberButtonPictures(): Map<String, Painter> {
    val context = LocalContext.current.applicationContext
    return remember {
        Layout.PICTURES.mapNotNull { name ->
            val bitmap = runCatching { context.assets.open("icons/$name.png").use { BitmapFactory.decodeStream(it) } }.getOrNull()
            bitmap?.let { name to BitmapPainter(it.asImageBitmap(), filterQuality = FilterQuality.None) }
        }.toMap()
    }
}
