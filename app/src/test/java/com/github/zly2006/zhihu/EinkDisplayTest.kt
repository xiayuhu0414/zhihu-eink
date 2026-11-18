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

import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.VectorConverter
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.github.zly2006.zhihu.theme.EinkColorScheme
import com.github.zly2006.zhihu.theme.EinkMotionScheme
import com.github.zly2006.zhihu.theme.ThemeManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EinkDisplayTest {
    @Test
    fun readingSurfacesStayWhiteWithBlackText() {
        with(EinkColorScheme) {
            listOf(
                background,
                surface,
                surfaceVariant,
                surfaceBright,
                surfaceDim,
                surfaceContainer,
                surfaceContainerHigh,
                surfaceContainerHighest,
                surfaceContainerLow,
                surfaceContainerLowest,
            ).forEach { assertEquals(Color.White, it) }
            assertEquals(Color.Black, onBackground)
            assertEquals(Color.Black, onSurface)
        }
        assertFalse(ThemeManager.isDarkTheme)
    }

    @Test
    fun auxiliaryTextAndDividersRemainReadableInGrayscale() {
        with(EinkColorScheme) {
            assertTrue(contrast(onSurfaceVariant, surface) >= 7f)
            assertTrue(contrast(outline, surface) >= 7f)
            assertTrue(contrast(outlineVariant, surface) >= 4.5f)
        }
    }

    @Test
    fun accentColorsRemainColoredAndReadableOnWhite() {
        with(EinkColorScheme) {
            listOf(primary, secondary, tertiary, error).forEach { accent ->
                assertTrue(accent.red != accent.green || accent.green != accent.blue)
                assertTrue(contrast(accent, Color.White) >= 7f)
            }
        }
    }

    @OptIn(ExperimentalMaterial3ExpressiveApi::class)
    @Test
    fun materialTransitionsFinishWithoutIntermediateFrames() {
        val specs = with(EinkMotionScheme) {
            listOf(
                defaultSpatialSpec<Float>(),
                fastSpatialSpec<Float>(),
                slowSpatialSpec<Float>(),
                defaultEffectsSpec<Float>(),
                fastEffectsSpec<Float>(),
                slowEffectsSpec<Float>(),
            )
        }
        specs.forEach { spec ->
            val vectorized = spec.vectorize(Float.VectorConverter)
            assertEquals(
                0L,
                vectorized.getDurationNanos(AnimationVector1D(0f), AnimationVector1D(1f), AnimationVector1D(0f)),
            )
        }
    }

    @Test
    fun applicationDisablesComposeAnimationsWithoutSystemSettings() {
        assertEquals(0f, EinkMotionDurationScale.scaleFactor, 0f)
    }

    private fun contrast(first: Color, second: Color): Float {
        val firstLuminance = first.luminance()
        val secondLuminance = second.luminance()
        return (maxOf(firstLuminance, secondLuminance) + 0.05f) / (minOf(firstLuminance, secondLuminance) + 0.05f)
    }
}
