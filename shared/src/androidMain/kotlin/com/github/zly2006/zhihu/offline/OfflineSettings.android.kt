package com.github.zly2006.zhihu.offline

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.github.zly2006.zhihu.platform.rememberSettingsStore
import com.github.zly2006.zhihu.ui.components.SettingItemGroup
import com.github.zly2006.zhihu.ui.components.SettingItemWithSwitch

@Composable
actual fun OfflineCacheSettings(highlightedSetting: String) {
    val settings = rememberSettingsStore()
    val options = remember { OfflineOptions.from(settings) }
    var enabled by remember { mutableStateOf(options.enabled) }
    var wifiOnly by remember { mutableStateOf(options.wifiOnly) }
    val status by OfflineRuntime.status.collectAsState()
    var confirmClear by remember { mutableStateOf(false) }
    SettingItemGroup(title = "自动离线缓存") {
        SettingItemWithSwitch(
            title = { Text("自动准备内容") },
            description = { Text("页面和操作保持不变；联网获取新数据，断网自动使用缓存。不下载视频。") },
            checked = enabled,
            onCheckedChange = {
                enabled = it
                settings.putBoolean(OFFLINE_ENABLED, it)
                OfflineRuntime.pauseAndReschedule()
            },
            settingKey = OFFLINE_ENABLED,
            highlightedKey = highlightedSetting,
        )
        SettingItemWithSwitch(
            title = { Text("仅在 Wi-Fi 下自动下载") },
            description = { Text("正常浏览产生的接口数据仍会保存，不额外消耗下载流量。") },
            checked = wifiOnly,
            onCheckedChange = {
                wifiOnly = it
                settings.putBoolean(OFFLINE_WIFI_ONLY, it)
                OfflineRuntime.pauseAndReschedule()
            },
            settingKey = OFFLINE_WIFI_ONLY,
            highlightedKey = highlightedSetting,
        )
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val fields = remember {
                listOf(
                    Triple(OFFLINE_QUESTIONS, "问题数量（0–1000）", options.questions),
                    Triple(OFFLINE_ANSWERS, "每题回答数（1–500）", options.answers),
                    Triple(OFFLINE_ARTICLES, "文章数量（0–1000）", options.articles),
                    Triple(OFFLINE_COMMENTS, "每个内容一级评论数（0–500）", options.comments),
                    Triple(OFFLINE_REPLIES, "每个内容额外回复总数（0–200）", options.replies),
                    Triple(OFFLINE_CAPACITY_MB, "容量上限 MB（128–16384）", options.capacityMb),
                    Triple(OFFLINE_READ_HOURS, "已读保留小时（0–720）", options.readHours),
                )
            }
            val values = remember { fields.map { mutableStateOf(it.third.toString()) } }
            val ranges = listOf(0..1000, 1..500, 0..1000, 0..500, 0..200, 128..16384, 0..720)
            fields.forEachIndexed { index, field ->
                OutlinedTextField(
                    value = values[index].value,
                    onValueChange = { values[index].value = it.filter(Char::isDigit).take(5) },
                    label = { Text(field.second) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = values[index].value.toIntOrNull() !in ranges[index],
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Button(
                enabled = values.indices.all { values[it].value.toIntOrNull() in ranges[it] },
                onClick = {
                    fields.forEachIndexed { index, field -> settings.putInt(field.first, values[index].value.toInt()) }
                    OfflineRuntime.pauseAndReschedule()
                },
            ) { Text("应用缓存设置") }
            Text("数量是准备目标；实际可用数量受接口、容量和后台运行限制。回复数是整个评论区的额外回复总量。", style = MaterialTheme.typography.bodySmall)
            Text(status, style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { OfflineRuntime.onForeground(force = true) }, enabled = enabled) { Text("立即补充") }
                TextButton(onClick = { OfflineRuntime.clear(readOnly = true) }) { Text("清理已读") }
            }
            TextButton(onClick = { confirmClear = true }) { Text("清空缓存") }
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清空离线缓存？") },
            text = { Text("删除已保存的接口数据和图片，不影响账号、收藏和浏览历史。之后需要联网重新准备。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    OfflineRuntime.clear(readOnly = false)
                }) { Text("清空") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } },
        )
    }
}

actual fun isOfflineNetworkAvailable(): Boolean = OfflineRuntime.connected()
