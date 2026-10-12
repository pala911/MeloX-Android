package com.lladlam.melox.core.remoteconfig

import android.util.Log
import com.whl.quickjs.android.QuickJSLoader
import com.whl.quickjs.wrapper.QuickJSContext
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * Sandboxed behaviour hooks delivered by the signed remote config.
 *
 * This is the "logic" half of the hot-update channel: a script may be replaced
 * without shipping an APK, but it can never do more than transform one small
 * JSON value. Every run happens in a fresh QuickJS context with a memory and
 * stack cap, on a single daemon thread, under a wall-clock timeout. A hook that
 * times out disables the whole channel for the process so a bad script cannot
 * keep burning CPU, and any failure simply returns `null` so callers fall back
 * to their built-in behaviour.
 *
 * Script contract: define `function run(input)` and return a JSON-serialisable
 * value. Example:
 *
 *     function run(input) { return { keywords: input.keywords.trim() }; }
 */
object MeloXHotScripts {

    private const val TAG = "MeloXHotScripts"
    private const val MemoryLimitBytes = 8 * 1024 * 1024
    private const val StackLimitBytes = 256 * 1024
    const val DefaultTimeoutMs = 600L

    private val mutableScripts = MutableStateFlow<Map<String, MeloXRemoteScript>>(emptyMap())
    val scripts: StateFlow<Map<String, MeloXRemoteScript>> = mutableScripts.asStateFlow()

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "melox-hot-scripts").apply { isDaemon = true }
    }

    private val disabled = AtomicBoolean(false)

    /** Replaces the active hooks; called by the remote-config runtime only. */
    internal fun update(value: Map<String, MeloXRemoteScript>) {
        if (value == mutableScripts.value) return
        mutableScripts.value = value
        // A new payload is a fresh chance: only a repeat revision stays disabled.
        disabled.set(false)
    }

    /**
     * Runs [hook] with [input] and returns its JSON result, or `null` when the
     * hook is absent, the sandbox failed, or the timeout elapsed.
     */
    fun run(hook: String, input: JSONObject, timeoutMs: Long = DefaultTimeoutMs): JSONObject? {
        if (disabled.get()) return null
        val script = mutableScripts.value[hook] ?: return null
        return try {
            executor.submit<JSONObject?> { evaluate(script, input) }.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (timeout: java.util.concurrent.TimeoutException) {
            if (disabled.compareAndSet(false, true)) {
                Log.w(TAG, "hook $hook timed out after ${timeoutMs}ms; hot scripts disabled")
            }
            null
        } catch (error: Throwable) {
            Log.w(TAG, "hook $hook failed", error)
            null
        }
    }

    private fun evaluate(script: MeloXRemoteScript, input: JSONObject): JSONObject? {
        QuickJSLoader.init()
        val context = QuickJSContext.create()
        return try {
            context.setMemoryLimit(MemoryLimitBytes)
            context.setMaxStackSize(StackLimitBytes)
            val program = buildString {
                append(script.source)
                append("\n;(function(){")
                append("const __meloxInput = JSON.parse(")
                append(JSONObject.quote(input.toString()))
                append(");")
                append("if (typeof run !== 'function') return null;")
                append("const __meloxResult = run(__meloxInput);")
                append("return (__meloxResult === undefined || __meloxResult === null)")
                append(" ? null : JSON.stringify(__meloxResult);")
                append("})()")
            }
            val raw = context.evaluate(program, "melox-hot-${script.name}.js") as? String ?: return null
            JSONObject(raw)
        } catch (error: Throwable) {
            Log.w(TAG, "script ${script.name} revision ${script.revision} failed", error)
            null
        } finally {
            runCatching { context.close() }
        }
    }
}
