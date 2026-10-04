package com.lladlam.melox.ui.legal

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MeloXRemoteNoticeDeliveryTest {
    /**
     * The fork removed every startup dialog, the remote notice included.
     *
     * The upstream version of this test asserted the delivery guards that used to wrap the
     * notice (consent + verified signature + shouldShow + markShown). With the renderer gone
     * those guards have nothing left to guard, so the invariant flips: the app shell must not
     * be able to produce a remote announcement at all, which also makes "shown without
     * consent" impossible.
     */
    @Test
    fun appNoLongerRendersRemoteAnnouncementsFromTheAppShell() {
        val source = File("src/main/kotlin/com/lladlam/melox/ui/MeloXApp.kt").readText()

        assertFalse(
            "MeloXApp must not render the remote notice dialog",
            source.contains("MeloXRemoteNoticeDialog("),
        )
        assertFalse(
            "MeloXApp must not decide whether to show a remote notice",
            source.contains("MeloXRemoteNoticeStore.shouldShow"),
        )
        assertFalse(
            "MeloXApp must not record a notice display when none can be shown",
            source.contains("MeloXRemoteNoticeStore.markShown"),
        )
        assertFalse(
            "MeloXApp must not observe verified remote config just for announcements",
            source.contains("MeloXRemoteConfigSource"),
        )
    }

    /**
     * The consent decision itself still has to be taken in the app shell, so anything that
     * reintroduces a consent-gated dialog has to go through it instead of around it.
     */
    @Test
    fun appShellStillGatesRemoteConfigOnUserConsent() {
        val source = File("src/main/kotlin/com/lladlam/melox/ui/MeloXApp.kt").readText()

        assertTrue(
            "MeloXApp must still consult MeloXRemoteConfigConsent",
            source.contains("MeloXRemoteConfigConsent."),
        )
        assertFalse(
            "the first-launch consent dialog must not come back",
            source.contains("MeloXFirstLaunchLegalConsent"),
        )
        assertFalse(
            "the cloud-control consent dialog must not come back here",
            source.contains("MeloXCloudControlConsentDialog("),
        )
    }
}
