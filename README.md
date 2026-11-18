# 知乎水墨（zhihu-eink）

面向 Android 水墨屏设备的知乎客户端，基于 [Zhihu++](https://github.com/zly2006/zhihu-plus-plus) 的 Lite 版本建立。

**已建立 Android Lite 工程基础、固定签名云端构建及本仓库在线更新，并完成墨水屏显示与动画、静态左右切换回答、自动离线缓存与阅读进度恢复。**

## 工程范围

- 仅构建 Android Lite，不提供 Full、Windows、Linux、macOS 或 iOS 应用。
- 移除 Full 专属 ONNX、HanLP、文本向量模型及 Rust tokenizer 构建依赖。
- 保留 `app`、`shared`、`shared-local-db`，继续使用 Kotlin Multiplatform 组织 Android 共享源码。
- 应用名称：知乎水墨；独立包名：`com.github.xiayuhu0414.zhihu.eink`，可与上游并存。
- 最低 Android 8.1（API 27）。
- 更新来源已切换为本仓库 Releases；墨水屏显示策略固定生效，不提供额外的“墨水屏模式”开关。

## 墨水屏显示与动画

- 固定白底黑字、深灰辅助文字及深色分隔线，不随系统深色主题或旧版自定义背景改变。
- 保留深蓝、深绿等强调色和图片、徽章的原有颜色，兼顾黑白与彩色墨水屏；不对内容做全局灰度处理。
- 导航、筛选、列表选中项增加勾选或边框，设置定位使用静态边框，状态辨识不依赖颜色。
- 文章呼吸占位、旋转加载及下拉刷新指示改成静态文字；紧凑按钮用静态沙漏图标标识加载状态。
- 左右滑动切换回答时页面保持静止，松手达到阈值后直接切换，不绘制侧边预览或回弹。
- 应用内部 Compose 动画关闭，Material 控件即时切换；主动翻页、回顶和脚注跳转直接到达，图片不淡入，正文 CSS 过渡关闭。
- 此阶段未接入厂商刷新 SDK，也未控制屏幕全刷、局刷或刷新频率。视频与内容图片保留原有呈现能力。

改造范围与验证说明见 [显示与动画改造记录](docs/eink-display.md)。

## 自动离线缓存

联网时自动准备问题、回答、文章、评论及图片；断网时继续在原有页面阅读已缓存内容，恢复联网后自动使用在线数据。保存阅读位置，支持下次打开时恢复；缓存按账号隔离。

可在“设置 → 系统与更新 → 自动离线缓存”调整准备数量、下载网络、容量及已读保留时间。首次使用需要联网准备；视频文件和离线写操作不在缓存范围内。

配置、缓存边界和验证说明见 [自动离线缓存](docs/offline-cache.md)。

## 云端构建和下载

推送到 `main` 或在 [Actions](https://github.com/xiayuhu0414/zhihu-eink/actions) 手动运行“构建并发布 Android Lite”，检查和构建成功后自动发布测试 APK。

首次从 [Releases](https://github.com/xiayuhu0414/zhihu-eink/releases) 下载签名 APK 安装，后续可通过“设置 → 系统与更新 → 检查更新”升级。发布包含 APK、SHA-256、构建信息和对应源码归档；Actions 的 `zhihu-eink-lite` artifact 额外保留混淆映射。版本及构建号随云端运行递增，沿用固定签名，可保留数据覆盖升级。

具体协议、发布流程和验证边界见 [在线更新说明](docs/online-updates.md)。

签名使用仓库 Actions Secrets：`ANDROID_SIGNING_KEY`（Base64 JKS）、`ANDROID_KEY_ALIAS`、`ANDROID_KEYSTORE_PASSWORD`、`ANDROID_KEY_PASSWORD`。私钥不提交到源码；应妥善保留备份，丢失后无法对原安装覆盖升级。

## 本地构建

安装 JDK 17/21 和 Android SDK，在 `local.properties` 设置 `sdk.dir`，执行：

```sh
./gradlew :app:assembleLiteDebug
```

云端构建使用 `:app:assembleLiteRelease`，并以 `:app:testLiteDebugUnitTest` 验证现有单元测试。原共享测试源码予以保留；其中部分用例依赖原桌面 JVM 实现，尚未迁移到 Android 测试执行面，不计入当前 CI 验证范围。

## 工程维护

本仓库只保留 Android Lite 工程、测试和相关维护文档。已清理上游桌面打包、浏览器扩展、商店宣传素材、独立 Rust 签名及 APK 重压缩工具、静态表情副本和失效的公式基准生成器；业务能力继续沿用 Lite，显示与动画按墨水屏版调整。

源码模块、构建命令和测试边界见 [Android Lite 工程说明](docs/kmp-android-variants.md)，文档入口见 [维护文档](docs/README.md)。仓库内容精简不代表 APK 体积会同比缩小。

## 来源与许可

初始化来源：本地 `/Users/yhx/project/zhihu-plus-plus` 的提交 `04a4e4b8ade8c2d2d7d5442d46a97f586c02d7f4`。

上游项目由 zly2006 及贡献者开发。本仓库保留原有版权声明，沿用 [GNU AGPL-3.0](LICENSE)。上游完整说明可在 [Zhihu++ 仓库](https://github.com/zly2006/zhihu-plus-plus) 查阅。
