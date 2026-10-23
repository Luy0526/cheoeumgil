package com.cheoeumgil.core

/** Pictogram shown next to a hard section; the app draws it, mirrored when [left]. */
enum class Glyph { TURN, DOUBLE_TURN, FORK, ROUNDABOUT, MERGE, UTURN, WARNING }

data class Icon(val glyph: Glyph, val left: Boolean)

fun iconFor(s: Section): Icon {
    val t = s.turn.type
    val left = t == "TURN_LEFT" || t.endsWith("_L")
    return when {
        s.next != null -> Icon(Glyph.DOUBLE_TURN, left)
        t == "TURN_LEFT" || t == "TURN_RIGHT" || t.startsWith("TURN_SHARP") -> Icon(Glyph.TURN, left)
        t.startsWith("TURN_KEEP") || "UNDER" in t || "OVER" in t || "_IC_" in t ||
            t == "TURN_ENTER_SERVICEAREA" || t == "TURN_RESTAREA" -> Icon(Glyph.FORK, left)
        "ROUNDABOUT" in t -> Icon(Glyph.ROUNDABOUT, false)
        t == "TURN_DEVIDE_MERGE" -> Icon(Glyph.MERGE, false)
        t == "TURN_UTURN" -> Icon(Glyph.UTURN, true)
        else -> Icon(Glyph.WARNING, false)
    }
}
