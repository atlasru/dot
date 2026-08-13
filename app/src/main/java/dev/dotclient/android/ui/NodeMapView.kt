package dev.dotclient.android.ui

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.hypot

data class NodeMapMarker(
    val countryCode: String,
    val latitude: Double,
    val longitude: Double,
    val nodeCount: Int,
    val active: Boolean,
)

private data class GeoPoint(val longitude: Double, val latitude: Double)

@Composable
fun NodeMapView(
    markers: List<NodeMapMarker>,
    onMarkerClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tapRadius = with(LocalDensity.current) { 28.dp.toPx() }

    Canvas(
        modifier.pointerInput(markers) {
            detectTapGestures { tap ->
                markers
                    .map { marker -> marker to project(marker.longitude, marker.latitude, size.width.toFloat(), size.height.toFloat()) }
                    .minByOrNull { (_, point) -> hypot((tap.x - point.x).toDouble(), (tap.y - point.y).toDouble()) }
                    ?.takeIf { (_, point) -> hypot((tap.x - point.x).toDouble(), (tap.y - point.y).toDouble()) <= tapRadius }
                    ?.first
                    ?.countryCode
                    ?.let(onMarkerClick)
            }
        },
    ) {
        drawRect(Color(0xFF070707))

        val grid = Color(0xFF171717)
        for (longitude in -150..150 step 30) {
            val x = project(longitude.toDouble(), 0.0, size.width, size.height).x
            drawLine(grid, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
        }
        for (latitude in -60..60 step 30) {
            val y = project(0.0, latitude.toDouble(), size.width, size.height).y
            drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
        }

        WORLD_SHAPES.forEach { polygon ->
            val path = Path()
            polygon.forEachIndexed { index, point ->
                val projected = project(point.longitude, point.latitude, size.width, size.height)
                if (index == 0) path.moveTo(projected.x, projected.y) else path.lineTo(projected.x, projected.y)
            }
            path.close()
            drawPath(path, Color(0xFF151515))
            drawPath(path, Color(0xFF2B2B2B), style = Stroke(width = 1.2f))
        }

        val countPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = android.graphics.Typeface.MONOSPACE
            isFakeBoldText = true
        }

        markers.forEach { marker ->
            val center = project(marker.longitude, marker.latitude, size.width, size.height)
            val radius = if (marker.nodeCount > 1) 9.dp.toPx() else 6.dp.toPx()
            val fill = if (marker.active) Color(0xFFFF2D2D) else Color(0xFFE5E5E5)
            drawCircle(Color(0xFF050505), radius + 2.dp.toPx(), center)
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

private fun project(longitude: Double, latitude: Double, width: Float, height: Float): Offset = Offset(
    x = (((longitude + 180.0) / 360.0) * width).toFloat(),
    y = (((90.0 - latitude) / 180.0) * height).toFloat(),
)

private val WORLD_SHAPES = listOf(
    listOf(
        GeoPoint(-168.0, 71.0), GeoPoint(-145.0, 70.0), GeoPoint(-125.0, 58.0), GeoPoint(-105.0, 52.0),
        GeoPoint(-85.0, 48.0), GeoPoint(-60.0, 52.0), GeoPoint(-52.0, 45.0), GeoPoint(-67.0, 26.0),
        GeoPoint(-82.0, 24.0), GeoPoint(-97.0, 18.0), GeoPoint(-110.0, 28.0), GeoPoint(-125.0, 42.0),
        GeoPoint(-145.0, 56.0),
    ),
    listOf(
        GeoPoint(-81.0, 12.0), GeoPoint(-68.0, 10.0), GeoPoint(-52.0, 2.0), GeoPoint(-35.0, -7.0),
        GeoPoint(-44.0, -24.0), GeoPoint(-56.0, -38.0), GeoPoint(-68.0, -55.0), GeoPoint(-77.0, -38.0),
        GeoPoint(-80.0, -16.0),
    ),
    listOf(
        GeoPoint(-11.0, 36.0), GeoPoint(2.0, 44.0), GeoPoint(20.0, 46.0), GeoPoint(33.0, 37.0),
        GeoPoint(42.0, 31.0), GeoPoint(51.0, 12.0), GeoPoint(44.0, -12.0), GeoPoint(32.0, -34.0),
        GeoPoint(18.0, -35.0), GeoPoint(5.0, -18.0), GeoPoint(-14.0, 5.0), GeoPoint(-17.0, 22.0),
    ),
    listOf(
        GeoPoint(-10.0, 36.0), GeoPoint(-5.0, 55.0), GeoPoint(8.0, 70.0), GeoPoint(30.0, 72.0),
        GeoPoint(55.0, 67.0), GeoPoint(90.0, 75.0), GeoPoint(130.0, 66.0), GeoPoint(170.0, 58.0),
        GeoPoint(178.0, 42.0), GeoPoint(155.0, 32.0), GeoPoint(140.0, 20.0), GeoPoint(118.0, 20.0),
        GeoPoint(106.0, 5.0), GeoPoint(92.0, 8.0), GeoPoint(78.0, 22.0), GeoPoint(60.0, 28.0),
        GeoPoint(45.0, 34.0), GeoPoint(32.0, 42.0), GeoPoint(15.0, 38.0),
    ),
    listOf(
        GeoPoint(112.0, -11.0), GeoPoint(132.0, -10.0), GeoPoint(153.0, -24.0), GeoPoint(146.0, -40.0),
        GeoPoint(125.0, -35.0), GeoPoint(113.0, -24.0),
    ),
    listOf(
        GeoPoint(-73.0, 83.0), GeoPoint(-22.0, 82.0), GeoPoint(-12.0, 69.0), GeoPoint(-44.0, 59.0),
        GeoPoint(-62.0, 66.0),
    ),
)
