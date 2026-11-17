# 知乎水墨（zhihu-eink）

面向 Android 水墨屏设备的知乎客户端项目基础，基于 [Zhihu++](https://github.com/zly2006/zhihu-plus-plus) 的 Lite 版本建立。

**当前仅完成工程初始化与云端构建配置，尚未进行水墨屏功能、主题或交互改造。应用功能保持上游 Lite 行为。**

## 工程范围

- 仅构建 Android Lite，不提供 Full、Windows、Linux、macOS 或 iOS 应用。
- 移除 Full 专属 ONNX、HanLP、文本向量模型及 Rust tokenizer 构建依赖。
- 保留 `app`、`shared`、`shared-local-db`，继续使用 Kotlin Multiplatform 组织 Android 共享源码。
- 应用名称：知乎水墨；独立包名：`com.github.xiayuhu0414.zhihu.eink`，可与上游并存。
- 最低 Android 8.1（API 27）。
- 当前上游业务行为（含检查更新来源）保持不变；后续水墨屏改造另行实施。

## 云端构建和下载

推送到 `main` 或在 [Actions](https://github.com/xiayuhu0414/zhihu-eink/actions) 手动运行“构建 Android Lite”即可构建 APK。

成功后在该次运行的 Artifacts 下载 `zhihu-eink-lite`，解压得到签名的 Release APK、SHA-256 校验文件和混淆映射。APK 名称与版本号包含云端构建序号，可覆盖升级同一签名的安装。

签名使用仓库 Actions Secrets：`ANDROID_SIGNING_KEY`（Base64 JKS）、`ANDROID_KEY_ALIAS`、`ANDROID_KEYSTORE_PASSWORD`、`ANDROID_KEY_PASSWORD`。私钥不提交到源码；应妥善保留备份，丢失后无法对原安装覆盖升级。

## 本地构建

安装 JDK 17/21 和 Android SDK，在 `local.properties` 设置 `sdk.dir`，执行：

```sh
./gradlew :app:assembleLiteDebug
```

云端构建使用 `:app:assembleLiteRelease`，并以 `:app:testLiteDebugUnitTest` 验证现有单元测试。共享模块的测试使用 `:shared:testAndroidHostTest`。

## 来源与许可

初始化来源：本地 `/Users/yhx/project/zhihu-plus-plus` 的提交 `04a4e4b8ade8c2d2d7d5442d46a97f586c02d7f4`。

上游项目由 zly2006 及贡献者开发。本仓库保留原有版权声明，沿用 [GNU AGPL-3.0](LICENSE)。原项目说明见 [docs/upstream-readme.md](docs/upstream-readme.md)。
