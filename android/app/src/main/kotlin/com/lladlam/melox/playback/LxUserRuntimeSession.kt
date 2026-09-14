package com.lladlam.melox.playback

import com.lladlam.melox.core.provider.lxuser.LxUserRuntime
import com.lladlam.melox.core.provider.lxuser.LxUserScript
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors

/**
 * Keeps one LX runtime alive across songs.
 *
 * Building a runtime means compiling the user's script - measured at ~200 ms for a
 * 145 KB obfuscated source - and discarding whatever cache the script had built up.
 * Keeping it alive skips that work and lets the source reuse its own cached
 * lookups, which most public sources keep for around twenty minutes.
 *
 * A QuickJS context may only be touched from the thread that created it, so every
 * call is funnelled through one dedicated thread. Resolves are serialised by the
 * caller anyway, so this costs no parallelism; it just pins the context to a
 * stable thread. The caller's own wall-clock budget still bounds each song, and a
 * failed action discards the context so the next song starts clean.
 */
internal object LxUserRuntimeSession {
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "lx-user-runtime").apply { isDaemon = true }
    }

    // Only ever touched on [executor]'s thread, so no extra locking is needed.
    private var runtime: LxUserRuntime? = null
    private var identity: String? = null

    private val pacingLock = Any()
    private var nextRequestAt = 0L

    /**
     * Blocks until this caller is allowed to issue the next request to the source.
     *
     * The quota is shared by every concurrent resolve. Pacing each resolve on its own
     * multiplied the intended gap by the prefetcher's four parallel resolves, and the
     * public mirrors answered `429 请求过于频繁` - which made songs fall back to the
     * official 30 s trial clip.
     */
    fun awaitRequestSlot(gapMs: Long) {
        val waitMs = synchronized(pacingLock) {
            val now = android.os.SystemClock.elapsedRealtime()
            val slot = if (nextRequestAt > now) nextRequestAt else now
            nextRequestAt = slot + gapMs
            slot - now
        }
        if (waitMs > 0L) Thread.sleep(waitMs)
    }

    fun <T> withRuntime(recordId: String, scriptSource: String, block: (LxUserRuntime) -> T): T {
        val task = Callable {
            block(runtimeFor(recordId, scriptSource))
        }
        return try {
            executor.submit(task).get()
        } catch (error: ExecutionException) {
            // An action that threw may have left the context mid-flight. Drop it on
            // the owning thread so the next song rebuilds from a known-good state.
            executor.submit {
                runCatching { runtime?.close() }
                runtime = null
                identity = null
            }
            throw error.cause ?: error
        }
    }

    private fun runtimeFor(recordId: String, scriptSource: String): LxUserRuntime {
        runtime?.let { existing ->
            if (identity == recordId) return existing
        }
        runCatching { runtime?.close() }
        runtime = null
        identity = null
        val candidate = LxUserRuntime()
        try {
            candidate.load(LxUserScript(scriptSource))
        } catch (error: Throwable) {
            runCatching { candidate.close() }
            throw error
        }
        runtime = candidate
        identity = recordId
        return candidate
    }
}
