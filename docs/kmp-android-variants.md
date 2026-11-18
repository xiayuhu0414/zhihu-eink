# Android Lite 工程说明

本仓库仅构建 Android Lite，保留 Kotlin Multiplatform 的源码组织方式，不包含 Full 或桌面目标，也不再根据任务名动态切换版本实现。

## 源码模块

- `app`：Android 应用入口、资源、Lite flavor 及应用测试。
- `shared/src/commonMain`：界面、账号、网络协议和业务状态。
- `shared/src/androidMain`：Android 平台实现。
- `shared/src/androidLite/kotlin`：Lite 专属实现，由 `shared/build.gradle.kts` 显式加入 Android 主源码集。
- `shared-local-db`：Room 本地数据库及 Android 驱动。
- `buildSrc`：构建时读取 Git 信息。

应用安装包名为 `com.github.xiayuhu0414.zhihu.eink`。源码命名空间和 Compose 资源包名继续沿用上游，避免无收益的大范围重命名。

## 构建与验证

安装 JDK 17/21 和 Android SDK，在 `local.properties` 配置 `sdk.dir`。

```sh
./gradlew :app:testLiteDebugUnitTest :app:assembleLiteDebug
python3 -B -m unittest discover -s scripts -p 'test_*.py'
```

Debug APK 位于 `app/build/outputs/apk/lite/debug/`。Release 使用 `:app:assembleLiteRelease`，APK 位于 `app/build/outputs/apk/lite/release/`；签名和自动发布以 `.github/workflows/android.yml` 及 [在线更新说明](online-updates.md) 为准。

当前 CI 执行 `app` 的 Lite Debug 单元测试、发布脚本测试和签名 Release 构建。`shared/src/commonTest` 的源码继续保留，其中部分用例依赖已移除的桌面 JVM 实现，尚未迁移到 Android 测试执行面，不计入当前 CI 覆盖范围。

`app/src/androidTest` 保留仪器测试及测试素材，需要设备或模拟器另行执行；普通编译和单元测试不能替代这些业务验证。未经用户要求，不进行本地视觉复核。
