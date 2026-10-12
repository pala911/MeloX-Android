# AutoSync Android 音频链路回归

## 实现

- MediaCodec 异步回调解码，独立工作线程；播放管线保持原有实现。
- 只对已知支持多帧输入的 `c2.android.mp3.decoder` 合并最多 16 个完整 MP3 帧。其他解码器仍使用单 access unit 输入。
- 63 抽头抗混叠 FIR 流式降采样到 16 kHz 单声道。工作缓冲固定大小，整首 PCM 写入临时文件，推理时只读取一行窗口。
- 按输出 PTS 裁剪并保留时间轴原点，包括 gapless trimming 后的起点空隙；MP3 避免近似 seek，尾部额外喂入少量数据以处理解码延迟。
- 文件在成功、失败、协程取消时关闭和删除。生成串行化，切歌/关闭开关终止旧任务；缓存校验歌曲、歌词 SHA-256 和模型/音频管线版本。
- 本地模型生成结果具有独立来源标记，允许超过原 AMLL 30 秒升级窗口后更新当前歌曲；人工逐字源的优先级保留。

## 真机结果（2026-10-11）

设备上已经缓存的标准音质 MP3：48 kHz、立体声、约 211.8 秒。音频、歌词和模型权重未加入仓库。

| 检查 | 结果 |
| --- | --- |
| 20 秒片段解码 | 538 ms |
| 10.013 秒非帧对齐起点，3 秒窗口 | 48,000 个采样 |
| 批量完整解码与单帧裁剪解码的窗口对比 | 平均绝对误差 0 |
| 英文整首解码 | 4,692 ms |
| 英文真实 ONNX + CTC 对齐 | 47/47 行，322 个单词，失败 0 行 |
| 解码与对齐总耗时 | 49,580 ms（含模型获取的调用耗时约 50,043 ms） |
| 持久化后重新读取 | 与生成文档一致 |

这些检查证明音频获取、真实推理、时间轴生成和持久化链路能够运行，**不代表逐字精度通过人工真值验收**。模型仍为 `NOT_ACCEPTED_FOR_WORD_LEVEL_ACCURACY`。日文 G2P 尚未实现，仍不可用。中文使用独立 CIF 路径；先前真机发现其复用的 ONNX direct-buffer 未限长，短句失败，已修正有效长度并添加 CIF 回归。中文逐字精度仍未人工验收。

## 复现

测试 runner 仅位于 `androidTest` APK，不进入应用 APK，无新增测试框架依赖。参数指定设备上已有的音频文件；只有指定 `songId` 时才获取对应供应商歌词并做完整生成。

```sh
gradle :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument --user 0 -w \
  -e audio cache/automix_analysis/EXISTING_FILE.media \
  -e endMs 20000 \
  -e songId MATCHING_NETEASE_SONG_ID \
  com.lladlam.melox.android.dev.test/com.lladlam.melox.core.autosync.AutoSyncDeviceRunner
```

不传 `songId` 可仅测解码及采样对比；音频必须与指定歌曲真实对应。成功输出 `AutoSync device regression passed`，包括耗时、采样差异及生成单词数。日志标签为 `MeloXAutoSync` 和 `MeloXLyricsAuto`。
