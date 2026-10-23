package com.cheoeumgil.core

import org.junit.Assert.assertEquals
import org.junit.Test

class IconTest {
    private fun icon(type: String, next: Turn? = null) = iconFor(Section(Turn(type, 500.0, ""), 500.0, next))

    @Test
    fun picksGlyphAndSide() {
        assertEquals(Icon(Glyph.FORK, false), icon("TURN_AVOID_UNDER_R"))
        assertEquals(Icon(Glyph.FORK, true), icon("TURN_KEEP_L"))
        assertEquals(Icon(Glyph.FORK, false), icon("TURN_EXIT_IC_R"))
        assertEquals(Icon(Glyph.TURN, true), icon("TURN_LEFT"))
        assertEquals(Icon(Glyph.TURN, false), icon("TURN_SHARP_R"))
        assertEquals(Icon(Glyph.DOUBLE_TURN, false), icon("TURN_RIGHT", Turn("TURN_RIGHT", 50.0, "")))
        assertEquals(Icon(Glyph.ROUNDABOUT, false), icon("TURN_ENTER_ROUNDABOUT_03"))
        assertEquals(Icon(Glyph.MERGE, false), icon("TURN_DEVIDE_MERGE"))
        assertEquals(Icon(Glyph.UTURN, true), icon("TURN_UTURN"))
        assertEquals(Icon(Glyph.WARNING, false), icon("TURN_TOLL"))
    }
}
