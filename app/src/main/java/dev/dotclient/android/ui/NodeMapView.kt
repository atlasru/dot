package dev.dotclient.android.ui

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.tan

data class NodeMapMarker(
    val countryCode: String,
    val latitude: Double,
    val longitude: Double,
    val nodeCount: Int,
    val active: Boolean,
)

@Composable
fun NodeMapView(
    markers: List<NodeMapMarker>,
    onMarkerClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val tapRadius = with(LocalDensity.current) { 28.dp.toPx() }
    val geometryRepository = remember(context) { WorldMapGeometryRepository(context) }
    var countries by remember { mutableStateOf<List<WorldCountryShape>>(emptyList()) }
    var zoom by remember { mutableFloatStateOf(DEFAULT_ZOOM) }
    var viewportOffset by remember { mutableStateOf(Offset.Zero) }

    LaunchedEffect(geometryRepository) {
        geometryRepository.load().onSuccess { countries = it }
    }

    Canvas(
        modifier
            .pointerInput(Unit) {
                detectTransformGestures { centroid, pan, gestureZoom, _ ->
                    val oldZoom = zoom
                    val newZoom = (oldZoom * gestureZoom).coerceIn(MIN_ZOOM, MAX_ZOOM)
                    val ratio = newZoom / oldZoom
                    val canvasCenter = Offset(size.width / 2f, size.height / 2f)
                    val proposedOffset = viewportOffset + pan +
                        (centroid - canvasCenter - viewportOffset) * (1f - ratio)

                    zoom = newZoom
                    viewportOffset = clampViewportOffset(
                        proposedOffset,
                        Size(size.width.toFloat(), size.height.toFloat()),
                        newZoom,
                    )
                }
            }
            .pointerInput(markers, zoom, viewportOffset) {
                detectTapGestures(
                    onDoubleTap = {
                        zoom = DEFAULT_ZOOM
                        viewportOffset = Offset.Zero
                    },
                    onTap = { tap ->
                        markers
                            .map { marker ->
                                marker to viewportPoint(
                                    marker.longitude,
                                    marker.latitude,
                                    size.width.toFloat(),
                                    size.height.toFloat(),
                                    zoom,
                                    viewportOffset,
                                )
                            }
                            .minByOrNull { (_, point) ->
                                hypot((tap.x - point.x).toDouble(), (tap.y - point.y).toDouble())
                            }
                            ?.takeIf { (_, point) ->
                                hypot((tap.x - point.x).toDouble(), (tap.y - point.y).toDouble()) <= tapRadius
                            }
                            ?.first
                            ?.countryCode
                            ?.let(onMarkerClick)
                    },
                )
            },
    ) {
        drawWorldCountries(countries) { point ->
            viewportPoint(
                point.longitude,
                point.latitude,
                size.width,
                size.height,
                zoom,
                viewportOffset,
            )
        }

        if (countries.isEmpty()) {
            val grid = Color(0xFF121517)
            for (longitude in -120..120 step 40) {
                val start = viewportPoint(longitude.toDouble(), -80.0, size.width, size.height, zoom, viewportOffset)
                val end = viewportPoint(longitude.toDouble(), 80.0, size.width, size.height, zoom, viewportOffset)
                drawLine(grid, start, end, strokeWidth = 1f)
            }
            for (latitude in -60..60 step 30) {
                val start = viewportPoint(-180.0, latitude.toDouble(), size.width, size.height, zoom, viewportOffset)
                val end = viewportPoint(180.0, latitude.toDouble(), size.width, size.height, zoom, viewportOffset)
                drawLine(grid, start, end, strokeWidth = 1f)
            }
        }

        val countPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = android.graphics.Typeface.MONOSPACE
            isFakeBoldText = true
        }

        markers.forEach { marker ->
            val center = viewportPoint(
                marker.longitude,
                marker.latitude,
                size.width,
                size.height,
                zoom,
                viewportOffset,
            )
            val radius = if (marker.nodeCount > 1) 9.dp.toPx() else 6.dp.toPx()
            val fill = if (marker.active) Color(0xFFFF2D2D) else Color(0xFFE8E8E8)
            drawCircle(Color(0xFF030405), radius + 2.dp.toPx(), center)
            drawCircle(fill, radius, center)

            if (marker.nodeCount > 1) {
                countPaint.color = if (marker.active) android.graphics.Color.WHITE else android.graphics.Color.BLACK
                countPaint.textSize = 8.dp.toPx()
                val baseline = center.y - (countPaint.ascent() + countPaint.descent()) / 2f
                drawContext.canvas.nativeCanvas.drawText(
                    marker.nodeCount.coerceAtMost(99).toString(),
                    center.x,
                    baseline,
                    countPaint,
                )
            }
        }
    }
}

private fun viewportPoint(
    longitude: Double,
    latitude: Double,
    width: Float,
    height: Float,
    zoom: Float,
    viewportOffset: Offset,
): Offset {
    val base = project(longitude, latitude, width, height)
    val focus = project(DEFAULT_CENTER_LONGITUDE, DEFAULT_CENTER_LATITUDE, width, height)
    val canvasCenter = Offset(width / 2f, height / 2f)
    return canvasCenter + (base - focus) * zoom + viewportOffset
}

private fun clampViewportOffset(offset: Offset, size: Size, zoom: Float): Offset {
    val maxX = size.width * zoom * 0.46f
    val maxY = size.height * zoom * 0.46f
    return Offset(
        x = offset.x.coerceIn(-maxX, maxX),
        y = offset.y.coerceIn(-maxY, maxY),
    )
}

private fun project(longitude: Double, latitude: Double, width: Float, height: Float): Offset {
    val worldSize = width
    val top = (height - worldSize) / 2f
    val x = ((longitude + 180.0) / 360.0).coerceIn(0.0, 1.0)
    val safeLatitude = latitude.coerceIn(-MERCATOR_LIMIT, MERCATOR_LIMIT)
    val radians = Math.toRadians(safeLatitude)
    val y = (1.0 - ln(tan(radians) + 1.0 / cos(radians)) / PI) / 2.0
    return Offset(
        x = (x * worldSize).toFloat(),
        y = top + (y * worldSize).toFloat(),
    )
}

private const val MIN_ZOOM = 1.2f
private const val MAX_ZOOM = 5f
private const val DEFAULT_ZOOM = 1.5f
private const val DEFAULT_CENTER_LONGITUDE = 18.0
private const val DEFAULT_CENTER_LATITUDE = 38.0
private const val MERCATOR_LIMIT = 85.05112878
