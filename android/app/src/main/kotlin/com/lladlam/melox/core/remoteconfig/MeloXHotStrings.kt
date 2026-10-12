package com.lladlam.melox.core.remoteconfig

import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide UI text overrides delivered by the signed remote config.
 *
 * This is the read side of the "hot update" channel: the runtime publishes the
 * overrides carried by a *verified, applicable* config, and the UI asks for a
 * replacement by key. Nothing here trusts the network — the values only ever
 * arrive through [MeloXRemoteConfig], whose signature was already checked — and
 * a missing key always falls back to the built-in text.
 */
object MeloXHotStrings {

    private val mutableOverrides = MutableStateFlow<Map<String, Map<String, String>>>(emptyMap())
    val overrides: StateFlow<Map<String, Map<String, String>>> = mutableOverrides.asStateFlow()

    /** Replaces the active overrides; called by the remote-config runtime only. */
    internal fun update(value: Map<String, Map<String, String>>) {
        mutableOverrides.value = value
    }

    /**
     * Looks a key up for [localeTag] (`zh-CN`, `ja`, ...) with a deterministic
     * fallback chain: exact tag, then the bare language, then `default`.
     */
    fun override(
        localeTag: String,
        key: String,
        source: Map<String, Map<String, String>> = mutableOverrides.value,
    ): String? {
        if (key.isBlank() || source.isEmpty()) return null
        source[localeTag]?.get(key)?.let { return it }
        val language = localeTag.substringBefore('-').lowercase(Locale.ROOT)
        if (language.isNotEmpty() && language != localeTag.lowercase(Locale.ROOT)) {
            source[language]?.get(key)?.let { return it }
        }
        return source["default"]?.get(key)
    }
}
