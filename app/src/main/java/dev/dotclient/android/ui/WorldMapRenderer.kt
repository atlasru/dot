package dev.dotclient.android.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.abs

internal fun DrawScope.drawWorldBackdrop(
    pointFor: (WorldGeoPoint) -> Offset,
) {
    drawRect(
        brush = Brush.verticalGradient(
            listOf(
                Color(0xFF050607),
                Color(0xFF080B0D),
                Color(0xFF050607),
            ),
        ),
    )

    val minorGrid = Color(0xFF12171A)
    val majorGrid = Color(0xFF171D21)

    for (longitude in -150..150 step 30) {
        drawLine(
            color = if (longitude == 0) majorGrid else minorGrid,
            start = pointFor(WorldGeoPoint(longitude.toDouble(), -80.0)),
            end = pointFor(WorldGeoPoint(longitude.toDouble(), 80.0)),
            strokeWidth = if (longitude == 0) 0.85.dp.toPx() else 0.45.dp.toPx(),
        )
    }

    for (latitude in -60..60 step 20) {
        drawLine(
            color = if (latitude == 0) majorGrid else minorGrid,
            start = pointFor(WorldGeoPoint(-180.0, latitude.toDouble())),
            end = pointFor(WorldGeoPoint(180.0, latitude.toDouble())),
            strokeWidth = if (latitude == 0) 0.85.dp.toPx() else 0.45.dp.toPx(),
        )
    }
}

internal fun DrawScope.drawWorldCountries(
    countries: List<WorldCountryShape>,
    nodeCountryCodes: Set<String>,
    activeCountryCode: String?,
    pointFor: (WorldGeoPoint) -> Offset,
) {
    countries.forEachIndexed { index, country ->
        val code = country.countryCode
        val containsNodes = code != null && code in nodeCountryCodes
        val isActive = code != null && code == activeCountryCode

        val landFill = when {
            isActive -> Color(0xFF281316)
            containsNodes -> Color(0xFF1B2226)
            abs((code?.hashCode() ?: index) % 3) == 0 -> Color(0xFF12171A)
            abs((code?.hashCode() ?: index) % 3) == 1 -> Color(0xFF14191C)
            else -> Color(0xFF101518)
        }
        val border = when {
            isActive -> Color(0xFF8D3438)
            containsNodes -> Color(0xFF505A60)
            else -> Color(0xFF343C42)
        }

        country.rings.forEach { ring ->
            if (ring.size < 3) return@forEach
            val path = Path()
            ring.forEachIndexed { pointIndex, point ->
                val projected = pointFor(point)
                if (pointIndex == 0) path.moveTo(projected.x, projected.y)
                else path.lineTo(projected.x, projected.y)
            }
            path.close()

            drawPath(path, landFill)
            drawPath(
                path,
                border,
                style = Stroke(
                    width = when {
                        isActive -> 1.25.dp.toPx()
                        containsNodes -> 1.0.dp.toPx()
                        else -> 0.72.dp.toPx()
                    },
                ),
            )
        }
    }
}
