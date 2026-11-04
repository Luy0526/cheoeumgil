package com.cheoeumgil.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Signs that a driver struggled at a spot, and what to do before reaching it. */
enum class StruggleKind(val reason: String, val tip: String) {
    REROUTE("경로를 벗어난", "놓치기 쉬운 곳이니, 갈 길을 미리 준비하세요."),
    SUDDEN_BRAKE("급하게 브레이크를 밟은", "속도를 미리 줄이세요."),
    /** Signalled but could not get in: retried, or waited long while barely moving (see [StruggleDetector.onTurnSignal]). */
    SIGNAL_HESITATION("깜빡이를 켜고도 제때 못 끼어든", "직전엔 끼어들기 어려울 수 있으니, 일찍 옮겨 갈 준비를 하세요."),
    CRUISE_DROP("크루즈가 꺼진", "직접 속도를 조절할 준비를 하세요."),
}

data class Spot(val lat: Double, val lon: Double)

/** [headingDeg] = driving direction, so a spot on the other carriageway is not mixed in. */
data class StruggleEvent(val kind: StruggleKind, val spot: Spot, val timeMs: Long, val tripId: String, val headingDeg: Double = 0.0)

/**
 * Turns raw vehicle/navi signals into struggle events. Pure Kotlin; the activity feeds it
 * and persists what it returns.
 */
class StruggleDetector(
    private val brakeWindowMs: Long = 3_000,     // speed drop must happen within this after the brake
    private val brakeDropKmh: Double = 20.0,
    private val signalHoldMs: Long = 6_000,      // a normal city lane change signals for a few seconds
    private val signalHoldMaxM: Double = 150.0,  // ...and a normal motorway one covers more ground than this meanwhile
    private val signalWindowMs: Long = 30_000,   // signal cycles counted within this window
    private val signalCycles: Int = 2,           // signalled, gave up, signalled again
    private val turnDeg: Double = 45.0,          // heading changed this much = turned at a junction, not a lane change
) {
    var tripId = ""
    private var spot: Spot? = null
    private var heading = 0.0
    private var lastFix: Pair<Spot, Long>? = null
    /** Estimated from consecutive location fixes. */
    var speedKmh = 0.0
        private set
    private var brakeAt: Pair<Long, Double>? = null  // time, speed when brake went down
    private var signalOnAt: Long? = null
    private var signalOnHeading = 0.0
    private var signalOnSpot: Spot? = null
    private val cycles = ArrayDeque<Long>() // start times of recent on->off cycles
    private var cruiseActive = false

    /** Location updates also give a speed estimate (NaviHelper has no speed field). */
    fun onLocation(s: Spot, timeMs: Long, headingDeg: Double): StruggleEvent? {
        lastFix?.let { (prev, t) ->
            val dt = (timeMs - t) / 1000.0
            if (dt > 0) speedKmh = metres(prev, s) / dt * 3.6
        }
        lastFix = s to timeMs
        spot = s
        heading = headingDeg
        val (bt, v0) = brakeAt ?: return null
        if (timeMs - bt > brakeWindowMs) { brakeAt = null; return null }
        if (v0 - speedKmh < brakeDropKmh) return null
        brakeAt = null
        return event(StruggleKind.SUDDEN_BRAKE, timeMs)
    }

    fun onBrake(pressed: Boolean, timeMs: Long) {
        if (pressed) brakeAt = timeMs to speedKmh
    }

    /**
     * Signal on, then off, still heading the same way (a signal that ends in a turn was a junction turn). Counts as a
     * failed merge when the driver either
     *  - signalled [signalCycles] times within [signalWindowMs] (gave up and tried again), or
     *  - held it [signalHoldMs]+ while moving less than [signalHoldMaxM] (waiting for a gap; a normal motorway
     *    lane change takes as long but covers more ground).
     * Repeated "on" reports (e.g. LEFT then RIGHT) keep the original start.
     */
    fun onTurnSignal(on: Boolean, timeMs: Long): StruggleEvent? {
        val since = signalOnAt
        if (on) {
            if (since == null) { signalOnAt = timeMs; signalOnHeading = heading; signalOnSpot = spot }
            return null
        }
        signalOnAt = null
        if (since == null) return null
        if (angleDiff(heading, signalOnHeading) > turnDeg) { cycles.clear(); return null }
        val moved = signalOnSpot?.let { a -> spot?.let { metres(a, it) } } ?: Double.MAX_VALUE
        cycles.addLast(since)
        while (cycles.isNotEmpty() && timeMs - cycles.first() > signalWindowMs) cycles.removeFirst()
        val waited = timeMs - since >= signalHoldMs && moved < signalHoldMaxM
        if (!waited && cycles.size < signalCycles) return null
        cycles.clear()
        return event(StruggleKind.SIGNAL_HESITATION, timeMs)
    }

    fun onCruise(active: Boolean, timeMs: Long): StruggleEvent? {
        val dropped = cruiseActive && !active
        cruiseActive = active
        return if (dropped) event(StruggleKind.CRUISE_DROP, timeMs) else null
    }

    fun onReRoute(timeMs: Long) = event(StruggleKind.REROUTE, timeMs)

    private fun event(kind: StruggleKind, timeMs: Long) = spot?.let { StruggleEvent(kind, it, timeMs, tripId, heading) }

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

        /** Smallest difference between two compass directions, 0..180. */
        fun angleDiff(a: Double, b: Double) = abs(((a - b + 540) % 360) - 180)

        fun meanHeading(hs: List<Double>) =
            (atan2(hs.sumOf { sin(it * PI / 180) }, hs.sumOf { cos(it * PI / 180) }) * 180 / PI + 360) % 360
    }
}

data class Hotspot(val spot: Spot, val trips: Int, val main: StruggleKind, val headingDeg: Double = 0.0)

/**
 * 헤맴 지도: clusters events lying within [radiusM] of each other and driving the same way (fixed grid cells
 * would split a spot sitting on a cell edge) and keeps clusters where several different trips struggled.
 * ponytail: greedy O(n²) clustering on-device for the prototype; move to the server with a spatial index once events pile up.
 */
fun hotspots(events: List<StruggleEvent>, minTrips: Int = 3, radiusM: Double = 50.0, maxTurnDeg: Double = 45.0): List<Hotspot> {
    fun centre(c: List<StruggleEvent>) = Spot(c.map { it.spot.lat }.average(), c.map { it.spot.lon }.average())
    fun heading(c: List<StruggleEvent>) = StruggleDetector.meanHeading(c.map { it.headingDeg })
    val clusters = mutableListOf<MutableList<StruggleEvent>>()
    for (e in events) {
        clusters.firstOrNull {
            StruggleDetector.metres(centre(it), e.spot) <= radiusM && StruggleDetector.angleDiff(heading(it), e.headingDeg) <= maxTurnDeg
        }?.add(e) ?: clusters.add(mutableListOf(e))
    }
    return clusters.mapNotNull { c ->
        val trips = c.map { it.tripId }.distinct().size
        if (trips < minTrips) null
        else Hotspot(centre(c), trips, c.groupingBy { it.kind }.eachCount().maxBy { it.value }.key, heading(c))
    }
}

/**
 * Hotspots within [rangeM], roughly in front, recorded while driving the same way, nearest first.
 * ponytail: straight-line, not along the route (NaviHelper gives no route shape); a parallel road the same way can still match.
 */
fun hotspotsAhead(here: Spot, headingDeg: Double, spots: List<Hotspot>, rangeM: Double = 1500.0): List<Pair<Hotspot, Double>> =
    spots.map { it to StruggleDetector.metres(here, it.spot) }
        .filter { (h, d) ->
            d in 30.0..rangeM && StruggleDetector.angleDiff(StruggleDetector.bearing(here, h.spot), headingDeg) <= 45 &&
                StruggleDetector.angleDiff(h.headingDeg, headingDeg) <= 45
        }
        .sortedBy { it.second }
