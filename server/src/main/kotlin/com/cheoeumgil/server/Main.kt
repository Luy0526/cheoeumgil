package com.cheoeumgil.server

import com.cheoeumgil.core.Hotspot
import com.cheoeumgil.core.Spot
import com.cheoeumgil.core.StruggleDetector
import com.cheoeumgil.core.StruggleEvent
import com.cheoeumgil.core.Wire
import com.cheoeumgil.core.hotspots
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.net.URLDecoder

/**
 * Append-only event store; hotspots are recomputed only after new events arrive.
 * Exact duplicates are ignored, so a client may safely resend a batch after a failed upload.
 */
class HotspotStore(private val file: File) {
    private val events = Wire.decodeEvents(if (file.exists()) file.readText() else "").distinct().toMutableList()
    private val seen = events.toHashSet()
    private var cache: List<Hotspot>? = null

    /** Returns how many events were new. */
    @Synchronized
    fun add(incoming: List<StruggleEvent>): Int {
        val batch = incoming.distinct().filter { it !in seen }
        if (batch.isEmpty()) return 0
        file.appendText(Wire.encodeEvents(batch) + "\n") // throws -> nothing marked seen, the client's retry is accepted
        seen += batch
        events += batch
        cache = null
        return batch.size
    }

    @Synchronized
    fun near(here: Spot, radiusM: Double): List<Hotspot> {
        val all = cache ?: hotspots(events).also { cache = it }
        return all.filter { StruggleDetector.metres(here, it.spot) <= radiusM }
    }

    @Synchronized
    fun size() = events.size
}

private const val MAX_BODY = 1_000_000          // bytes per upload
private const val MAX_RADIUS_M = 50_000.0

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8090
    val dataDir = File(System.getenv("DATA_DIR") ?: "data").apply { mkdirs() }
    val store = HotspotStore(File(dataDir, "events.csv"))

    // ponytail: single-threaded, no auth, no rate limit: fine for the contest demo, add both before real traffic
    val http = HttpServer.create(InetSocketAddress(port), 0)
    http.createContext("/v1/events") { ex ->
        ex.use {
            if (ex.requestMethod != "POST") return@use ex.reply(405, "POST only")
            val body = ex.requestBody.readNBytes(MAX_BODY + 1)
            if (body.size > MAX_BODY) return@use ex.reply(413, "too large")
            val batch = Wire.decodeEvents(body.decodeToString())
            val added = store.add(batch)
            println("events +$added of ${batch.size} (total ${store.size()})")
            ex.reply(200, "accepted ${batch.size}, new $added")
        }
    }
    http.createContext("/v1/hotspots") { ex ->
        ex.use {
            if (ex.requestMethod != "GET") return@use ex.reply(405, "GET only")
            val q = ex.requestURI.rawQuery.orEmpty().split("&").mapNotNull {
                it.split("=", limit = 2).takeIf { kv -> kv.size == 2 }?.let { (k, v) -> k to URLDecoder.decode(v, "UTF-8") }
            }.toMap()
            val lat = q["lat"]?.toDoubleOrNull()?.takeIf { it in -90.0..90.0 }
            val lon = q["lon"]?.toDoubleOrNull()?.takeIf { it in -180.0..180.0 }
            val radius = q["radiusM"]?.toDoubleOrNull()?.takeIf { it in 1.0..MAX_RADIUS_M } ?: 30_000.0
            if (lat == null || lon == null) return@use ex.reply(400, "lat and lon required")
            ex.reply(200, Wire.encodeHotspots(store.near(Spot(lat, lon), radius)))
        }
    }
    http.start()
    println("처음길 hotspot server on :$port, ${store.size()} events loaded from ${dataDir.absolutePath}")
}

private fun HttpExchange.reply(code: Int, text: String) {
    val bytes = text.encodeToByteArray()
    responseHeaders.add("Content-Type", "text/plain; charset=utf-8")
    sendResponseHeaders(code, if (bytes.isEmpty()) -1 else bytes.size.toLong())
    if (bytes.isNotEmpty()) responseBody.write(bytes)
}
