package com.zhiyu.app.domain.planar

import org.junit.Assert.*
import org.junit.Test

class PlanarDraftTest {
    @Test fun proportionalSuggestionsStayUnconfirmedAndDoNotInventParts() {
        val draft = PlanarDraft.suggest(128, 192)
        draft.validate(128, 192)
        assertFalse(draft.suggestionsConfirmed)
        assertTrue(draft.parts.isEmpty())
        assertTrue(draft.subjectBrush.isEmpty())
        assertEquals(64.0, draft.anchors.head.x, 0.0)
    }

    @Test fun editedCollectionsAreSnapshots() {
        val points = mutableListOf(PlanarPoint(1.0, 1.0))
        val stroke = PlanarBrushStroke(points, 2.0, true)
        points.clear()
        assertEquals(1, stroke.points.size)
        val polygon = mutableListOf(PlanarPoint(2.0, 2.0), PlanarPoint(9.0, 2.0), PlanarPoint(2.0, 9.0))
        val part = PlanarPart("wing", "翼", "accessory", 0.0, polygon, PlanarPoint(3.0, 3.0))
        polygon.clear()
        assertEquals(3, part.polygon.size)
    }

    @Test fun invalidGeometryAndPartBudgetRejectBeforeGeneration() {
        val draft = PlanarDraft.suggest(128, 192)
        assertThrows(IllegalArgumentException::class.java) { draft.validate(1537, 192) }
        assertThrows(IllegalArgumentException::class.java) {
            draft.copy(anchors = draft.anchors.copy(head = PlanarPoint(Double.NaN, 1.0))).validate(128, 192)
        }
        val parts = (1..61).map { part("part_$it") }
        assertThrows(IllegalArgumentException::class.java) { draft.copy(parts = parts).validate(128, 192) }
        assertThrows(IllegalArgumentException::class.java) { draft.copy(parts = listOf(part("base"))).validate(128, 192) }
        assertThrows(IllegalArgumentException::class.java) { draft.copy(parts = listOf(part("wing"), part("wing"))).validate(128, 192) }
    }

    @Test fun separateSparseKeepAndEraseEditsRemainOrdered() {
        val keep = PlanarBrushStroke(listOf(PlanarPoint(100.0, 150.0)), 3.0, true)
        val erase = keep.copy(keep = false)
        val draft = PlanarDraft.suggest(128, 192).copy(subjectBrush = listOf(keep, erase), suggestionsConfirmed = true)
        draft.validate(128, 192)
        assertTrue(draft.subjectBrush.first().keep)
        assertFalse(draft.subjectBrush.last().keep)
    }

    private fun part(id: String) = PlanarPart(id, "附件", "accessory", 0.0,
        listOf(PlanarPoint(2.0, 2.0), PlanarPoint(9.0, 2.0), PlanarPoint(2.0, 9.0)), PlanarPoint(3.0, 3.0))
}
