package com.cheoeumgil.core

/**
 * How many times the driver actually arrived at each destination. A route counts only on arrival,
 * so a route that was started and abandoned does not make a road "familiar".
 * Stored as one `name<TAB>count` line per destination.
 */
class RouteHistory(private val familiarAfter: Int = 3) {
    private val arrivals = mutableMapOf<String, Int>()

    fun isFamiliar(destination: String) = (arrivals[destination] ?: 0) >= familiarAfter

    fun arrived(destination: String) {
        if (destination.isNotBlank()) arrivals.merge(destination, 1, Int::plus)
    }

    fun encode(): String = arrivals.entries.joinToString("\n") { "${it.key.replace('\t', ' ')}\t${it.value}" }

    fun decode(text: String) {
        text.lineSequence().forEach { line ->
            val (name, count) = line.split('\t').takeIf { it.size == 2 } ?: return@forEach
            count.toIntOrNull()?.let { arrivals[name] = it }
        }
    }
}
