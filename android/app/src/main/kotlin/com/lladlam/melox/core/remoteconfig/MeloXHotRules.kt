package com.lladlam.melox.core.remoteconfig

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Matching/search rules delivered by the signed remote config. */
object MeloXHotRules {

    private val mutableRules = MutableStateFlow(MeloXRemoteConfigDefaults.Rules)
    val rules: StateFlow<MeloXRemoteRules> = mutableRules.asStateFlow()

    /** Replaces the active rules; called by the remote-config runtime only. */
    internal fun update(value: MeloXRemoteRules) {
        mutableRules.value = value
    }

    val current: MeloXRemoteRules get() = mutableRules.value

    /** Applies the configured literal title suffixes to a search keyword. */
    fun stripTitleSuffixes(title: String): String =
        com.lladlam.melox.core.remoteconfig.stripTitleSuffixes(title, current.searchTitleStripSuffixes)
}

/** Playback implementation selection delivered by the signed remote config. */
object MeloXHotStrategies {

    private val mutableStrategies = MutableStateFlow(MeloXRemoteConfigDefaults.Strategies)
    val strategies: StateFlow<Map<String, String>> = mutableStrategies.asStateFlow()

    /** Replaces the active strategies; called by the remote-config runtime only. */
    internal fun update(value: Map<String, String>) {
        mutableStrategies.value = value
    }

    fun value(name: String): String =
        mutableStrategies.value[name] ?: MeloXRemoteConfigDefaults.Strategies[name] ?: "v1"

    /**
     * True when NetEase playback should stay on the safe tier. Used to abandon
     * lossless/immersive endpoints without shipping a build.
     */
    fun neteaseCompatMode(): Boolean = value("netease_playback") == "compat"
}
