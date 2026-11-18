/*
 * Zhihu++ - Free & Ad-Free Zhihu client for all platforms.
 * Copyright (C) 2024-2026, zly2006 <i@zly2006.me>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation (version 3 only).
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.github.zly2006.zhihu

import com.github.zly2006.zhihu.ui.components.AnswerHorizontalSwipeGesture
import com.github.zly2006.zhihu.ui.components.AnswerSwipeDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnswerHorizontalSwipeGestureTest {
    @Test
    fun shortDragDoesNotSwitch() {
        val gesture = AnswerHorizontalSwipeGesture(56f)
        assertFalse(gesture.dragBy(-55f, canGoPrevious = true, canGoNext = true))
        assertNull(gesture.finish(canGoPrevious = true, canGoNext = true))
    }

    @Test
    fun leftDragSwitchesToNextOnlyOnceOnRelease() {
        val gesture = AnswerHorizontalSwipeGesture(56f)
        assertFalse(gesture.dragBy(-30f, canGoPrevious = true, canGoNext = true))
        assertTrue(gesture.dragBy(-26f, canGoPrevious = true, canGoNext = true))
        assertEquals(AnswerSwipeDirection.NEXT, gesture.finish(canGoPrevious = true, canGoNext = true))
        assertNull(gesture.finish(canGoPrevious = true, canGoNext = true))
    }

    @Test
    fun rightDragSwitchesToPrevious() {
        val gesture = AnswerHorizontalSwipeGesture(56f)
        assertTrue(gesture.dragBy(56f, canGoPrevious = true, canGoNext = true))
        assertEquals(AnswerSwipeDirection.PREVIOUS, gesture.finish(canGoPrevious = true, canGoNext = true))
    }

    @Test
    fun returningBelowThresholdDoesNotSwitch() {
        val gesture = AnswerHorizontalSwipeGesture(56f)
        assertTrue(gesture.dragBy(-90f, canGoPrevious = true, canGoNext = true))
        assertFalse(gesture.dragBy(60f, canGoPrevious = true, canGoNext = true))
        assertNull(gesture.finish(canGoPrevious = true, canGoNext = true))
    }

    @Test
    fun reversedGestureUsesFinalDirectionWithOneHaptic() {
        val gesture = AnswerHorizontalSwipeGesture(56f)
        assertTrue(gesture.dragBy(-70f, canGoPrevious = true, canGoNext = true))
        assertFalse(gesture.dragBy(140f, canGoPrevious = true, canGoNext = true))
        assertEquals(AnswerSwipeDirection.PREVIOUS, gesture.finish(canGoPrevious = true, canGoNext = true))
    }

    @Test
    fun hapticDoesNotRepeatWhenCrossingThresholdAgain() {
        val gesture = AnswerHorizontalSwipeGesture(56f)
        assertTrue(gesture.dragBy(-60f, canGoPrevious = true, canGoNext = true))
        assertFalse(gesture.dragBy(20f, canGoPrevious = true, canGoNext = true))
        assertFalse(gesture.dragBy(-20f, canGoPrevious = true, canGoNext = true))
        gesture.finish(canGoPrevious = true, canGoNext = true)
        assertTrue(gesture.dragBy(-60f, canGoPrevious = true, canGoNext = true))
    }

    @Test
    fun cancellationClearsDistanceAndHaptic() {
        val gesture = AnswerHorizontalSwipeGesture(56f)
        gesture.dragBy(-90f, canGoPrevious = true, canGoNext = true)
        gesture.cancel()
        assertNull(gesture.finish(canGoPrevious = true, canGoNext = true))
        assertTrue(gesture.dragBy(56f, canGoPrevious = true, canGoNext = true))
    }

    @Test
    fun unavailableDirectionsDoNotAccumulateDistanceOrTriggerHaptic() {
        val gesture = AnswerHorizontalSwipeGesture(56f)
        assertFalse(gesture.dragBy(-200f, canGoPrevious = true, canGoNext = false))
        assertNull(gesture.finish(canGoPrevious = true, canGoNext = false))
        assertFalse(gesture.dragBy(200f, canGoPrevious = false, canGoNext = true))
        assertNull(gesture.finish(canGoPrevious = false, canGoNext = true))
        assertFalse(gesture.dragBy(-200f, canGoPrevious = true, canGoNext = false))
        assertTrue(gesture.dragBy(56f, canGoPrevious = true, canGoNext = false))
        assertEquals(AnswerSwipeDirection.PREVIOUS, gesture.finish(canGoPrevious = true, canGoNext = false))
    }

    @Test
    fun releaseRechecksWhetherDestinationIsStillAvailable() {
        val gesture = AnswerHorizontalSwipeGesture(56f)
        gesture.dragBy(-90f, canGoPrevious = true, canGoNext = true)
        assertNull(gesture.finish(canGoPrevious = true, canGoNext = false))
    }
}
