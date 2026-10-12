package com.lladlam.melox.core.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LrcLyricsParserTest {
    @Test
    fun providerLrcFallbackDoesNotBecomeSyntheticWordTiming() {
        val document = LrcLyricsParser.parse(
            lrc = "[00:01.00]第一句歌词\n[00:04.00]第二句歌词",
        )

        assertFalse(document.pseudoTimingAllowed)
        assertTrue(document.lines.isNotEmpty())
        assertTrue(document.withPseudoTiming().lines.all { it.syllables.isEmpty() })
    }

    @Test
    fun nativeNeteaseLineLyricsKeepExistingPseudoTimingBehavior() {
        val document = NeteaseLyricParser.parse(
            yrc = "",
            lrc = "[00:01.00]第一句歌词\n[00:04.00]第二句歌词",
        )

        assertTrue(document.pseudoTimingAllowed)
        assertTrue(document.withPseudoTiming().lines.first().syllables.isNotEmpty())
    }

    @Test
    fun romanizationUsesMonotonicPairingAndCorrectsGlobalOffset() {
        val document = NeteaseLyricParser.parse(
            yrc = "",
            lrc = "[00:01.00]第一句\n[00:04.00]第二句\n[00:07.00]第三句",
            romanizedLrc = "[00:02.00]di yi ju\n[00:05.00]di er ju\n[00:08.00]di san ju",
        )

        assertTrue(document.lines.map { it.romanization } == listOf("di yi ju", "di er ju", "di san ju"))
    }

    @Test
    fun untranslatedHeaderLinesDoNotShiftFollowingTranslations() {
        // NetEase returns the translator's blank placeholders for the credit
        // header ("作词"/"作曲"), but parseLrc drops blank lines. Those two
        // unmatched primary lines must not push the whole translation track one
        // or more positions ahead.
        val document = NeteaseLyricParser.parse(
            yrc = "",
            lrc = "[00:00.000]作词 : Freddie Mercury\n" +
                "[00:01.078]作曲 : Freddie Mercury\n" +
                "[00:02.842]she keeps her moet et chandon\n" +
                "[00:05.786]in her pretty cabinet",
            translatedLrc = "[00:00.000]\n" +
                "[00:01.078]\n" +
                "[00:02.842]在她的漂亮橱柜里\n" +
                "[00:05.786]摆放着酩悦香槟",
        )

        assertEquals(
            listOf(null, null, "在她的漂亮橱柜里", "摆放着酩悦香槟"),
            document.lines.map { it.translation },
        )
    }
}
