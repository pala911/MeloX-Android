package com.lladlam.melox.playback

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.widget.Toast

/**
 * 播放链路里唯一的用户可见反馈出口。
 *
 * 之前无音源 / 空列表这些分支只是 `return` 或者写一行 Log，界面上完全没反应，
 * 用户只能以为「点了没用」。这里统一收口成一句短提示。
 * 同一句话在 [THROTTLE_MS] 内不重复弹，避免自动切歌或连续点歌时刷屏。
 */
object MeloXPlaybackNotice {
    private const val TAG = "MeloXPlaybackNotice"
    private const val THROTTLE_MS = 3_000L

    const val EMPTY_QUEUE = "这个列表还没有歌曲"
    const val NO_SOURCE = "没有找到可播放的音源"
    const val OFFLINE_NO_DOWNLOADS = "离线状态下没有可播放的已下载歌曲"
    const val NETWORK_UNAVAILABLE = "网络不可用，暂时无法播放"
    const val NEED_MEMBERSHIP = "该歌曲需要会员或版权权限"
    const val LOGIN_EXPIRED = "登录状态已失效，请重新登录后再播放"
    const val PLAYBACK_FAILED = "播放失败，请稍后重试"

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var lastMessage: String? = null

    @Volatile
    private var lastShownAtMs = 0L

    fun show(context: Context, message: String?) {
        val text = message?.takeIf { it.isNotBlank() } ?: return
        val now = SystemClock.elapsedRealtime()
        synchronized(this) {
            if (text == lastMessage && now - lastShownAtMs < THROTTLE_MS) return
            lastMessage = text
            lastShownAtMs = now
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            showNow(context, text)
        } else {
            mainHandler.post { showNow(context, text) }
        }
    }

    private fun showNow(context: Context, text: String) {
        runCatching { Toast.makeText(context.applicationContext, text, Toast.LENGTH_SHORT).show() }
            .onFailure { Log.w(TAG, "Unable to show playback notice", it) }
    }

    /**
     * 把播放器抛出的错误翻译成一句用户读得懂的话。
     * 音源解析失败（[com.lladlam.melox.core.audio.NeteasePlaybackUnavailableException]）
     * 在 ExoPlayer 里会被包成 [androidx.media3.common.PlaybackException]，
     * 原始中文文案留在 cause 链里，所以整条链都参与匹配。
     */
    fun describe(error: Throwable?, offline: Boolean): String {
        val chain = messageChain(error)
        return when {
            offline && chain.contains("下载") -> OFFLINE_NO_DOWNLOADS
            offline -> NETWORK_UNAVAILABLE
            chain.contains("音源") || chain.contains("没有可播放") || chain.contains("版权") -> NO_SOURCE
            chain.contains("会员") || chain.contains("付费") || chain.contains("VIP") ||
                chain.contains("权限") -> NEED_MEMBERSHIP
            chain.contains("登录") || chain.contains("cookie") -> LOGIN_EXPIRED
            else -> PLAYBACK_FAILED
        }
    }

    private fun messageChain(error: Throwable?): String = buildString {
        var current = error
        var depth = 0
        while (current != null && depth++ < 8) {
            current.message?.takeIf(String::isNotBlank)?.let { append(it).append('\n') }
            current = current.cause
        }
    }
}
