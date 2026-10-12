# 翻译目录 / Translations

这个目录专门放**语言资源**，和代码、默认文案分开，方便任何人贡献自己的语言。

- 默认（英文）文案：`../src/main/res/values/strings.xml` —— **不要改**，它是 key 的唯一来源。
- 每种语言一个文件夹：`values-<BCP-47>/strings.xml`。

现有语言：

| 目录 | 语言 |
| --- | --- |
| `values-zh-rCN/` | 简体中文 |
| `values-zh-rTW/` | 繁体中文 |
| `values-ja/` | 日语 |

## 新增一种语言（3 步，不需要改任何 Kotlin 代码）

1. **复制** `../src/main/res/values/strings.xml`，改名为 `values-<你的语言>/strings.xml`。
   - 目录名用 Android 的 BCP-47 规则，例如：土耳其语 `values-tr`、法语 `values-fr`、
     德语 `values-de`、韩语 `values-ko`、俄语 `values-ru`。
   - 地区变体用 `-r`，例如巴西葡萄牙语 `values-pt-rBR`。
2. **翻译 `<string>` 的内容**，`name="..."` 一个字都不要改。
   - 带占位符的（`%1$s`、`%1$d`）必须保留占位符；顺序可以用 `%1$s`/`%2$s` 调整。
   - 有 `translatable="false"` 的条目不用翻译（通常是品牌名或纯符号）。
   - 找不到对应项的 key 可以省略，运行时会自动回落到英文。
3. **登记语言**：在 `../src/main/res/xml/locales_config.xml` 里加一行
   `<locale android:name="tr" />`（用 BCP-47 名称），这样 Android 13+ 的
   「应用语言」列表里才会出现它。

提交时只需带上 `values-<语言>/strings.xml` 和 `locales_config.xml` 两处改动。

## 注意

- 只翻译**用户可见文案**；不要改 key 名，也不要翻译日志、协议字段。
- 不确定的地方留空或沿用英文，比机翻更安全：缺失条目会自动回落。
- 文案里有 `%` 时保持原样（它是格式化占位符的一部分）。
