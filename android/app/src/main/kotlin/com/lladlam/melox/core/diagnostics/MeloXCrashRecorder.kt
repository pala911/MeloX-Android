package com.lladlam.melox.core.diagnostics

import android.app.Application
import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃现场记录器（2026-10-01）。
 *
 * ## 为什么需要它
 *
 * 「在其他机型上闪退」这件事，本项目**没有第二条诊断通道**：
 *   · 只有一台真机（vivo iQOO 15），别的机型复现不了；
 *   · 多数 ROM（含 vivo）会屏蔽应用级 logcat，`MeloXLogExporter` 里那条 `logcat` 通路
 *     在用户机上拿不到任何东西；
 *   · 玻璃失败的形态里，最麻烦的一类（AGSL / RenderEffect / GPU 驱动）是
 *     **HWUI native abort**，Java 层 `try/catch` 一个都接不住，只有 tombstone。
 *
 * 没有栈就只能靠猜，而「靠猜迭代」正是这个项目已经吃过亏的地方。
 *
 * ## 它做什么
 *
 * 1. 接管 `Thread.setDefaultUncaughtExceptionHandler`，并**串接原有处理器**
 *    （不吞掉系统默认行为，异常照旧走完系统流程）。
 * 2. 把 异常类 / message / **完整 cause 链** / **完整 stackTrace** 以及
 *    设备 + 版本信息，落盘到 `filesDir/crash/crash-<时间戳>.txt`。
 * 3. 记录**崩溃瞬间玻璃是否在渲染**（`glassRenderingActive`）——
 *    这是区分「玻璃路径崩」与「其他路径崩」最关键的一位信息。
 *    该标记由 `meloXGlassBackdrop()` 打点，**只用于记录，不参与任何门控/降级**。
 * 4. 接入 `MeloXLogExporter`，所以用户走应用内「导出日志」就能把它带出来。
 *
 * ⚠ 本记录器**不做任何降级**：玻璃是否继续渲染与它完全无关。
 *   它只负责让下一次崩溃「说出是哪一行」。
 */
object MeloXCrashRecorder {

    private const val DIR_NAME = "crash"
    private const val FILE_PREFIX = "crash-"
    private const val MAX_REPORTS = 10

    @Volatile
    private var installed = false

    /**
     * 本进程是否真的把玻璃画上屏过。
     *
     * ⚠ 读它的人只该写日志，**不要**拿它做门控 —— 那就是被移除的「自愈降级」。
     */
    @Volatile
    var glassRenderingActive: Boolean = false
        private set

    /** 由 `meloXGlassBackdrop()` 打点。极轻，无锁、无 IO。 */
    fun markGlassRendering() {
        glassRenderingActive = true
    }

    fun install(app: Application) {
        if (installed) return
        installed = true
        val context = app.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // 记录本身绝不能成为二次崩溃源。
            runCatching { write(context, thread, throwable) }
            if (previous != null) {
                previous.uncaughtException(thread, throwable)
            } else {
                android.os.Process.killProcess(android.os.Process.myPid())
            }
        }
    }

    /** 最近 n 份崩溃记录的正文（供日志导出使用）；没有则返回空串。 */
    fun readRecentReports(context: Context, limit: Int = 3): String {
        val files = reportFiles(context).takeLast(limit.coerceAtLeast(0))
        if (files.isEmpty()) return ""
        return files.joinToString(separator = "\n") { file ->
            runCatching { file.readText() }.getOrElse { "（无法读取 ${file.name}）" }
        }
    }

    fun reportCount(context: Context): Int = reportFiles(context).size

    private fun reportFiles(context: Context): List<File> {
        val dir = File(context.filesDir, DIR_NAME)
        val files = runCatching {
            dir.listFiles { file -> file.isFile && file.name.startsWith(FILE_PREFIX) }
                ?.sortedBy { it.name }
        }.getOrNull().orEmpty()
        return files
    }

    private fun write(context: Context, thread: Thread, throwable: Throwable) {
        val dir = File(context.filesDir, DIR_NAME)
        if (!dir.exists() && !dir.mkdirs()) return
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())
        val target = File(dir, "$FILE_PREFIX$stamp.txt")

        val text = buildString {
            appendLine("=== MeloX 崩溃现场 ===")
            appendLine("时间：$stamp（${System.currentTimeMillis()}）")
            appendLine("进程：${android.os.Process.myPid()}")
            appendLine("包名：${context.packageName}")
            appendLine("版本：${versionName(context)}")
            appendLine("构建类型：${if (com.lladlam.melox.BuildConfig.DEBUG) "Debug" else "Release"}")
            appendLine("发生崩溃的线程：${thread.name}")
            // ★ 最关键的一位：用来区分「玻璃渲染路径崩」和「其他路径崩」。
            appendLine("崩溃时玻璃在渲染：$glassRenderingActive")
            appendLine("Android：${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}）")
            appendLine("设备：${Build.MANUFACTURER} ${Build.MODEL}（${Build.DEVICE}）")
            appendLine("主板/硬件：${Build.BOARD} / ${Build.HARDWARE}")
            appendLine("ABI：${Build.SUPPORTED_ABIS.joinToString("、")}")
            appendLine()
            appendLine("=== 异常 ===")
            appendLine("顶层层级：${throwable.javaClass.name}: ${throwable.message}")
            appendLine()
            appendLine("--- cause 链 ---")
            var cause: Throwable? = throwable
            var depth = 0
            while (cause != null && depth < 16) {
                appendLine("[$depth] ${cause.javaClass.name}: ${cause.message}")
                cause = cause.cause
                depth++
            }
            appendLine()
            appendLine("=== 完整堆栈 ===")
            val writer = StringWriter()
            throwable.printStackTrace(PrintWriter(writer))
            append(writer.toString())
        }

        target.writeText(text)
        prune(dir)
    }

    /** 只保留最近的 [MAX_REPORTS] 份，避免长期占用用户存储。 */
    private fun prune(dir: File) {
        runCatching {
            val files = dir.listFiles { file -> file.isFile && file.name.startsWith(FILE_PREFIX) }
                ?.sortedBy { it.name }
                ?: return
            if (files.size <= MAX_REPORTS) return
            files.dropLast(MAX_REPORTS).forEach { it.delete() }
        }
    }

    private fun versionName(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    }.getOrDefault("?")
}
