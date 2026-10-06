package com.zobstudy.airspace

import android.content.Context
import org.json.JSONObject
import kotlin.math.cos
import kotlin.math.abs

enum class Layer { LOW, HIGH, APPROACH }

class Volume(
    val sectorId: String,
    val layer: Layer,
    val floor: Int,
    val ceiling: Int,
    val lats: DoubleArray,
    val lons: DoubleArray,
    val labelLat: Double,
    val labelLon: Double,
) {
    val minLat = lats.min()
    val maxLat = lats.max()
    val minLon = lons.min()
    val maxLon = lons.max()

    /** Approximate area in squared degrees (only used to pick which piece gets a label). */
    val area: Double = run {
        var a = 0.0
        for (i in lats.indices) {
            val j = if (i == 0) lats.size - 1 else i - 1
            a += lons[j] * lats[i] - lons[i] * lats[j]
        }
        abs(a) / 2
    }

    fun contains(lat: Double, lon: Double): Boolean {
        if (lat < minLat || lat > maxLat || lon < minLon || lon > maxLon) return false
        var inside = false
        val n = lats.size
        var j = n - 1
        for (i in 0 until n) {
            val yi = lats[i]
            val yj = lats[j]
            if ((yi > lat) != (yj > lat)) {
                val x = lons[i] + (lat - yi) * (lons[j] - lons[i]) / (yj - yi)
                if (lon < x) inside = !inside
            }
            j = i
        }
        return inside
    }
}

class Sector(
    val id: String,
    val name: String,
    val short: String,
    val isApproach: Boolean,
    val area: String,
    val volumes: List<Volume>,
) {
    val layers: Set<Layer> = volumes.map { it.layer }.toSet()
}

class Airport(val id: String, val lat: Double, val lon: Double)

/** One owner of airspace over a point: a sector and an altitude band (hundreds of feet). */
data class Stratum(val sectorId: String, val floor: Int, val ceiling: Int, val layer: Layer)

class Airspace(
    val sectors: List<Sector>,
    val airports: List<Airport>,
    val altitudes: List<Int>,
    val source: String,
    val cycle: String,
) {
    val byId: Map<String, Sector> = sectors.associateBy { it.id }
    val volumes: List<Volume> = sectors.flatMap { it.volumes }

    val minLat = volumes.minOf { it.minLat }
    val maxLat = volumes.maxOf { it.maxLat }
    val minLon = volumes.minOf { it.minLon }
    val maxLon = volumes.maxOf { it.maxLon }

    /** Reference point for the flat map projection. */
    val lat0 = (minLat + maxLat) / 2
    val lon0 = (minLon + maxLon) / 2
    val cosLat0 = cos(Math.toRadians(lat0))

    fun projectX(lon: Double) = ((lon - lon0) * cosLat0).toFloat()
    fun projectY(lat: Double) = (lat0 - lat).toFloat()
    fun unprojectLon(x: Float) = x / cosLat0 + lon0
    fun unprojectLat(y: Float) = lat0 - y

    /**
     * Who owns the airspace over a point, bottom to top.
     *
     * Approach controls own their delegated volumes; a center sector owns its
     * volumes minus any approach airspace stacked inside them. Center sectors
     * don't overlap each other in this data set, so no other tie-break is needed.
     */
    fun lookup(lat: Double, lon: Double): List<Stratum> {
        val hits = volumes.filter { it.contains(lat, lon) }
        if (hits.isEmpty()) return emptyList()

        val approach = hits.filter { it.layer == Layer.APPROACH }
        val approachBands = merge(approach.map { it.floor to it.ceiling })
        val result = mutableListOf<Stratum>()

        approach.groupBy { it.sectorId }.forEach { (id, vols) ->
            merge(vols.map { it.floor to it.ceiling }).forEach { (lo, hi) ->
                result += Stratum(id, lo, hi, Layer.APPROACH)
            }
        }
        hits.filter { it.layer != Layer.APPROACH }
            .groupBy { it.sectorId to it.layer }
            .forEach { (key, vols) ->
                val own = merge(vols.map { it.floor to it.ceiling })
                subtract(own, approachBands).forEach { (lo, hi) ->
                    result += Stratum(key.first, lo, hi, key.second)
                }
            }
        return result.sortedWith(compareBy({ it.floor }, { it.ceiling }, { it.sectorId }))
    }

    companion object {
        fun load(context: Context): Airspace {
            val text = context.assets.open("airspace.json").bufferedReader().use { it.readText() }
            val root = JSONObject(text)
            val sectors = mutableListOf<Sector>()
            val arr = root.getJSONArray("sectors")
            for (i in 0 until arr.length()) {
                val s = arr.getJSONObject(i)
                val id = s.getString("id")
                val vols = mutableListOf<Volume>()
                val va = s.getJSONArray("volumes")
                for (k in 0 until va.length()) {
                    val v = va.getJSONObject(k)
                    val pts = v.getJSONArray("points")
                    val lats = DoubleArray(pts.length()) { pts.getJSONArray(it).getDouble(0) }
                    val lons = DoubleArray(pts.length()) { pts.getJSONArray(it).getDouble(1) }
                    val label = v.optJSONArray("label")
                    vols += Volume(
                        sectorId = id,
                        layer = when (v.getString("layer")) {
                            "high" -> Layer.HIGH
                            "approach" -> Layer.APPROACH
                            else -> Layer.LOW
                        },
                        floor = v.getInt("floor"),
                        ceiling = v.getInt("ceiling"),
                        lats = lats,
                        lons = lons,
                        labelLat = label?.getDouble(0) ?: lats.average(),
                        labelLon = label?.getDouble(1) ?: lons.average(),
                    )
                }
                sectors += Sector(
                    id = id,
                    name = s.getString("name"),
                    short = s.getString("short"),
                    isApproach = s.getString("type") == "approach",
                    area = s.optString("area"),
                    volumes = vols,
                )
            }
            val airports = mutableListOf<Airport>()
            root.optJSONArray("airports")?.let { aa ->
                for (i in 0 until aa.length()) {
                    val a = aa.getJSONObject(i)
                    airports += Airport(a.getString("id"), a.getDouble("lat"), a.getDouble("lon"))
                }
            }
            val altsArr = root.getJSONArray("altitudes")
            val alts = List(altsArr.length()) { altsArr.getInt(it) }
            return Airspace(sectors, airports, alts, root.optString("source"), root.optString("cycle"))
        }

        /** Union of altitude bands, sorted. */
        fun merge(bands: List<Pair<Int, Int>>): List<Pair<Int, Int>> {
            val sorted = bands.sortedBy { it.first }
            val out = mutableListOf<Pair<Int, Int>>()
            for (b in sorted) {
                val last = out.lastOrNull()
                if (last != null && b.first <= last.second) {
                    out[out.size - 1] = last.first to maxOf(last.second, b.second)
                } else {
                    out += b
                }
            }
            return out
        }

        /** Bands of [own] not covered by [cut]. Both inputs are merged and sorted. */
        fun subtract(own: List<Pair<Int, Int>>, cut: List<Pair<Int, Int>>): List<Pair<Int, Int>> {
            var pieces = own
            for ((cLo, cHi) in cut) {
                val next = mutableListOf<Pair<Int, Int>>()
                for ((lo, hi) in pieces) {
                    if (cHi <= lo || cLo >= hi) {
                        next += lo to hi
                    } else {
                        if (cLo > lo) next += lo to cLo
                        if (cHi < hi) next += cHi to hi
                    }
                }
                pieces = next
            }
            return pieces
        }
    }
}

/** "SFC", "100", "FL235". Values are hundreds of feet as in the source data. */
fun formatAlt(v: Int): String = when {
    v <= 0 -> "SFC"
    v >= 180 -> "FL%03d".format(v)
    else -> "%03d".format(v)
}

fun formatBand(lo: Int, hi: Int) = "${formatAlt(lo)} – ${formatAlt(hi)}"
