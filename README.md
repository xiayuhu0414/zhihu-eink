# 知乎水墨（zhihu-eink）

面向 Android 水墨屏设备的知乎客户端项目基础，基于 [Zhihu++](https://github.com/zly2006/zhihu-plus-plus) 的 Lite 版本建立。

**已建立 Android Lite 工程基础、固定签名云端构建及本仓库在线更新。尚未进行墨水屏主题、翻页或刷新适配；其他业务功能沿用上游 Lite。**

## 工程范围

- 仅构建 Android Lite，不提供 Full、Windows、Linux、macOS 或 iOS 应用。
- 移除 Full 专属 ONNX、HanLP、文本向量模型及 Rust tokenizer 构建依赖。
- 保留 `app`、`shared`、`shared-local-db`，继续使用 Kotlin Multiplatform 组织 Android 共享源码。
- 应用名称：知乎水墨；独立包名：`com.github.xiayuhu0414.zhihu.eink`，可与上游并存。
- 最低 Android 8.1（API 27）。
- 更新来源已切换为本仓库 Releases；其他业务功能保持上游行为，后续墨水屏改造另行实施。

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

## 来源与许可

初始化来源：本地 `/Users/yhx/project/zhihu-plus-plus` 的提交 `04a4e4b8ade8c2d2d7d5442d46a97f586c02d7f4`。

上游项目由 zly2006 及贡献者开发。本仓库保留原有版权声明，沿用 [GNU AGPL-3.0](LICENSE)。原项目说明见 [docs/upstream-readme.md](docs/upstream-readme.md)。
