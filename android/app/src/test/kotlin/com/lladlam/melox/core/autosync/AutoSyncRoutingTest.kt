package com.lladlam.melox.core.autosync

import org.junit.Assert.*
import org.junit.Test

class AutoSyncRoutingTest {
    @Test fun modelReleaseIsPinnedToBetaTwo() {
        assertEquals("android-models-v0.1.0-beta.2", AutoSyncModelRepository.RELEASE_TAG)
        assertEquals(
            "https://github.com/lladlam/MeloX-AutoSync/releases/download/android-models-v0.1.0-beta.2/",
            AutoSyncModelRepository.RELEASE_PREFIX,
        )
        assertEquals(
            "ffcc9b5db19dff668b9994aae7d2e49e6810564131f51039bc49e7c779f475d4",
            AutoSyncModelRepository.INDEX_SHA256,
        )
    }

    @Test fun japaneseCannotFallThroughToChineseCif() {
        assertNull(AutoSyncText.detectLanguage(listOf("君の知らない物語", "空を見上げている")))
    }

    @Test fun chineseWithEnglishLineKeepsCifAndRefusesThatMixedLine() {
        assertEquals("zh", AutoSyncText.detectLanguage(listOf("天空之中飘落的雨滴", "诉说着那些遥远的故事", "你好 hello")))
        assertFalse(ZhText.isAlignableForChinese("你好 hello"))
        assertFalse(ZhText.isAlignableForChinese("你好 OK"))
    }
}
