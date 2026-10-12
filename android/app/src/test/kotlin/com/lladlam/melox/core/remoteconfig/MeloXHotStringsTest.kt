package com.lladlam.melox.core.remoteconfig

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MeloXHotStringsTest {

    @After
    fun tearDown() {
        MeloXHotStrings.update(emptyMap())
    }

    @Test
    fun exactLocaleWinsThenLanguageThenDefault() {
        MeloXHotStrings.update(
            mapOf(
                "zh-CN" to mapOf("a" to "cn"),
                "zh" to mapOf("a" to "zh", "b" to "zh-b"),
                "default" to mapOf("a" to "def", "c" to "def-c"),
            ),
        )

        assertEquals("cn", MeloXHotStrings.override("zh-CN", "a"))
        assertEquals("zh-b", MeloXHotStrings.override("zh-CN", "b"))
        assertEquals("def-c", MeloXHotStrings.override("zh-CN", "c"))
        assertNull(MeloXHotStrings.override("zh-CN", "missing"))
    }

    @Test
    fun noOverridesMeansNoReplacement() {
        MeloXHotStrings.update(emptyMap())
        assertNull(MeloXHotStrings.override("zh-CN", "a"))
    }

    @Test
    fun blankKeyNeverMatches() {
        MeloXHotStrings.update(mapOf("default" to mapOf("" to "x")))
        assertNull(MeloXHotStrings.override("en", ""))
    }
}
