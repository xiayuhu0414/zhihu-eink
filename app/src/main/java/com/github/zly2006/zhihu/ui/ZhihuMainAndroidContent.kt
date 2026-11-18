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

package com.github.zly2006.zhihu.ui

import androidx.activity.compose.LocalActivity
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.github.zly2006.zhihu.MainActivity
import com.github.zly2006.zhihu.platform.androidUserMessageSink
import com.github.zly2006.zhihu.viewmodel.ArticleViewModel

/**
 * Android 平台的 Zhihu++ 主界面入口。
 *
 * 这里把 [MainActivity] 持有的导航、偏好设置、文章页 ViewModel、回答切换转场和 NLP 页面适配到共享 [ZhihuMain]。
 * UI 结构仍由 common 主壳负责，Android 只提供生命周期、Activity、ViewModel 和平台专属页面实现。
 */
@Composable
fun AndroidZhihuMain(navController: NavHostController) {
    val activity = LocalActivity.current as MainActivity
    val isTablet = LocalConfiguration.current.smallestScreenWidthDp >= 600
    ZhihuMain(
        navController = navController,
        mainTabNavigationTarget = activity.mainTabNavigationTarget,
        navigate = activity::navigate,
        navigateContent = activity::navigateIn,
        enableLandscapeListDetail = true,
        isListDetailCapable = isTablet,
        setCurrentMainTabOpenFrom = activity::setCurrentMainTabOpenFrom,
        consumeMainTabNavigationTarget = activity::consumeMainTabNavigationTarget,
        preferenceState = rememberAndroidZhihuMainPreferenceState(),
        isDarkTheme = com.github.zly2006.zhihu.theme.ThemeManager.isDarkTheme,
        // 回答切换直接替换页面，独立于系统或 Compose 的动画时长设置。
        articleEnterTransition = { EnterTransition.None },
        articleExitTransition = { ExitTransition.None },
        articleContent = { article, navEntry ->
            val viewModel: ArticleViewModel = viewModel(navEntry) {
                ArticleViewModel(article, activity.httpClient, androidUserMessageSink(activity)) { onPause ->
                    navEntry.lifecycle.addObserver(object : DefaultLifecycleObserver {
                        override fun onPause(owner: LifecycleOwner) {
                            onPause()
                        }
                    })
                }
            }
            ArticleScreen(article, viewModel)
        },
        sentenceSimilarityContent = {
            SentenceSimilarityTestScreen()
        },
        blocklistSettingsNlpContent = { onNavigateBack ->
            NLPKeywordManagementScreen(
                innerPadding = PaddingValues(),
                onNavigateBack = onNavigateBack,
            )
        },
    )
}
