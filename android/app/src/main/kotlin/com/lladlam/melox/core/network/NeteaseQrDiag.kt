package com.lladlam.melox.core.network

import android.content.Context
import com.lladlam.melox.core.account.NeteaseSessionStore
import java.io.File

/**
 * 网易云登录链路的落盘诊断。
 *
 * 为什么要落盘而不是 logcat：这台 vivo 真机是 user build（`ro.debuggable=0`），
 * App 自身的 `Log.i` 在 main buffer 里完全不出，只有 `run-as … cat files/…` 能读到。
 * 服务端返回的风控文案（「请切换登录方式或升级版本」等）必须连同**是哪个请求**一起
 * 记录，否则只能靠猜。
 */
object NeteaseQrDiag {
    private const val FileName = "netease_qr_diag.txt"
    private const val MaxBytes = 64 * 1024

    @Volatile
    private var context: Context? = null

    fun install(context: Context) {
        this.context = context.applicationContext
    }

    fun log(phase: String, detail: String) {
        val ctx = context ?: return
        val line = "time=${System.currentTimeMillis()} phase=$phase detail=$detail"
        runCatching {
            val file = File(ctx.filesDir, FileName)
            // 简单截断，避免长期使用把 flash 写满。
            if (file.length() > MaxBytes) {
                file.writeText(line + "\n")
            } else {
                file.appendText(line + "\n")
            }
        }
    }

    /** cookie 只记键名，绝不记值（MUSIC_U 是用户凭证）。 */
    fun cookieKeys(cookieHeader: String): String =
        NeteaseSessionStore.parseCookie(cookieHeader).keys.sorted().joinToString(",")
}
