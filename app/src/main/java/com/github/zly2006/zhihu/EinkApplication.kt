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

import android.app.Application
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.platform.WindowRecomposerFactory
import androidx.compose.ui.platform.WindowRecomposerPolicy
import androidx.compose.ui.platform.createLifecycleAwareWindowRecomposer

internal object EinkMotionDurationScale : MotionDurationScale {
    override val scaleFactor: Float = 0f
}

class EinkApplication : Application() {
    @OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
    override fun onCreate() {
        super.onCreate()
        // 为本应用所有 Compose 窗口提供零时长，包括弹窗；不修改系统设置。
        WindowRecomposerPolicy.setFactory(
            WindowRecomposerFactory { view ->
                view.createLifecycleAwareWindowRecomposer(coroutineContext = EinkMotionDurationScale)
            },
        )
    }
}
