# AGENT.md

面向在本仓库工作的自动化代理与协作者。这里只写“怎么干活”和容易踩的坑；功能说明看
[`README.zh-Hans.md`](README.zh-Hans.md)，真机 AutoSync 复现看
[`docs/AUTOSYNC_DEVICE_REGRESSION.md`](docs/AUTOSYNC_DEVICE_REGRESSION.md)。

## 仓库结构

- `android/` — 原生 Android 工程（Kotlin + Jetpack Compose），真正的代码在这里。
  - `android/app/` — 主应用模块。
  - `android/innertube/` — vendored 的 YouTube Music InnerTube 模块。
  - `android/hidden-api/` — 隐藏 API 桥接。
- `docs/` — 设计与回归文档。
- `releases/`、`release/` — 历史产物目录（正常不应新增二进制）。
- 根目录 `README*.md`、`CHANGELOG.md` — 发版时要同步更新。

## 工具链

- JDK 17、Android SDK 37（`compileSdk`/`targetSdk` 均为 37）、build-tools 37.0.0。
- Gradle 用工程的 wrapper；本机未必有全局 `gradle`。可用：

```bash
"$HOME/.gradle/wrapper/dists/gradle-9.5.1-bin/bvvv0hnjcinfezlom7fy9dbyi/gradle-9.5.1/bin/gradle" --version
```

- `android/local.properties` 里 `sdk.dir` 指向本机 SDK，未提交。

## 构建与测试

在 `android/` 下执行。CI 与本地一致的命令：

```bash
# 单测 + Debug APK（CI 用的组合）
gradle :app:testDebugUnitTest :app:assembleDebug --no-daemon --max-workers=2 --stacktrace

# 本机受限环境常用（离线、单 worker、限制 JVM）
gradle :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest \
  --offline --no-daemon --max-workers=1 \
  -Dorg.gradle.jvmargs="-Xmx1536m -XX:MaxMetaspaceSize=384m -XX:+UseSerialGC"
```

- 产物：`android/app/build/outputs/apk/debug/app-debug.apk`。
- 单测结果 XML：`android/app/build/test-results/testDebugUnitTest/TEST-*.xml`。
- 若新增依赖且本机缓存里没有，`--offline` 会失败，去掉该参数让 Gradle 联网拉取一次。

## 真机回归（AutoSync）

测试包为 `com.lladlam.melox.android.dev`，runner 是
`com.lladlam.melox.core.autosync.AutoSyncDeviceRunner`：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument --user 0 -w \
  -e audio cache/automix_analysis/<file>.media \
  -e songId <neteaseSongId> \
  com.lladlam.melox.android.dev.test/com.lladlam.melox.core.autosync.AutoSyncDeviceRunner
```

设备上的音频常被缓存淘汰，先 `run-as ... ls cache/automix_analysis` 确认存在再用。
日志过滤：`adb logcat -d -s MeloXAutoSync:I MeloXLyricsAuto:D`。

## 发版与签名

- 签名读取顺序：`-PmeloxRelease*` / `$GRADLE_USER_HOME/gradle.properties`，其次
  `android/keystore.properties`（已被 `.gitignore`，不要提交）。
- `android/keystore.properties` 需要四个键：
  `meloxReleaseStoreFile`、`meloxReleaseStorePassword`、`meloxReleaseKeyAlias`、`meloxReleaseKeyPassword`。
- 正式版用官方包名（无后缀）：`-PmeloxReleaseIdSuffix=`（默认会加 `.fork`）。
- 构建正式 APK：

```bash
gradle :app:assembleRelease -PmeloxReleaseIdSuffix= --no-daemon --max-workers=1
# 产物 app/build/outputs/apk/release/app-release.apk
```

- 校验：`aapt2 dump badging` 看 `package`/`versionName`；`apksigner verify --print-certs` 看证书。
- 发布：`gh release create <tag> <apk> --title "MeloX Android <ver>" --notes-file <file> --latest`，
  资产名沿用 `MeloX-Android-<ver>.apk`。
- 应用内更新用 `MeloXUpdateClient.isNewer(versionName)` 做**语义化版本**比较：
  **同版本号不会被提示更新**，修复版必须抬高 `versionName`。

## 版本号

- `android/app/build.gradle.kts` 的 `versionName` 是唯一事实来源；`versionCode` 随发版递增。
- Debug / debugPerf 使用 `.dev` 包名后缀，可与正式包共存。

## AutoSync（本地逐字歌词）

- 模型从 `MeloX-AutoSync` 的 pinned release 下载，索引固定版本与 SHA-256 写在
  `AutoSyncModelRepository`；改动 URL/hash 必须同步更新 `AutoSyncRoutingTest`。
- 英文走 CTC，中文走独立 CIF；日文在设备端 G2P 就绪前标记为不可用。
- 模型为 `NOT_ACCEPTED_FOR_WORD_LEVEL_ACCURACY`：不得把“能跑通”写成“精度通过”。
- 不要提交 ONNX 权重、音频或歌词到本仓库。

## 贡献者分支合并的坑（重要）

`contributor` 分支虽然以 `origin/main` 为父提交，但内容是**基于更早的主线树**重建的，
直接合并会**静默回退主线近期的修复/功能**（典型：`SecureSessionPreferences` 的 keystore
崩溃修复、`proguard-rules.pro`、`79a4bc2` 歌手页/歌单、`288b61c2` 的 Mei 背景移除等）。

合并前务必逐提交核对“主线的改动是否还在”，合并后用
`git checkout <main> -- <被回退的文件>` 之类的方式把主线修复还原，再跑单测与真机验证。

## 多语言（i18n）

- 默认文案（英文）在 `android/app/src/main/res/values/strings.xml`，是 key 的唯一来源。
- **各语言放在独立目录** `android/app/i18n/values-<BCP-47>/strings.xml`，已通过
  `build.gradle.kts` 的 `res.srcDir("i18n")` 注册进资源合并；新增语言只需加一个目录，
  不需要改 Kotlin 代码。贡献说明见 `android/app/i18n/README.md`。
- 新增语言还要在 `res/xml/locales_config.xml` 里登记，Android 13+ 的「应用语言」才会列出。
- 代码里一律用 `stringResource(R.string.x)`；**id 类字符串（如歌单分类 "华语"）保持字面量**，
  它们是与数据/逻辑比较用的键，不能本地化。
- 缺失的翻译会自动回落到默认（英文）。

## 代码约定

- 注释与文档用中文，标识符用英文；保持与相邻代码一致。
- 不新增 `// TODO` 式的占位；无法处理的情况要显式回退并保持可见。
- 改动后至少运行相关单测；涉及 UI/播放链路的改动应做真机冒烟。

## 已知问题

- `values-*` 的系统多语言机制在该分支被内联中文替代（见 CHANGELOG 0.6.3）。
- 日文 AutoSync 依赖未打包。
- 详见 [`docs/known-issues.md`](docs/known-issues.md)。
