package com.cheoeumgil.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpeechQueueTest {
    @Test
    fun nothingIsLostBeforeReadyOrWhileSpeaking() {
        val q = SpeechQueue()
        assertNull(q.offer("요약"))              // engine not ready yet
        assertEquals("요약", q.done())           // ready -> first queued phrase
        assertNull(q.offer("1.5킬로미터 앞"))     // still speaking
        assertNull(q.offer("답변"))
        assertEquals("1.5킬로미터 앞", q.done())  // in order
        assertEquals("답변", q.done())
        assertNull(q.done())                    // idle
        assertEquals("곧", q.offer("곧"))        // idle -> speak right away
    }

    @Test
    fun backlogKeepsTheNewest() {
        val q = SpeechQueue(max = 2)
        listOf("a", "b", "c").forEach { q.offer(it) }
        assertEquals("b", q.done())
        assertEquals("c", q.done())
    }
}
