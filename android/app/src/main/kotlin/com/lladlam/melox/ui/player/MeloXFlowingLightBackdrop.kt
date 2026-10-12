package com.lladlam.melox.ui.player

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.lladlam.melox.playback.MeloXAudioReactiveRuntime
import com.lladlam.melox.MeloXAppVisibility
import com.lladlam.melox.ui.settings.MeloXLyricsRenderingQuality
import com.lladlam.melox.ui.settings.MeloXSettingsRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.cos
import kotlin.math.sin

@Composable
internal fun MeloXBlurredArtworkBackdrop(
    artworkUrl: String?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val appInForeground = MeloXAppVisibility.isForeground
    val artworkModel = remember(context, artworkUrl) {
        ImageRequest.Builder(context).data(artworkUrl).size(320, 320).crossfade(220).build()
    }
    androidx.compose.foundation.layout.Box(modifier.fillMaxSize()) {
        AsyncImage(
            model = artworkModel,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = 1.18f; scaleY = 1.18f }.blur(38.dp),
        )
        Canvas(Modifier.fillMaxSize()) {
            drawRect(Color.Black.copy(alpha = .30f))
            drawRect(
                brush = Brush.verticalGradient(
                    listOf(Color.Black.copy(alpha = .05f), Color.Black.copy(alpha = .48f)),
                ),
            )
        }
    }
}

/**
 * Three slow artwork planes based on Apple Music's lyric background: 120s,
 * 90s and 70s linear rotations. Low quality deliberately keeps one plane to
 * avoid turning a lyric view into three full-screen blur passes.
 */
@Composable
internal fun MeloXLyricsArtworkBackdrop(
    artworkUrl: String?,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val appInForeground = MeloXAppVisibility.isForeground
    val quality = MeloXSettingsRuntime.lyricRenderingQuality
    val planeCount = when (quality) {
        MeloXLyricsRenderingQuality.Low -> 1
        MeloXLyricsRenderingQuality.Balanced -> 2
        MeloXLyricsRenderingQuality.High -> 3
    }
    val backgroundFrameRate = MeloXSettingsRuntime.lyricBackgroundFrameRate.coerceIn(15, 60)
    val saturation = if (MeloXSettingsRuntime.lyricReduceMotion) 3.5f else 2.5f
    val artworkColorFilter = remember(saturation) {
        ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(saturation) })
    }
    val latestIsPlaying by rememberUpdatedState(isPlaying)
    val elapsedWhilePlayingMs = remember(artworkUrl) { mutableLongStateOf(0L) }
    val artworkModel = remember(context, artworkUrl) {
        ImageRequest.Builder(context).data(artworkUrl).size(384, 384).crossfade(220).build()
    }

    // The source implementation invalidates its Canvas roughly every 42ms
    // (~24fps). Throttling here is intentional: three blurred planes at 60fps
    // make the lyric page visibly hotter without improving the slow motion.
    // A track change must keep redrawing briefly even while paused: the lyric
    // control surface samples this recorded layer, and a layer that stops
    // drawing keeps the previous track in the blurred controls.
    val settleUntilMs = remember(artworkUrl) { SystemClock.elapsedRealtime() + 1_600L }
    LaunchedEffect(planeCount, artworkUrl, backgroundFrameRate, appInForeground) {
        var previousFrameAt = SystemClock.elapsedRealtime()
        while (true) {
            val now = SystemClock.elapsedRealtime()
            if ((!latestIsPlaying && now >= settleUntilMs) || !appInForeground) {
                previousFrameAt = now
                delay(500L)
                continue
            }
            elapsedWhilePlayingMs.longValue += now - previousFrameAt
            previousFrameAt = now
            delay((1_000L / backgroundFrameRate.toLong()).coerceAtLeast(1L))
        }
    }

    androidx.compose.foundation.layout.Box(modifier.fillMaxSize()) {
        // Compose keeps only one bitmap generation and one set of full-screen
        // blur nodes alive during artwork changes.
        repeat(planeCount) { index ->
            AsyncImage(
                    model = artworkModel,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    colorFilter = artworkColorFilter,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = 1.34f
                            scaleY = 1.34f
                            val elapsed = elapsedWhilePlayingMs.longValue.toFloat()
                            val duration = when (index) {
                                0 -> 120_000f
                                1 -> 90_000f
                                else -> 70_000f
                            }
                            val direction = if (index == 0) -1f else 1f
                            rotationZ = direction * (elapsed % duration) / duration * 360f
                            translationX = (index - 1) * 34f
                            translationY = (1 - index) * 22f
                            alpha = if (index == 0) .48f else .28f
                        }
                        .blur(if (quality == MeloXLyricsRenderingQuality.High) 30.dp else 24.dp),
                )
        }
        Canvas(Modifier.fillMaxSize()) {
            drawRect(Color.Black.copy(alpha = .34f))
            drawRect(
                brush = Brush.verticalGradient(
                    listOf(Color.Transparent, Color.Black.copy(alpha = .52f)),
                ),
            )
        }
    }
}

/**
 * Android renderer for MeloX's artwork-driven Flowing Light background.
 * Palette extraction is identical in shape to ArtworkAccentColorProvider:
 * 160px downsample -> 3x3 cell averages. Android has no SwiftUI MeshGradient
 * equivalent on all supported API levels, so the nine control colors are laid
 * out as overlapping radial fields and then sampled through a time-varying
 * domain warp. The warp is what makes the field flow; the earlier version only
 * moved the field centers and read as breathing rather than motion.
 *
 * Brightness is fixed. When a real beat analysis is available its energy/beat
 * drive the warp speed and amplitude; otherwise the field drifts at a constant
 * speed from the synthetic playback clock.
 */
@Composable
internal fun MeloXFlowingLightBackdrop(
    artworkUrl: String?,
    isPlaying: Boolean,
    mediaId: String? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val appInForeground = MeloXAppVisibility.isForeground
    var targetPalette by remember { mutableStateOf(ArtworkDynamicPalette.Fallback) }
    val renderingQuality = MeloXSettingsRuntime.lyricRenderingQuality
    val backgroundFrameRate = MeloXSettingsRuntime.lyricBackgroundFrameRate.coerceIn(15, 60)
    val meshWidth = when (renderingQuality) {
        MeloXLyricsRenderingQuality.Low -> 24
        MeloXLyricsRenderingQuality.Balanced -> 32
        MeloXLyricsRenderingQuality.High -> 48
    }
    val meshHeight = meshWidth * 23 / 10
    val meshBitmaps = remember(meshWidth, meshHeight) {
        List(2) { Bitmap.createBitmap(meshWidth, meshHeight, Bitmap.Config.ARGB_8888) }
    }
    val meshImages = remember(meshBitmaps) { meshBitmaps.map(Bitmap::asImageBitmap) }
    var meshImage by remember(meshImages) { mutableStateOf(meshImages.first()) }
    val currentColors = remember(meshWidth, meshHeight) {
        MutableList(9) { index ->
            ArtworkDynamicPalette.Fallback.cells.getOrElse(index) { ArtworkDynamicPalette.Fallback.average }
        }
    }
    val currentAverage = remember(meshWidth, meshHeight) { arrayOf(ArtworkDynamicPalette.Fallback.average) }
    val phase = remember(meshWidth, meshHeight) { floatArrayOf(0f) }

    // Deliberately no recycle() on dispose. The render coroutine runs on a
    // background dispatcher and can still be inside setPixels() when this
    // composable leaves or the mesh size changes; recycling here raced with
    // that write and crashed with "Can't call setPixels() on a recycled
    // bitmap". The two bitmaps are tiny (<= 48x110), so the GC reclaims them.

    LaunchedEffect(artworkUrl) {
        targetPalette = ArtworkDynamicPaletteProvider.paletteFor(context, artworkUrl)
    }

    LaunchedEffect(isPlaying, artworkUrl, mediaId, renderingQuality, backgroundFrameRate, meshBitmaps, targetPalette, appInForeground) {
        val requestedFrameDelayMs = (1_000L / backgroundFrameRate.toLong()).coerceAtLeast(1L)
        val frameDelayMs = when (renderingQuality) {
            MeloXLyricsRenderingQuality.Low -> requestedFrameDelayMs.coerceAtLeast(50L)
            MeloXLyricsRenderingQuality.Balanced -> requestedFrameDelayMs.coerceAtLeast(33L)
            MeloXLyricsRenderingQuality.High -> requestedFrameDelayMs.coerceAtLeast(16L)
        }
        var energy = .18f
        var beatPulse = 0f
        var writeIndex = 1
        val pixels = IntArray(meshWidth * meshHeight)
        val centersX = FloatArray(currentColors.size)
        val centersY = FloatArray(currentColors.size)
        if (MeloXSettingsRuntime.reduceMotion) {
            currentColors.indices.forEach { index ->
                currentColors[index] = targetPalette.cells.getOrElse(index) { targetPalette.average }
            }
            currentAverage[0] = targetPalette.average
            withContext(Dispatchers.Default) {
                fillFlowingMeshPixels(
                    pixels = pixels,
                    meshWidth = meshWidth,
                    meshHeight = meshHeight,
                    colors = currentColors,
                    average = currentAverage[0],
                    phase = phase[0],
                    energy = energy,
                    beatPulse = beatPulse,
                    reactive = false,
                    saturation = MeloXSettingsRuntime.flowingLightSaturation.coerceIn(0f, 2f),
                    brightness = MeloXSettingsRuntime.flowingLightBrightness.coerceIn(.4f, 1.6f),
                    centersX = centersX,
                    centersY = centersY,
                )
                val bitmap = meshBitmaps[writeIndex]
                if (!bitmap.isRecycled) {
                    bitmap.setPixels(pixels, 0, meshWidth, 0, 0, meshWidth, meshHeight)
                }
            }
            meshImage = meshImages[writeIndex]
            awaitCancellation()
        }
        var lastRenderNanos = 0L
        while (true) {
            val frameNanos = withFrameNanos { it }
            if (!MeloXAppVisibility.isForeground) continue
            val effectiveDelayMs = if (com.lladlam.melox.playback.MeloXAudioAnalysisLoad.isBusy) {
                frameDelayMs.coerceAtLeast(66L)
            } else frameDelayMs
            if (lastRenderNanos != 0L && frameNanos - lastRenderNanos < effectiveDelayMs * 1_000_000L) continue
            val elapsedMs = if (lastRenderNanos == 0L) frameDelayMs.toFloat()
            else ((frameNanos - lastRenderNanos) / 1_000_000f).coerceIn(1f, 100f)
            lastRenderNanos = frameNanos
            val sample = MeloXAudioReactiveRuntime.sample(mediaId)
            // Freeze in step with the music. A backdrop bound to a track pauses
            // with it; pages that render this without a media id (playlist
            // covers, dead scenes) keep drifting.
            if (mediaId != null && !sample.isPlaying) continue
            // Shape follows the music only when a real beat analysis exists.
            // Otherwise the mesh drifts at a constant speed instead of tracking
            // the synthetic playback clock, so its brightness never pulses.
            val reactive = sample.hasAnalysis
            // 「减少动态效果」时不重算调色板（上游 0.6.2 7a5a7ef）。
            val animatePalette = !MeloXSettingsRuntime.reduceMotion
            energy += (sample.energy - energy) * .18f
            beatPulse += (sample.beat - beatPulse) * .32f
            val motion = if (reactive) {
                .021f + energy.coerceIn(0f, 1f) * .030f + beatPulse * .013f
            } else {
                ConstantFlowMotion
            }
            // 用户可调的速度倍率（设置 → 播放器外观 → 流动速度）。
            val speed = MeloXSettingsRuntime.flowingLightSpeed.coerceIn(.25f, 2f)
            phase[0] = (phase[0] + motion * speed * elapsedMs / (1_000f / 60f)) % FlowPhasePeriod
            if (animatePalette) {
                val paletteBlend = (elapsedMs / 280f).coerceIn(.08f, .45f)
                currentColors.indices.forEach { index ->
                    currentColors[index] = lerpColor(
                        currentColors[index],
                        targetPalette.cells.getOrElse(index) { targetPalette.average },
                        paletteBlend,
                    )
                }
                currentAverage[0] = lerpColor(currentAverage[0], targetPalette.average, paletteBlend)
            }
            val saturation = MeloXSettingsRuntime.flowingLightSaturation.coerceIn(0f, 2f)
            val brightness = MeloXSettingsRuntime.flowingLightBrightness.coerceIn(.4f, 1.6f)
            val bitmap = meshBitmaps[writeIndex]
            withContext(Dispatchers.Default) {
                fillFlowingMeshPixels(
                    pixels = pixels,
                    meshWidth = meshWidth,
                    meshHeight = meshHeight,
                    colors = currentColors,
                    average = currentAverage[0],
                    phase = phase[0],
                    energy = energy,
                    beatPulse = beatPulse,
                    reactive = reactive,
                    saturation = saturation,
                    brightness = brightness,
                    centersX = centersX,
                    centersY = centersY,
                )
                if (!bitmap.isRecycled) {
                    bitmap.setPixels(pixels, 0, meshWidth, 0, 0, meshWidth, meshHeight)
                }
            }
            meshImage = meshImages[writeIndex]
            writeIndex = 1 - writeIndex
        }
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        drawImage(
            image = meshImage,
            dstSize = IntSize(size.width.toInt().coerceAtLeast(1), size.height.toInt().coerceAtLeast(1)),
            filterQuality = FilterQuality.High,
        )

        // MeloX keeps the lower control region darker for white text/controls.
        drawRect(
            brush = Brush.verticalGradient(
                colorStops = arrayOf(
                    0f to Color.Black.copy(alpha = 0.04f),
                    0.52f to Color.Black.copy(alpha = 0.10f),
                    1f to Color.Black.copy(alpha = 0.48f),
                ),
            ),
        )
    }
}

private fun lerpColor(from: Color, to: Color, amount: Float): Color = Color(
    red = from.red + (to.red - from.red) * amount,
    green = from.green + (to.green - from.green) * amount,
    blue = from.blue + (to.blue - from.blue) * amount,
    alpha = 1f,
)

/** Constant angular speed (radians per 60 Hz frame) when no beat analysis exists. */
private const val ConstantFlowMotion = .032f

/**
 * Phase wrap point. Every time-varying term below multiplies the phase by a
 * multiple of a quarter turn, so wrapping at four full turns (8π) is exact and
 * the warp stays continuous instead of jumping when the phase rolls over.
 */
private const val FlowPhasePeriod = 25.132742f

/**
 * Base amplitude of the domain warp, in normalised screen units. The warp
 * displaces the sampling coordinate so the whole colour field advects, which
 * is what the previous symmetric radial fields could not do.
 */
private const val FlowWarpStrength = .15f

/**
 * Per-source travel frequencies. All are multiples of a quarter turn so the
 * 8π phase wrap in [fillFlowingMeshPixels] stays continuous. Different values
 * keep the nine colours from sweeping the field in lockstep.
 */
private val RoamFrequencyX = floatArrayOf(.50f, .25f, .75f, .50f, .25f, .75f, .50f, .25f, .75f)
private val RoamFrequencyY = floatArrayOf(.25f, .75f, .50f, .75f, .50f, .25f, .50f, .25f, .75f)

private fun fillFlowingMeshPixels(
    pixels: IntArray,
    meshWidth: Int,
    meshHeight: Int,
    colors: List<Color>,
    average: Color,
    phase: Float,
    energy: Float,
    beatPulse: Float,
    reactive: Boolean,
    saturation: Float,
    brightness: Float,
    centersX: FloatArray,
    centersY: FloatArray,
) {
    // Only a real beat analysis is allowed to shape the flow. With no analysis
    // the warp and the source orbit keep a fixed size while `phase` advances at
    // a constant speed, so the structure stays steady. Brightness is never
    // modulated here: only the field moves.
    val drive = if (reactive) 1f else 0f
    val energyDrive = energy.coerceIn(0f, 1f) * drive
    val beatDrive = beatPulse * drive
    // Kernels are sharper than before so the nine artwork colours actually read
    // as regions; a near-uniform field would swallow any warp.
    val radiusNormalized = (0.45f + energyDrive * .07f + beatDrive * .03f).coerceAtLeast(.01f)
    // Each colour source roams across the whole background on its own slow path
    // instead of orbiting its artwork cell, so the palette keeps visiting new
    // places. Frequencies are quarter-turn multiples so the 8π phase wrap stays
    // seamless (see FlowPhasePeriod).
    for (index in colors.indices) {
        val freqX = RoamFrequencyX[index]
        val freqY = RoamFrequencyY[index]
        centersX[index] = .5f + .3f * sin(phase * freqX + index * 1.7f)
        centersY[index] = .5f + .3f * cos(phase * freqY + index * .9f)
    }
    val maxDimension = maxOf(meshWidth, meshHeight).toFloat()
    val widthScale = meshWidth / maxDimension
    val heightScale = meshHeight / maxDimension
    val baseWeight = .16f
    val warp = FlowWarpStrength + energyDrive * .05f + beatDrive * .02f
    val t = phase
    var pixelIndex = 0
    for (yIndex in 0 until meshHeight) {
        val v = yIndex.toFloat() / (meshHeight - 1).coerceAtLeast(1).toFloat()
        for (xIndex in 0 until meshWidth) {
            val u = xIndex.toFloat() / (meshWidth - 1).coerceAtLeast(1).toFloat()
            // Two octaves of a slow curl-like field. Advancing `t` advects the
            // field, so the colours visibly flow instead of breathing in place.
            // Time multipliers are kept to multiples of a quarter turn so the
            // 8π phase wrap above is seamless (see FlowPhasePeriod).
            val warpX = sin(v * 3.1f + t) * cos(u * 2.3f - t * .75f) +
                .5f * sin((u + v) * 4.9f - t * 1.25f)
            val warpY = cos(u * 2.9f - t) * sin(v * 2.6f + t * .5f) +
                .5f * cos((u - v) * 5.4f + t)
            var sampleU = u + warp * warpX
            var sampleV = v + warp * warpY
            // A finer second pass gives the edges a marbled rather than a rigid
            // look.
            sampleU += warp * .28f * sin(sampleV * 6.7f - t * 1.5f)
            sampleV += warp * .28f * cos(sampleU * 7.1f + t * 1.5f)
            var totalWeight = baseWeight
            var red = average.red * baseWeight
            var green = average.green * baseWeight
            var blue = average.blue * baseWeight
            for (colorIndex in colors.indices) {
                val dx = (sampleU - centersX[colorIndex]) * widthScale / radiusNormalized
                val dy = (sampleV - centersY[colorIndex]) * heightScale / radiusNormalized
                val distanceSquared = dx * dx + dy * dy
                val falloff = 1f / (1f + distanceSquared * 4.5f)
                val weight = falloff * falloff
                val color = colors[colorIndex]
                totalWeight += weight
                red += color.red * weight
                green += color.green * weight
                blue += color.blue * weight
            }
            var r = red / totalWeight
            var g = green / totalWeight
            var b = blue / totalWeight
            // User colour controls. Intensity scales the chroma around the
            // pixel's own luminance so it never changes brightness; brightness
            // is the separate, explicit multiplier.
            if (saturation != 1f) {
                val luminance = r * .2126f + g * .7152f + b * .0722f
                r = luminance + (r - luminance) * saturation
                g = luminance + (g - luminance) * saturation
                b = luminance + (b - luminance) * saturation
            }
            if (brightness != 1f) {
                r *= brightness
                g *= brightness
                b *= brightness
            }
            pixels[pixelIndex++] = (0xFF shl 24) or
                ((r.coerceIn(0f, 1f) * 255f).toInt() shl 16) or
                ((g.coerceIn(0f, 1f) * 255f).toInt() shl 8) or
                (b.coerceIn(0f, 1f) * 255f).toInt()
        }
    }
}
