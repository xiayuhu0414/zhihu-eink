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

package com.github.zly2006.zhihu.ui.components

import kotlin.math.abs

/** 只在松手时决定去向，普通字段避免在拖动中引起屏幕变化。 */
class AnswerHorizontalSwipeGesture(
    private val triggerThresholdPx: Float,
) {
    private var dragDistancePx = 0f
    private var hasTriggeredHaptic = false

    init {
        require(triggerThresholdPx.isFinite() && triggerThresholdPx > 0f)
    }

    /** 返回本次手势是否首次达到切换阈值。不可切换方向不累积距离。 */
    fun dragBy(deltaPx: Float, canGoPrevious: Boolean, canGoNext: Boolean): Boolean {
        dragDistancePx += deltaPx
        if ((dragDistancePx > 0f && !canGoPrevious) || (dragDistancePx < 0f && !canGoNext)) {
            dragDistancePx = 0f
        }
        if (!hasTriggeredHaptic && abs(dragDistancePx) >= triggerThresholdPx) {
            hasTriggeredHaptic = true
            return true
        }
        return false
    }

    /** 切换条件以松手时的净位移和当前可用方向为准；先清空状态，防止重复触发。 */
    fun finish(canGoPrevious: Boolean, canGoNext: Boolean): AnswerSwipeDirection? {
        val direction = when {
            dragDistancePx >= triggerThresholdPx && canGoPrevious -> AnswerSwipeDirection.PREVIOUS
            dragDistancePx <= -triggerThresholdPx && canGoNext -> AnswerSwipeDirection.NEXT
            else -> null
        }
        cancel()
        return direction
    }

    fun cancel() {
        dragDistancePx = 0f
        hasTriggeredHaptic = false
    }
}

enum class AnswerSwipeDirection {
    PREVIOUS,
    NEXT,
}
