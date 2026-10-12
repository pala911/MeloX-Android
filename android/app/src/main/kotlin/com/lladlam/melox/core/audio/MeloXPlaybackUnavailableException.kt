package com.lladlam.melox.core.audio

import java.io.IOException

/**
 * 音源**不可用**：会员/VIP 未授权、版权受限、地区受限，或压根拿不到播放链接。
 *
 * ⚠ 它必须和「网络抖动 / 超时 / 断流」这类**可恢复**错误分开处理：
 *   · 可恢复 ⇒ 等 Media3 自己重试，或者等网络回来；
 *   · 不可用 ⇒ **重试一万次也拿不到链接**。
 *
 * 旧行为把所有错误一律当成可恢复：在线时 `onPlayerError` 只打一行 log，播放器就此
 * 卡在 `STATE_IDLE` + 错误态、永远不出声。后果有两个（用户实测）：
 *   1. 迷你栏滑过去后，`MeloXIOSMiniPlayer` 的 1.5s 兜底会把内容弹回原来那首 ⇒
 *      「怎么滑，一段时间后都会变回原来的歌曲」；
 *   2. 而 `onMediaItemTransition` 已经把这首**根本没出声**的歌写进了播放历史和
 *      本地推荐记录 ⇒ 「历史记录已经算上了那首没音源的」。
 *
 * 打上这个类型后，`MeloXPlaybackService.onPlayerError` 才能认出它并**向前跳过**。
 */
open class MeloXPlaybackUnavailableException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)
