package dev.aetherx86.ferrite.ui.map

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** Where a map is looking. World units become pixels by scale, then offset. */
@Stable
class MapCamera(private val worldWidth: Float, private val worldHeight: Float, private val maxScale: Float) {
    var scale by mutableFloatStateOf(1f)
        private set
    var offsetX by mutableFloatStateOf(0f)
        private set
    var offsetY by mutableFloatStateOf(0f)
        private set
    private var viewport = Size.Zero
    private var fitted = false

    val minScale: Float
        get() = if (viewport.width <= 0f) 1f else minOf(viewport.width / worldWidth, viewport.height / worldHeight) * 0.92f

    fun attach(size: Size) {
        if (size == viewport) return
        viewport = size
        if (!fitted && size.width > 0f) {
            fit()
            fitted = true
        } else {
            clampIntoView()
        }
    }

    fun fit() {
        scale = minScale
        offsetX = (viewport.width - worldWidth * scale) / 2
        offsetY = (viewport.height - worldHeight * scale) / 2
    }

    fun x(world: Float) = world * scale + offsetX
    fun y(world: Float) = world * scale + offsetY

    fun worldLeft() = -offsetX / scale
    fun worldTop() = -offsetY / scale
    fun worldRight() = (viewport.width - offsetX) / scale
    fun worldBottom() = (viewport.height - offsetY) / scale

    fun panZoom(centroid: Offset, pan: Offset, zoom: Float) {
        val nextScale = (scale * zoom).coerceIn(minScale, maxScale)
        val applied = nextScale / scale
        offsetX = centroid.x - (centroid.x - offsetX) * applied + pan.x
        offsetY = centroid.y - (centroid.y - offsetY) * applied + pan.y
        scale = nextScale
        clampIntoView()
    }

    private fun clampIntoView() {
        val w = worldWidth * scale
        val h = worldHeight * scale
        val marginX = viewport.width * 0.4f
        val marginY = viewport.height * 0.4f
        offsetX = if (w + marginX * 2 < viewport.width) (viewport.width - w) / 2 else offsetX.coerceIn(viewport.width - w - marginX, marginX)
        offsetY = if (h + marginY * 2 < viewport.height) (viewport.height - h) / 2 else offsetY.coerceIn(viewport.height - h - marginY, marginY)
    }

    /** Shows a point at a given scale, right away. */
    fun jumpTo(worldX: Float, worldY: Float, targetScale: Float) {
        if (viewport.width <= 0f) return
        scale = targetScale.coerceIn(minScale, maxScale)
        offsetX = viewport.width / 2 - worldX * scale
        offsetY = viewport.height / 2 - worldY * scale
        clampIntoView()
    }

    /** Glides to a point at a given scale. */
    suspend fun flyTo(worldX: Float, worldY: Float, targetScale: Float, millis: Int = 520) {
        val startScale = scale
        val startX = (viewport.width / 2 - offsetX) / scale
        val startY = (viewport.height / 2 - offsetY) / scale
        val end = targetScale.coerceIn(minScale, maxScale)
        val progress = Animatable(0f)
        progress.animateTo(1f, tween(millis, easing = FastOutSlowInEasing)) {
            val t = value
            scale = startScale + (end - startScale) * t
            val cx = startX + (worldX - startX) * t
            val cy = startY + (worldY - startY) * t
            offsetX = viewport.width / 2 - cx * scale
            offsetY = viewport.height / 2 - cy * scale
        }
    }
}

fun Modifier.mapGestures(camera: MapCamera, onTap: (Offset) -> Unit): Modifier = this
    .pointerInput(camera) {
        detectTransformGestures { centroid, pan, zoom, _ -> camera.panZoom(centroid, pan, zoom) }
    }
    .pointerInput(camera) {
        coroutineScope {
            detectTapGestures(
                onDoubleTap = { at -> launch { zoomAt(camera, at) } },
                onTap = { onTap(it) },
            )
        }
    }

private suspend fun zoomAt(camera: MapCamera, at: Offset) {
    val worldX = (at.x - camera.offsetX) / camera.scale
    val worldY = (at.y - camera.offsetY) / camera.scale
    camera.flyTo(worldX, worldY, camera.scale * 2f, 260)
}
