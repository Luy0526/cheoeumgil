package com.cheoeumgil.app

import com.cheoeumgil.core.Hotspot
import com.cheoeumgil.core.Spot
import com.cheoeumgil.core.StruggleEvent
import com.cheoeumgil.core.Wire
import java.net.HttpURLConnection
import java.net.URL

/** Talks to the 헤맴 지도 server (see :server). Blocking calls: run off the main thread. */
class HotspotClient(private val baseUrl: String) {
    val enabled get() = baseUrl.isNotBlank()

    /** True when the server accepted the batch. */
    fun upload(events: List<StruggleEvent>): Boolean = runCatching {
        val c = open("/v1/events").apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "text/plain; charset=utf-8")
        }
        c.outputStream.use { it.write(Wire.encodeEvents(events).encodeToByteArray()) }
        c.responseCode == 200
    }.getOrDefault(false)

    /** Hotspots within [radiusM] of [here] (sent as a ~1 km grid cell), or null when the server can't be reached. */
    fun near(here: Spot, radiusM: Int = 30_000): List<Hotspot>? = runCatching {
        val q = Wire.coarse(here)
        val c = open("/v1/hotspots?lat=${q.lat}&lon=${q.lon}&radiusM=$radiusM")
        if (c.responseCode != 200) null else Wire.decodeHotspots(c.inputStream.bufferedReader().readText())
    }.getOrNull()

    private fun open(path: String) = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
        connectTimeout = 5_000
        readTimeout = 5_000
    }
}
