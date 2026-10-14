package com.zhiyu.app.domain.planar

import org.junit.Assert.*
import org.junit.Test

class PlanarEditHistoryTest {
    @Test fun undoRedoAndNewEditKeepSameDraft() {
        val h=PlanarEditHistory(0,{8}); assertFalse(h.canUndo);h.update(1);h.update(2)
        assertTrue(h.undo());assertEquals(1,h.value);assertTrue(h.redo());assertEquals(2,h.value)
        h.undo();h.update(3);assertFalse(h.canRedo);assertTrue(h.undo());assertEquals(1,h.value)
    }
    @Test fun countAndPayloadBoundEvictOldestDrafts() {
        val h=PlanarEditHistory(0,{8},2,24);for(i in 1..4)h.update(i)
        assertTrue(h.undo());assertEquals(3,h.value);assertTrue(h.undo());assertEquals(2,h.value);assertFalse(h.undo())
    }
    @Test fun largeOrNegativePayloadDoesNotReplaceCurrentValue() {
        val h=PlanarEditHistory("a",{it.length},40,10);h.update("b")
        assertThrows(IllegalArgumentException::class.java){h.update("x".repeat(11))}
        assertEquals("b",h.value);h.undo();assertEquals("a",h.value)
        assertThrows(IllegalArgumentException::class.java){PlanarEditHistory("a",{-1})}
    }
    @Test fun identicalDraftIsNotAnUndoStep() {
        val h=PlanarEditHistory("a",{it.length});assertFalse(h.update("a"));assertFalse(h.canUndo)
    }
}
