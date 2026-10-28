package com.cheoeumgil.core

/**
 * Speaks phrases one at a time and in order, so nothing is lost while the TTS engine is still
 * starting up or busy with the previous phrase. Busy until the engine reports ready via [done].
 */
class SpeechQueue(private val max: Int = 3) {
    private val waiting = ArrayDeque<String>()
    private var busy = true

    /** Returns the phrase to speak right now, or null when it was queued. Beyond [max] the oldest is dropped. */
    fun offer(text: String): String? {
        if (!busy) {
            busy = true
            return text
        }
        waiting.addLast(text)
        if (waiting.size > max) waiting.removeFirst()
        return null
    }

    /** Engine became ready or finished a phrase: the next phrase to speak, if any. */
    fun done(): String? = waiting.removeFirstOrNull().also { busy = it != null }
}
