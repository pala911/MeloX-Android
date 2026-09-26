package com.lladlam.melox.core.audio

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NeteaseQualityClientTest {

    @Test
    fun detectsFreeTrialClip() {
        val data = JSONObject()
            .put("url", "https://example.com/trial.mp3")
            .put("freeTrialInfo", JSONObject().put("start", 0).put("end", 30))
        assertTrue(NeteaseQualityClient.isPreviewClip(data))
    }

    @Test
    fun ignoresExplicitNullTrialInfo() {
        val data = JSONObject()
            .put("url", "https://example.com/full.mp3")
            .put("freeTrialInfo", JSONObject.NULL)
        assertFalse(NeteaseQualityClient.isPreviewClip(data))
    }

    @Test
    fun treatsMissingTrialInfoAsFullTrack() {
        val data = JSONObject().put("url", "https://example.com/full.mp3")
        assertFalse(NeteaseQualityClient.isPreviewClip(data))
    }
}
