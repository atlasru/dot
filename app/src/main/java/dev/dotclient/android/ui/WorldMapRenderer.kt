package dev.dotclient.android.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

internal fun DrawScope.drawWorldCountries(
    countries: List<WorldCountryShape>,
    pointFor: (WorldGeoPoint) -> Offset,
) {
    drawRect(Color(0xFF060809))

    countries.forEach { country ->
        country.rings.forEach { ring ->
            if (ring.size < 3) return@forEach
            val path = Path()
            ring.forEachIndexed { index, point ->
                val projected = pointFor(point)
                if (index == 0) path.moveTo(projected.x, projected.y)
                else path.lineTo(projected.x, projected.y)
            }
            path.close()
            drawPath(path, Color(0xFF15191C))
            drawPath(
                path,
                Color(0xFF353B40),
                style = Stroke(width = 1.05.dp.toPx()),
            )
        }
    }
}
