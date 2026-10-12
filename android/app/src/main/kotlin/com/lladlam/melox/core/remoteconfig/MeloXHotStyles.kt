package com.lladlam.melox.core.remoteconfig

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Theme colour overrides carried by the signed remote config.
 *
 * Keys are `<role>` (applies to both modes) or `<light|dark>.<role>`. Values are
 * already-validated ARGB ints; a missing role simply keeps the built-in colour.
 * This keeps seasonal or campaign themes shippable without an APK while never
 * letting the remote side produce an invalid colour (validation lives in
 * [parseColorOverrides]).
 */
object MeloXHotStyles {

    private val mutableColors = MutableStateFlow<Map<String, Int>>(emptyMap())
    val colors: StateFlow<Map<String, Int>> = mutableColors.asStateFlow()

    /** Replaces the active overrides; called by the remote-config runtime only. */
    internal fun update(value: Map<String, Int>) {
        mutableColors.value = value
    }

    /**
     * Resolves [role] for the given mode: `<mode>.<role>` first, then `<role>`.
     */
    fun color(
        role: String,
        dark: Boolean,
        source: Map<String, Int> = mutableColors.value,
    ): Int? {
        if (source.isEmpty()) return null
        val mode = if (dark) "dark" else "light"
        return source["$mode.$role"] ?: source[role]
    }
}
