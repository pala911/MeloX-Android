# MeloX Android 热更新研究

调研时间：2026-10。目标：在不重新安装 APK 的前提下，尽可能快、尽可能安全地修正线上问题。

## 1. 结论先行

- 现有能力已经覆盖了两头：**配置热更新**（签名云控）与**整包更新**（应用内检查 + GitHub Releases 下载安装）。
- 缺的是中间层：**文案/资源覆盖**、**受限脚本逻辑**、**代码/DEX 热修复**。
- 建议按「低风险 → 高风险」推进：
  1. 资源/文案覆盖（最低风险，收益直接）。
  2. quickjs 受限脚本（复用现有运行时，做非 UI 的动态行为）。
  3. 关键缺陷的 DEX 补丁通道（可选，仅正式版；用 Tinker）。
- **不建议**引入插件化/动态组件框架（VirtualAPK 已归档，Shadow/RePlugin 复杂度与维护成本高，收益不匹配本项目体量）。

## 2. 现有基础（本仓库）

| 能力 | 位置 | 说明 |
| --- | --- | --- |
| 签名云控 | `core/remoteconfig/` | 远程 JSON，签名校验（`MeloXRemoteConfigVerifier`），能力开关 `disabledCapabilities`、回退顺序 `fallback`、公告；按 `versionCode` 区间生效（`appliesTo`）。已能当 kill switch。 |
| 整包更新 | `core/update/MeloXUpdateClient.kt` | GitHub Releases 检查，`isNewer(versionName)` 语义比较；仅允许 `github.com/lladlam/MeloX-Android/releases/download/` 下的 APK。 |
| 脚本运行时 | `quickjs-android`（`LxUserRuntime.kt`） | 目前只执行 lx-music 用户脚本。已有沙箱执行的基础。 |
| 分发渠道 | GitHub Releases | 自签名自分发，不受 Google Play 的动态代码加载政策约束（但见 §5）。 |

## 3. 热更新的“热度”分级与选型

| 方案 | 可热更内容 | 风险 | 生态现状（2026-10） |
| --- | --- | --- | --- |
| 配置热更新 | 开关、顺序、阈值、公告 | 极低 | 已有 |
| 资源/文案覆盖 | 字符串、图片、颜色 | 低 | 自行实现 |
| JS/解释逻辑 | 非 UI 的业务逻辑、解析器 | 中 | quickjs 已内置 |
| DEX/代码热修复 | 任意 Kotlin/Java 方法 | 高 | Tinker 活跃（2026-09 更新）；Robust 停滞（2022）；Sophix 商业闭源 |
| 插件化/动态组件 | 整个模块 | 很高 | Shadow 活跃；RePlugin 一般；VirtualAPK 已归档 |
| 整包/增量更新 | 全部 | 最低 | 已有 |

### 关键外部资料

- Tinker（`Tencent/tinker`，BSD-3，17.6k star，2026-09 仍在更新）：支持 dex、so、资源热修复；需要基线 APK、`TinkerApplication` 改造与补丁包生成插件。
- Robust（`Meituan-Dianping/Robust`）：方法级热修复，最后更新 2022，基本停更。
- Sophix（阿里）：商业方案，需采购。
- Shadow（`Tencent/Shadow`）：插件框架，活跃但对本项目过重。
- Google Play「Device and Network Abuse」政策：禁止下载并执行动态代码（dex/so），Play 内应用需遵守；MeloX 走 GitHub 自分发，不直接受此约束，但仍是安全与合规的参考底线。

## 4. 推荐落地设计

### 4.1 统一的「热更新清单」

复用云控的签名校验与镜像路由，定义一个带签名的清单（可并入现有 `android-models.json` 同款模式）：

```json
{
  "hotUpdateVersion": 3,
  "minVersionCode": 22,
  "maxVersionCode": 40,
  "resources": [
    { "name": "strings-zh.json", "sha256": "...", "url": "..." }
  ],
  "scripts": [
    { "name": "lx-default.js", "sha256": "...", "url": "..." }
  ],
  "patches": [
    { "abi": "arm64-v8a", "name": "patch-0.6.4-1.dex", "sha256": "...", "url": "..." }
  ]
}
```

- 所有条目强制 SHA-256 校验，且下载源限定在固定 GitHub release 前缀（与 `AutoSyncModelRepository.requireTrusted` 同思路）。
- 清单本身用与云控相同的签名公钥验证，防篡改。
- 失败一律回退到内置默认，绝不因热更新内容崩溃。

### 4.2 资源/文案覆盖（M1 已实现）

实现方式：**覆盖表直接放进已签名的云控 payload**，不新建下发通道。

- 客户端：`core/remoteconfig/MeloXHotStrings.kt` 保存「已验签且适用于当前版本」的覆盖表；
  `MeloXRemoteConfigRuntime` 在有效配置变化时刷新它（撤回授权 / 回滚 / 清缓存都会立刻清空）。
- UI：`ui/MeloXHotText.kt` 提供两个入口——`hotText("key", 默认值)` 用于内联文案，
  `hotStringResource(R.string.xxx)` 用资源 entry name 作 key。命中覆盖用覆盖值，否则用内置文案，
  离线行为与不开启完全一致。
- 格式：`textOverrides: { "<localeTag>": { "<key>": "<text>" } }`，查找顺序为
  精确 locale（如 `zh-CN`）→ 语言（`zh`）→ `default`。
- 边界（客户端与 admin 校验一致）：条目 ≤ 256、locale ≤ 24 字符、key ≤ 120、value ≤ 400；
  超限条目直接丢弃，不会让整份配置失效。
- 只用于展示文案，安全/协议类字符串不接入。

已接入的 key（key 需跨版本稳定）：

| key | 来源 | 位置 |
| --- | --- | --- |
| `about.maintainer` | 内联 key | 关于页维护者信息 |
| `about.upstream` | 内联 key | 关于页上游项目 |
| `about.cat_title` | 内联 key | 彩蛋标题 |
| `about.cat_body` | 内联 key | 彩蛋正文 |
| `settings_autosync_word` | 资源 entry name | AutoSync 开关标题 |
| `settings_autosync_word_note` | 资源 entry name | AutoSync 开关说明 |

新增文案时，把内联字符串换成 `hotText("模块.用途", "默认值")`（或 `hotStringResource`）即可。

服务端：`MeloX-Remote-Config` 的 schema、`RemoteConfig` 模型与 `RemoteConfigValidator` 已同步支持
该字段并通过测试；admin GUI 尚无覆盖编辑器，保存时会原样保留已加载的覆盖表。

### 4.2b 主题色覆盖（M1 扩展，已实现）

```json
"colorOverrides": {
  "primary": "#FF8800",
  "dark.background": "#0B0B0D",
  "light.surface": "#FDFDFE"
}
```

- key 为 Material 角色名（`primary` / `onPrimary` / `primaryContainer` / `onPrimaryContainer` /
  `secondary` / `tertiary` / `error` / `background` / `onBackground` / `surface` / `onSurface` /
  `surfaceVariant` / `onSurfaceVariant` / `outline`），可加 `light.` / `dark.` 前缀只覆盖单模式；
  不带前缀则两种模式都生效。
- 取值必须是 `#RRGGBB` 或 `#AARRGGBB`；角色未知、格式错误或条目超过 48 条直接丢弃。
- 客户端在 `MeloXTheme` 唯一入口处应用（`ui/theme/MeloXHotColors.kt`），因此底栏插值、
  玻璃材质等派生颜色会跟着一起变；未覆盖的角色保持内置配色。

### 4.3 quickjs 受限脚本（M2 已实现）

脚本同样随**已签名的云控 payload** 下发，字段为：

```json
"scripts": {
  "search.keywords": {
    "revision": 3,
    "source": "function run(input) { return { keywords: input.keywords.trim() }; }"
  }
}
```

客户端 `core/remoteconfig/MeloXHotScripts.kt` 的执行约束：

- 每次调用创建**全新的 QuickJS context**（无跨调用状态），设置 8 MB 内存上限与 256 KB 栈上限；
- 单线程 daemon executor + **600ms 墙钟超时**；超时后**整个脚本通道在本进程内禁用**，
  不会反复消耗 CPU；
- 脚本必须定义 `function run(input)` 并返回可 JSON 序列化的值；输入以 `JSON.parse`
  传入字符串字面量，不做属性注入；
- 任何失败（语法、异常、缺 hook、超时）一律返回 `null`，调用方回落到内置逻辑。

首批接线 hook：**`search.keywords`**——网易云搜索关键词在请求前经过它（
`NeteaseSearchClient.searchSongs`），用于修掉关键词类线上问题。

不适合放进脚本：UI 结构、播放器状态机（会变成两套状态源）、需要长耗时/网络的逻辑。

### 4.4 DEX 热修复（M3 决策：暂不引入）

结论：**当前不引入 Tinker/Sophix 等 DEX 补丁框架**。理由：

- 本项目自签名自分发，但 Tinker 需要 `TinkerApplication` 入口改造、基线 APK 归档、
  补丁 CI、补丁服务器与回滚链路，改动面覆盖启动路径，回归成本大于收益；
- 已有「签名配置 + 签名脚本 + 应用内整包更新」三层，绝大多数线上问题（文案错误、
  解析规则、开关与回退策略）无需 DEX；
- DEX 下发是任意代码执行面，密钥泄露即等于可投毒，安全责任远高于收益。

重新评估的条件（满足其一再考虑）：出现必须改 Native/启动路径的线上阻断问题，
且整包更新无法在可接受时间内覆盖用户；或接入 Google Play 后仍需要 Play 之外的补丁通道。

### 4.5 增量 APK 更新（M4 已实现）

整包 82 MB 里 58 MB 是 ONNX Runtime 原生库、9.6 MB 是 dex；只改代码时用户却要下整包。
增量更新用 **BSDIFF40** 补丁解决：设备端从「已安装的 APK + 补丁」重建出目标 APK。

- 发布期生成：`tools/make_apk_patch.sh <旧.apk> <新.apk> <out.patch> <version>`（依赖参考实现
  `bsdiff4`），并直接打印可粘贴进云控的 `updatePatches` 条目。
- 客户端：`core/update/BsPatch.kt` 实现 BSDIFF40 应用（bzip2 三个流按头部声明长度切片，
  commons-compress 解压）；`MeloXApkDelta` 负责重建、校验、拉起系统安装器。
- 校验链：补丁体积 + 补丁 SHA-256 → 重建后的 APK SHA-256 必须等于发布的
  `apkSha256` → 安装器再校验签名与已安装应用一致。任一步不符就回落整包下载。
- 实测：0.6.4 → 0.6.5 的补丁 **4.8 MB**（整包的 5.9%），设备端重建耗时 <1 秒。
- 权限：新增 `REQUEST_INSTALL_PACKAGES`，只用于「增量更新」这一显式操作。

`updatePatches` 条目（由脚本生成）：

```json
{
  "fromApkSha256": "...", "toVersion": "0.6.5", "apkSha256": "...",
  "patchUrl": "https://github.com/lladlam/MeloX-Android/releases/download/<tag>/xxx.patch",
  "patchSha256": "...", "patchBytes": 5016788
}
```

URL 必须位于固定 release 前缀下（客户端与 admin 双向校验），条目 ≤4 条、单补丁 ≤64 MB。

## 5. 风险与合规

- 安全：DEX/JS 下发是任意代码/逻辑执行面；必须签名校验 + 固定下载源 + 失败回退。密钥泄露等同于可被投毒。
- 平台：若未来上架 Google Play，DEX/SO 热更新会被判定违规；资源与 JS（解释执行）的边界也要谨慎。
- 完整性：可结合现有签名证书（CN=MeloX）与 `apksigner` 校验，防止中间人替换补丁。
- 可观测：热更新必须带版本与命中率埋点，且能被云控一键关闭。

## 6. 里程碑建议

| 阶段 | 内容 | 状态 |
| --- | --- | --- |
| M1 | 签名文案覆盖（并入云控 payload；客户端 + admin + 测试） | **已完成** |
| M1b | 主题色覆盖（`colorOverrides`，`MeloXTheme` 单一入口应用） | **已完成** |
| M2 | 签名脚本沙箱（quickjs、内存/超时限制、`search.keywords` 接线） | **已完成** |
| M3 | DEX 热修复决策 | **已完成：暂不引入**（见 4.4） |
| M4 | 规则/策略数据化（`rules`、`strategies=compat`） | **已完成** |
| M5 | 增量 APK（BSDIFF40 + 重建校验 + 安装） | **已完成** |
| — | 插件化 | 不做 |

## 7. 使用方法

### 7.1 客户端行为

- 只有「已授权云控 + 配置验签通过 + `versionCode` 在区间内」时，文案/颜色覆盖与脚本才生效；
  其余情况一律用内置文案、内置配色与内置逻辑。
- 撤回云控授权、清缓存或配置回滚，覆盖与脚本会立即失效。
- 脚本通道一旦超时会**在本进程内关闭**，重启应用后恢复。

### 7.2 维护者发布覆盖/脚本

1. 在 `MeloX-Remote-Config` 仓库里编辑 `config/current-v1.json`，加入 `textOverrides` /
   `colorOverrides` / `scripts`，并把 `configVersion` +1。
2. 启动 admin（`./gradlew :admin:run`，工作目录为仓库根）→ 加载当前配置 → 保存并发布，
   或使用 CLI：`bootstrap <口令文件>`（会重新签名并发布）。
3. 发布产物落到 `MeloX-Remote-Config-Public` 的 `v1/latest.json`；客户端最迟在下次前台
   刷新（最多 2 小时）拿到。

### 7.3 写一条文案覆盖

```json
"textOverrides": {
  "zh-CN": { "about.cat_title": "新的彩蛋标题" },
  "default": { "about.cat_title": "New easter-egg title" }
}
```

key 见 §4.2 的表格；新增 key 时把内联文案改成 `hotText("模块.用途", "默认值")`。

### 7.4 写一条颜色覆盖

```json
"colorOverrides": { "dark.primary": "#FF375F", "light.primary": "#007AFF" }
```

配合系统深浅色自动切换；不带前缀的 key 对两种模式都生效。

### 7.5 发布一条增量补丁

```bash
python3 -m venv .venv && .venv/bin/pip install bsdiff4
PYTHON=.venv/bin/python tools/make_apk_patch.sh \
  /path/MeloX-Android-0.6.4.apk /path/MeloX-Android-0.6.5.apk melox-0.6.4-to-0.6.5.patch 0.6.5
```

把输出的 `updatePatches` 数组粘进 `config/current-v1.json`（`configVersion` +1），并把 patch 文件
作为 release 资产上传；用户端在关于页看到「增量更新 X.Y.Z（约 N MB）」，点击即重建并拉起安装器。

### 7.6 写一条脚本 hook

```json
"scripts": {
  "search.keywords": {
    "revision": 1,
    "source": "function run(input) { return { keywords: input.keywords.replace(/【.*?】/g, '').trim() }; }"
  }
}
```

约束：脚本 ≤ 16 KB、总量 ≤ 32 KB、最多 8 条；必须定义 `function run`；只做纯函数变换。

## 8. 与 AutoSync 模型下发的复用

模型包（`android-models-v0.1.0-beta.2`）已经是「签名索引 + 固定 release + 逐文件 SHA-256 + 原子安装 + 失败回退」的成熟模式，热更新清单应直接复用同一套下载/校验/镜像代码，避免再造一套下发通道。
