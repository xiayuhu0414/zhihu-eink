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

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp

/** 墨水屏左右切换：拖动中不移动正文，松手后一次性切换，不绘制相邻回答预览。 */
@Composable
fun AnswerHorizontalSwipe(
    canGoPrevious: Boolean,
    canGoNext: Boolean,
    onNavigatePrevious: () -> Unit,
    onNavigateNext: () -> Unit,
    answerSwitchSensitivity: Float = DEFAULT_ANSWER_SWITCH_SENSITIVITY,
    content: @Composable () -> Unit,
) {
    val triggerThresholdPx = with(LocalDensity.current) {
        (HORIZONTAL_TRIGGER_THRESHOLD_DP / normalizedAnswerSwitchSensitivity(answerSwitchSensitivity)).dp.toPx()
    }
    val hapticFeedback = LocalHapticFeedback.current
    val navigatePrevious by rememberUpdatedState(onNavigatePrevious)
    val navigateNext by rememberUpdatedState(onNavigateNext)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(canGoPrevious, canGoNext, triggerThresholdPx, hapticFeedback) {
                // 普通内存状态，不触发重组、布局或绘制；每个手势至多一次触感提示。
                val gesture = AnswerHorizontalSwipeGesture(triggerThresholdPx)
                detectHorizontalDragGestures(
                    onDragStart = { gesture.cancel() },
                    onDragCancel = { gesture.cancel() },
                    onDragEnd = {
                        when (gesture.finish(canGoPrevious, canGoNext)) {
                            AnswerSwipeDirection.PREVIOUS -> navigatePrevious()
                            AnswerSwipeDirection.NEXT -> navigateNext()
                            null -> Unit
                        }
                    },
                ) { _, dragAmount ->
                    if (gesture.dragBy(dragAmount, canGoPrevious, canGoNext)) {
                        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                    }
                }
            },
    ) {
        content()
    }
}

// 原来的 46dp 阻尼偏移约等于 56dp 实际拖动，保留默认切换手感。
private const val HORIZONTAL_TRIGGER_THRESHOLD_DP = 56f
