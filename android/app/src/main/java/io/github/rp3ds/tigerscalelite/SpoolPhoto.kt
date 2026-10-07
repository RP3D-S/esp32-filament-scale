package io.github.rp3ds.tigerscalelite

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp

/**
 * The photo the account's inventory holds for the spool on the platform. Shown under the scale screen
 * (which has room to spare) only while the scale reports a photo for the tag it is reading.
 */
@Composable
fun SpoolPhoto(s: ScaleState) {
    val photo by produceState<Bitmap?>(null, s.spoolImageUrl) {
        value = if (s.spoolImageUrl.isBlank()) null else AvatarLoader.load(s.spoolImageUrl, maxDim = 1024)
    }
    val bmp = photo ?: return
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp).clip(RoundedCornerShape(14.dp)),
        )
    }
}
