package com.cheoeumgil.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sqrt

/** Signs that a driver struggled at a spot. */
enum class StruggleKind(val reason: String) {
    REROUTE("경로를 자주 벗어난"),
    SUDDEN_BRAKE("급하게 브레이크를 밟은"),
    SIGNAL_HESITATION("차로 변경을 망설인"),
    CRUISE_DROP("크루즈가 자주 꺼진"),
}

data class Spot(val lat: Double, val lon: Double)

data class StruggleEvent(val kind: StruggleKind, val spot: Spot, val timeMs: Long, val tripId: String)

/**
 * Turns raw vehicle/navi signals into struggle events. Pure Kotlin; the activity feeds it
 * and persists what it returns.
 */
class StruggleDetector(
    private val brakeWindowMs: Long = 3_000,     // speed drop must happen within this after the brake
    private val brakeDropKmh: Double = 20.0,
    private val signalWindowMs: Long = 30_000,   // on/off flips counted within this window
    private val signalFlips: Int = 4,            // ON,OFF,ON,OFF = changed its mind
) {
    var tripId = ""
    private var spot: Spot? = null
    private var lastFix: Pair<Spot, Long>? = null
    /** Estimated from consecutive location fixes. */
    var speedKmh = 0.0
        private set
    private var brakeAt: Pair<Long, Double>? = null  // time, speed when brake went down
    private val flips = ArrayDeque<Long>()
    private var signalOn = false
    private var cruiseActive = false

    /** Location updates also give a speed estimate (NaviHelper has no speed field). */
    fun onLocation(s: Spot, timeMs: Long): StruggleEvent? {
        lastFix?.let { (prev, t) ->
            val dt = (timeMs - t) / 1000.0
            if (dt > 0) speedKmh = metres(prev, s) / dt * 3.6
        }
        lastFix = s to timeMs
        spot = s
        val (bt, v0) = brakeAt ?: return null
        if (timeMs - bt > brakeWindowMs) { brakeAt = null; return null }
        if (v0 - speedKmh < brakeDropKmh) return null
        brakeAt = null
        return event(StruggleKind.SUDDEN_BRAKE, timeMs)
    }

    fun onBrake(pressed: Boolean, timeMs: Long) {
        if (pressed) brakeAt = timeMs to speedKmh
    }

    fun onTurnSignal(on: Boolean, timeMs: Long): StruggleEvent? {
        if (on == signalOn) return null // repeated state reports (e.g. LEFT then BOTH) are not flips
        signalOn = on
        flips.addLast(timeMs)
        while (flips.isNotEmpty() && timeMs - flips.first() > signalWindowMs) flips.removeFirst()
        if (on || flips.size < signalFlips) return null
        flips.clear()
        return event(StruggleKind.SIGNAL_HESITATION, timeMs)
    }

    fun onCruise(active: Boolean, timeMs: Long): StruggleEvent? {
        val dropped = cruiseActive && !active
        cruiseActive = active
        return if (dropped) event(StruggleKind.CRUISE_DROP, timeMs) else null
    }

    fun onReRoute(timeMs: Long) = event(StruggleKind.REROUTE, timeMs)

    private fun event(kind: StruggleKind, timeMs: Long) = spot?.let { StruggleEvent(kind, it, timeMs, tripId) }

    companion object {
        fun metres(a: Spot, b: Spot): Double {
            val dLat = (b.lat - a.lat) * 111_320.0
            val dLon = (b.lon - a.lon) * 111_320.0 * cos(a.lat * PI / 180)
            return sqrt(dLat * dLat + dLon * dLon)
        }

        /** Compass bearing a -> b in degrees. */
        fun bearing(a: Spot, b: Spot): Double {
            val dLat = b.lat - a.lat
            val dLon = (b.lon - a.lon) * cos(a.lat * PI / 180)
            return (atan2(dLon, dLat) * 180 / PI + 360) % 360
        }
    }
}

data class Hotspot(val spot: Spot, val trips: Int, val main: StruggleKind)

/**
 * 헤맴 지도: clusters events lying within [radiusM] of each other (fixed grid cells would split a spot
 * sitting on a cell edge) and keeps clusters where several different trips struggled.
 * ponytail: greedy O(n²) clustering on-device for the prototype; move to the server with a spatial index once events pile up.
 */
fun hotspots(events: List<StruggleEvent>, minTrips: Int = 3, radiusM: Double = 50.0): List<Hotspot> {
    fun centre(c: List<StruggleEvent>) = Spot(c.map { it.spot.lat }.average(), c.map { it.spot.lon }.average())
    val clusters = mutableListOf<MutableList<StruggleEvent>>()
    for (e in events) {
        clusters.firstOrNull { StruggleDetector.metres(centre(it), e.spot) <= radiusM }?.add(e)
            ?: clusters.add(mutableListOf(e))
    }
    return clusters.mapNotNull { c ->
        val trips = c.map { it.tripId }.distinct().size
        if (trips < minTrips) null
        else Hotspot(centre(c), trips, c.groupingBy { it.kind }.eachCount().maxBy { it.value }.key)
    }
}

/** Hotspots within [rangeM] and roughly in the driving direction, nearest first. */
fun hotspotsAhead(here: Spot, headingDeg: Double, spots: List<Hotspot>, rangeM: Double = 1500.0): List<Pair<Hotspot, Double>> =
    spots.map { it to StruggleDetector.metres(here, it.spot) }
        .filter { (h, d) ->
            val off = abs(((StruggleDetector.bearing(here, h.spot) - headingDeg + 540) % 360) - 180)
            d in 30.0..rangeM && off <= 45
        }
        .sortedBy { it.second }
