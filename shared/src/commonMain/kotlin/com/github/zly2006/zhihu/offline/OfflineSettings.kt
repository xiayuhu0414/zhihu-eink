package com.github.zly2006.zhihu.offline

import androidx.compose.runtime.Composable

@Composable
expect fun OfflineCacheSettings(highlightedSetting: String)

expect fun isOfflineNetworkAvailable(): Boolean
