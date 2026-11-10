package com.nemap.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContinuationTailTest {
    @Test fun newContinuationTextStreamsWithoutWaitingForFourThousandCharacters() {
        val previous = "Earlier answer ending in a distinct sentence."
        val sent = mutableListOf<String>()
        val tail = ContinuationTail(previous, sent::add)
        tail.append("Brand")
        assertEquals(listOf("Brand"), sent)
        tail.append(" new text")
        assertEquals(listOf("Brand", " new text"), sent)
        tail.finish()
        assertEquals(previous + "Brand new text", tail.fullText())
    }

    @Test fun repeatedSuffixIsBufferedThenRemovedWithoutDuplicatingIt() {
        val overlap = "repeated-tail-fragment-0123456789"
        val previous = "Earlier answer: $overlap"
        val sent = mutableListOf<String>()
        val tail = ContinuationTail(previous, sent::add)
        tail.append(overlap.take(12))
        assertTrue("ambiguous repeated prefix must wait", sent.isEmpty())
        tail.append(overlap.drop(12) + " and the new continuation")
        assertEquals(listOf(" and the new continuation"), sent)
        tail.finish()
        assertEquals(previous + " and the new continuation", tail.fullText())
    }

    @Test fun duplicateOnlyContinuationProducesNoNewDelta() {
        val previous = "Earlier answer: repeated-tail-fragment-0123456789"
        val sent = mutableListOf<String>()
        val tail = ContinuationTail(previous, sent::add)
        tail.append(previous.takeLast(30))
        tail.finish()
        assertTrue(sent.isEmpty())
        assertEquals(previous, tail.fullText())
    }
}
