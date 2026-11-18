# Android Lite UI 维护指南

本文件只描述当前仓库的 UI 入口。修改前应读取对应源码，以实际实现为准。

## 主要入口

- `shared/src/commonMain/kotlin/com/github/zly2006/zhihu/navigation/NavDestination.kt`：类型化导航目标。
- `shared/src/commonMain/kotlin/com/github/zly2006/zhihu/ui/ZhihuMain.kt`：主界面、底栏和导航注册。
- `app/src/main/java/com/github/zly2006/zhihu/MainActivity.kt`：Android 入口、外链解析和平台导航。
- `app/src/main/java/com/github/zly2006/zhihu/ui/ZhihuMainAndroidState.kt`：Android 主界面状态。
- `shared/src/commonMain/kotlin/com/github/zly2006/zhihu/ui/subscreens`：设置页。
- `shared/src/commonMain/kotlin/com/github/zly2006/zhihu/ui/components`：信息流卡片、设置项、刷新和翻页等共用组件。

## 修改时的关联范围

导航使用 `LocalNavigator`；新增 route 时同步检查 `NavDestination`、`ZhihuMain` 和 Android 入口。主标签由 `TopLevelDestination`、底栏显示偏好和启动页共同决定，不能只修改单个入口。

主题由 `ThemeManager`、`ZhihuTheme` 和 Android 主题运行时共同管理。修改字号、行高、信息流卡片或文章操作栏时，先查偏好键在 `shared` 与 `app` 中的全部读写点，确认默认值、实时生效范围和设置入口。

正文新能力优先接入 Compose Markdown。`ARTICLE_USE_WEBVIEW_PREFERENCE_KEY` 对应的 WebView 正文路径已经废弃，仅保留紧急回退，不应成为新增阅读功能的主路径。正文依赖见 [Markdown 依赖说明](markdown-library-dependency.md)。

翻页复用 `DraggablePageTurnButtons.kt` 中的 dispatcher、target 和 viewport 机制；外观设置已有音量键翻页、悬浮按钮、翻页距离和位置线选项。修改时检查正文、列表及嵌套滚动页面的实际接入。

通用设置项优先复用 `SettingItem` 系列组件。可测试的交互沿用稳定 test tag，涉及账号、通知或阅读偏好时检查对应 store，避免另起一份状态。

## 项目约定与验证

遵循根目录 `AGENTS.md`：先获得实施授权；未经用户要求不进行本地视觉复核；Git 提交备注使用中文。

编译和单元测试入口见 [Android Lite 工程说明](kmp-android-variants.md)。需要仪器测试时按实际改动选择已有用例；当前 CI 不执行仪器测试，也不覆盖全部共享测试。更新来源、版本校验和发布行为以 [在线更新说明](online-updates.md) 为准。
