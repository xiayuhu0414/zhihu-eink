# 知乎水墨在线更新

源码、Actions 构建和 APK 分发统一使用公开仓库 `xiayuhu0414/zhihu-eink`。更新不再访问上游 Zhihu++、其镜像或 Nightly，也不需要在设备上配置 GitHub 令牌。

## 使用方法

1. 首次从本仓库 Releases 安装带新更新源的签名 APK。
2. 进入“设置 → 系统与更新”，点击“检查更新”。有新版时查看版本和更新说明，点击“下载更新”。
3. 完成 SHA-256、大小、包名、版本、最低系统要求及当前安装签名校验后，点击“安装更新”。首次可能需要允许本应用“安装未知应用”；返回后再次点击安装。
4. Android 安装器确认后覆盖安装。包名与固定签名保持一致，不需要卸载或清除数据。自行使用其他 debug 密钥构建的 APK 不能直接覆盖云端签名版本。

“自动检查更新”默认开启，启动应用时每 24 小时最多检查一次，可以关闭。手动检查不受每日间隔限制；跳过版本只影响自动提示。不会自动下载或静默安装。下载时离开设置页可能取消下载，可重新检查和下载；进程退出后需重新检查下载，不承诺断点续传。下载依赖设备能够访问 GitHub API 和 Release 下载地址。

## 测试通道协议

- 查询：`https://api.github.com/repos/xiayuhu0414/zhihu-eink/releases?per_page=100`。
- 只接收非草稿、标记为预发布、标签匹配 `test-v0.1.<构建号>` 的完整发布。
- `versionName` 为 `0.1.<构建号>`，`versionCode` 为同一 Actions 工作流的 `github.run_number`，按 code 判断更新，不依赖发布时间或列表顺序。
- 必须包含 `zhihu-eink-lite-0.1.<构建号>.apk` 与 `SHA256SUMS.txt`；地址必须严格位于本仓库对应标签下。
- APK 大小上限 128 MiB；校验文件上限 16 KiB。下载仅允许 HTTPS 和 GitHub 资产 CDN 跳转，下载大小必须与发布信息一致。
- APK 下载后、安装前各校验一次。拒绝错误包名、错误版本、相同或更低 code、不支持的系统、不同签名及文件篡改。
- 此通道只用于测试构建。未来改变版本命名或加入稳定通道时，需要同步调整发布脚本与应用筛选规则。

## 云端发布

推送 `main` 或手动运行 `.github/workflows/android.yml`：

1. `build` 运行发布脚本测试、Android 单元测试，使用已有四项 Actions Secrets 签名构建 Release APK。
2. 核对 APK 身份、版本和已发布固定证书；收集 APK、摘要、构建来源信息、AGPL-3.0 许可及对应提交的源码归档。混淆映射仍保留在 Actions artifact 中。
3. 独立 `publish` 任务下载本次运行产物，重新核对摘要、证书、提交和运行编号；仅此任务拥有仓库 `contents: write` 权限。
4. 使用本仓库自动生成的 `GITHUB_TOKEN` 创建测试草稿，上传全部文件，核对附件齐全后才公开为 Pre-release，不标记为稳定版 latest。失败草稿可重试；已有更新版本时拒绝发布旧构建；公开同名产物不覆盖，重跑只核对一致性。

固定签名证书 SHA-256：

```text
955081fa13f79f08a20f7188a3a0602e4cd4545757b79a46f265595b092e5b4f
```

此指纹来自已签名的首次基础版本 `0.1.3`。私钥和密码只在本地忽略目录及 Actions Secrets 中保存，不进入源码、日志或 APK。保留现有签名和此工作流的构建编号，避免破坏覆盖升级。

## 本地验证

```sh
python3 -B -m unittest discover -s scripts -p 'test_*.py'
./gradlew :app:testLiteDebugUnitTest :app:assembleLiteDebug --no-configuration-cache --max-workers=2
```

应用更新协议的回归测试位于 `app/src/test/.../updater/EinkUpdateTest.kt`，会在现有 Android 单元测试任务中执行。历史 `shared/commonTest` 仍未迁移到 Android 执行面，不计入本轮 CI 的通过范围。安装和启动核验不代表已完成墨水屏效果或完整应用内升级验收。
