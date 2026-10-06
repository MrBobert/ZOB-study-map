package com.zobstudy.airspace

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import kotlin.math.max
import kotlin.math.min

val ScopeBackground = Color(0xFF0A0E13)
val LowColor = Color(0xFF4CC38A)
val HighColor = Color(0xFF5AA9FF)
val ApproachColor = Color(0xFFFFB547)
val AirportColor = Color(0xFF8A94A3)
val MarkerColor = Color(0xFFFF5C7A)

fun layerColor(layer: Layer) = when (layer) {
    Layer.LOW -> LowColor
    Layer.HIGH -> HighColor
    Layer.APPROACH -> ApproachColor
}

fun layerName(layer: Layer) = when (layer) {
    Layer.LOW -> "Low"
    Layer.HIGH -> "High"
    Layer.APPROACH -> "Approach"
}

/** Pan/zoom state. Screen = world * scale + offset; world units come from [Airspace.projectX]/[Airspace.projectY]. */
class MapViewState(private val airspace: Airspace) {
    var scale by mutableFloatStateOf(0f)
    var offset by mutableStateOf(Offset.Zero)
    var size by mutableStateOf(IntSize.Zero)
    private var padPx = 48f

    fun toScreen(lat: Double, lon: Double) =
        Offset(airspace.projectX(lon) * scale + offset.x, airspace.projectY(lat) * scale + offset.y)

    fun toLatLon(p: Offset): Pair<Double, Double> {
        val x = (p.x - offset.x) / scale
        val y = (p.y - offset.y) / scale
        return airspace.unprojectLat(y) to airspace.unprojectLon(x)
    }

    fun fitLatLon(minLat: Double, maxLat: Double, minLon: Double, maxLon: Double) {
        if (size.width == 0 || size.height == 0) return
        val x0 = airspace.projectX(minLon); val x1 = airspace.projectX(maxLon)
        val y0 = airspace.projectY(maxLat); val y1 = airspace.projectY(minLat)
        val w = max(x1 - x0, 1e-4f); val h = max(y1 - y0, 1e-4f)
        val s = min((size.width - 2 * padPx) / w, (size.height - 2 * padPx) / h)
        scale = s
        offset = Offset(size.width / 2f - s * (x0 + x1) / 2f, size.height / 2f - s * (y0 + y1) / 2f)
    }

    fun fitAll() = fitLatLon(airspace.minLat, airspace.maxLat, airspace.minLon, airspace.maxLon)

    fun fitSector(sector: Sector) = fitLatLon(
        sector.volumes.minOf { it.minLat }, sector.volumes.maxOf { it.maxLat },
        sector.volumes.minOf { it.minLon }, sector.volumes.maxOf { it.maxLon },
    )

    fun transform(centroid: Offset, pan: Offset, zoom: Float) {
        if (scale == 0f) return
        val base = min(size.width, size.height).toFloat()
        val newScale = (scale * zoom).coerceIn(base / 20f, base * 4f)
        val z = newScale / scale
        offset = (offset - centroid) * z + centroid + pan
        scale = newScale
    }

    /** Pan so the point is on screen, leaving room for the bottom panel. */
    fun ensureVisible(lat: Double, lon: Double) {
        val p = toScreen(lat, lon)
        val margin = padPx
        val bottomLimit = size.height * 0.55f
        if (p.x < margin || p.x > size.width - margin || p.y < margin || p.y > bottomLimit) {
            offset += Offset(size.width / 2f - p.x, size.height * 0.3f - p.y)
        }
    }
}

@Composable
fun AirspaceMap(
    airspace: Airspace,
    view: MapViewState,
    visible: Set<Layer>,
    showAirports: Boolean,
    showLabels: Boolean,
    focusId: String?,
    highlightIds: Set<String>,
    marker: Pair<Double, Double>?,
    onTap: (Double, Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    val paths = remember(airspace) {
        airspace.volumes.associateWith { v ->
            Path().apply {
                for (i in v.lats.indices) {
                    val x = airspace.projectX(v.lons[i]); val y = airspace.projectY(v.lats[i])
                    if (i == 0) moveTo(x, y) else lineTo(x, y)
                }
                close()
            }
        }
    }
    // One label per sector per layer, on its largest piece.
    val labelVolumes = remember(airspace) {
        airspace.volumes.groupBy { it.sectorId to it.layer }.values.map { vs -> vs.maxBy { it.area } }
    }
    val textMeasurer = rememberTextMeasurer()
    val currentOnTap by rememberUpdatedState(onTap)

    Canvas(
        modifier = modifier
            .onSizeChanged {
                val first = view.size == IntSize.Zero
                view.size = it
                if (first || view.scale == 0f) view.fitAll()
            }
            .pointerInput(view) {
                detectTransformGestures { centroid, pan, zoom, _ -> view.transform(centroid, pan, zoom) }
            }
            .pointerInput(view) {
                detectTapGestures(
                    onDoubleTap = { p -> view.transform(p, Offset.Zero, 2f) },
                    onTap = { p ->
                        if (view.scale != 0f) {
                            val (lat, lon) = view.toLatLon(p)
                            currentOnTap(lat, lon)
                        }
                    },
                )
            },
    ) {
        drawRect(ScopeBackground)
        if (view.scale == 0f) return@Canvas
        val s = view.scale
        val px = 1f / s // one screen pixel in world units
        val focusing = focusId != null

        withTransform({
            translate(view.offset.x, view.offset.y)
            scale(s, s, pivot = Offset.Zero)
        }) {
            // Fills first so every border stays on top.
            for (v in airspace.volumes) {
                val color = layerColor(v.layer)
                val path = paths.getValue(v)
                when {
                    v.sectorId == focusId -> drawPath(path, color.copy(alpha = 0.22f), style = Fill)
                    v.sectorId in highlightIds && (v.layer in visible || focusing) ->
                        drawPath(path, color.copy(alpha = 0.16f), style = Fill)
                }
            }
            // Draw order: approach under low under high, so high dashes read on top.
            val order = listOf(Layer.APPROACH, Layer.LOW, Layer.HIGH)
            for (layer in order) {
                for (v in airspace.volumes) {
                    if (v.layer != layer) continue
                    val isFocus = v.sectorId == focusId
                    if (!isFocus && layer !in visible) continue
                    val color = layerColor(layer)
                    val alpha = when {
                        isFocus -> 1f
                        focusing -> 0.28f
                        else -> 0.9f
                    }
                    val width = (if (isFocus) 3f else 1.4f) * density * px
                    val effect = if (layer == Layer.HIGH) {
                        PathEffect.dashPathEffect(floatArrayOf(7f * density * px, 4f * density * px))
                    } else null
                    drawPath(
                        paths.getValue(v),
                        color.copy(alpha = alpha),
                        style = Stroke(width = width, pathEffect = effect),
                    )
                }
            }
        }

        if (showAirports) {
            for (a in airspace.airports) {
                val p = view.toScreen(a.lat, a.lon)
                if (!onScreen(p)) continue
                drawCircle(AirportColor, radius = 2.2f * density, center = p)
                if (s > size.minDimension / 4f) {
                    drawLabel(textMeasurer, a.id, p + Offset(0f, 9f * density), AirportColor, 9f, bold = false)
                }
            }
        }

        if (showLabels) {
            for (v in labelVolumes) {
                val isFocus = v.sectorId == focusId
                if (!isFocus && v.layer !in visible) continue
                val sector = airspace.byId[v.sectorId] ?: continue
                val p = view.toScreen(v.labelLat, v.labelLon)
                if (!onScreen(p)) continue
                val alpha = if (focusing && !isFocus) 0.35f else 1f
                drawLabel(
                    textMeasurer, sector.short, p, layerColor(v.layer).copy(alpha = alpha),
                    if (isFocus) 14f else 11f, bold = true,
                )
            }
        }

        marker?.let { (lat, lon) ->
            val p = view.toScreen(lat, lon)
            val r = 9f * density
            drawCircle(MarkerColor, radius = r, center = p, style = Stroke(width = 2f * density))
            drawLine(MarkerColor, p - Offset(r * 1.8f, 0f), p - Offset(r * 0.6f, 0f), strokeWidth = 2f * density)
            drawLine(MarkerColor, p + Offset(r * 0.6f, 0f), p + Offset(r * 1.8f, 0f), strokeWidth = 2f * density)
            drawLine(MarkerColor, p - Offset(0f, r * 1.8f), p - Offset(0f, r * 0.6f), strokeWidth = 2f * density)
            drawLine(MarkerColor, p + Offset(0f, r * 0.6f), p + Offset(0f, r * 1.8f), strokeWidth = 2f * density)
            drawCircle(MarkerColor, radius = 2f * density, center = p)
        }
    }
}

private fun DrawScope.onScreen(p: Offset) =
    p.x > -40f && p.y > -40f && p.x < size.width + 40f && p.y < size.height + 40f

private fun DrawScope.drawLabel(
    measurer: TextMeasurer,
    text: String,
    center: Offset,
    color: Color,
    sizeSp: Float,
    bold: Boolean,
) {
    val layout = measurer.measure(
        text,
        TextStyle(
            color = color,
            fontSize = sizeSp.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        ),
    )
    drawText(
        layout,
        topLeft = Offset(center.x - layout.size.width / 2f, center.y - layout.size.height / 2f),
    )
}
