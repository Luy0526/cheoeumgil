package com.cheoeumgil.core

/**
 * App <-> server format. One CSV line per item, same as the app's local store:
 *   event:   kind,lat,lon,timeMs,tripId,heading
 *   hotspot: lat,lon,trips,kind,heading
 * ponytail: CSV instead of JSON keeps both sides dependency-free; switch if fields get nested.
 */
object Wire {
    private val TRIP_ID = Regex("[A-Za-z0-9-]{1,64}")

    fun encodeEvents(events: List<StruggleEvent>) =
        events.joinToString("\n") { "${it.kind},${it.spot.lat},${it.spot.lon},${it.timeMs},${it.tripId},${it.headingDeg}" }

    /** Untrusted input: any malformed line is dropped, never partially accepted. */
    fun decodeEvents(text: String): List<StruggleEvent> = text.lineSequence().mapNotNull { line ->
        val f = line.trim().split(",")
        if (f.size != 6) return@mapNotNull null
        val kind = StruggleKind.entries.firstOrNull { it.name == f[0] } ?: return@mapNotNull null
        val lat = f[1].toDoubleOrNull()?.takeIf { it in -90.0..90.0 } ?: return@mapNotNull null
        val lon = f[2].toDoubleOrNull()?.takeIf { it in -180.0..180.0 } ?: return@mapNotNull null
        val time = f[3].toLongOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
        if (!TRIP_ID.matches(f[4])) return@mapNotNull null
        val heading = f[5].toDoubleOrNull()?.takeIf { it >= 0.0 && it < 360.0 } ?: return@mapNotNull null
        StruggleEvent(kind, Spot(lat, lon), time, f[4], heading)
    }.toList()

    fun encodeHotspots(spots: List<Hotspot>) =
        spots.joinToString("\n") { "${it.spot.lat},${it.spot.lon},${it.trips},${it.main},${it.headingDeg}" }

    fun decodeHotspots(text: String): List<Hotspot> = text.lineSequence().mapNotNull { line ->
        val f = line.trim().split(",")
        if (f.size != 5) return@mapNotNull null
        val kind = StruggleKind.entries.firstOrNull { it.name == f[3] } ?: return@mapNotNull null
        val lat = f[0].toDoubleOrNull() ?: return@mapNotNull null
        val lon = f[1].toDoubleOrNull() ?: return@mapNotNull null
        val trips = f[2].toIntOrNull() ?: return@mapNotNull null
        val heading = f[4].toDoubleOrNull() ?: return@mapNotNull null
        Hotspot(Spot(lat, lon), trips, kind, heading)
    }.toList()

    /**
     * Privacy: events near where a trip started or ended (home, work) never leave the car.
     * Fails closed: with no private spots known, nothing is uploaded. Also drops events without a trip id.
     */
    fun uploadable(events: List<StruggleEvent>, privateSpots: List<Spot>, radiusM: Double = 300.0) =
        if (privateSpots.isEmpty()) emptyList()
        else events.filter { e -> TRIP_ID.matches(e.tripId) && privateSpots.none { StruggleDetector.metres(it, e.spot) <= radiusM } }

    /** Position snapped to a 0.01° grid (~1 km) for hotspot queries, so the exact start never leaves the car. */
    fun coarse(s: Spot) = Spot(Math.round(s.lat * 100) / 100.0, Math.round(s.lon * 100) / 100.0)
}
