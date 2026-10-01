package com.stan.libbylight.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LockScreenOverlayPolicyTest {
    private val ready = LockScreenOverlayPolicy.Inputs(
        enabled = true,
        canDrawOverlays = true,
        hasUsageAccess = true,
        hasBook = true,
        screenOn = true,
        bardInForeground = false,
        topIsLightOsLockScreen = true,
        dismissedThisWake = false,
    )

    @Test fun showsOnlyWhenEverythingAgrees() {
        assertTrue(LockScreenOverlayPolicy.wanted(ready))
        assertEquals(LockScreenOverlayPolicy.Action.Show, LockScreenOverlayPolicy.decide(ready, shown = false))
        assertEquals(LockScreenOverlayPolicy.Action.Nothing, LockScreenOverlayPolicy.decide(ready, shown = true))
    }

    @Test fun anyMissingInputHides() {
        val variants = listOf(
            ready.copy(enabled = false),
            ready.copy(canDrawOverlays = false),
            ready.copy(hasUsageAccess = false),
            ready.copy(hasBook = false),
            ready.copy(screenOn = false),
            ready.copy(bardInForeground = true),
            ready.copy(topIsLightOsLockScreen = false),
            ready.copy(dismissedThisWake = true),
        )
        for (inputs in variants) {
            assertFalse(LockScreenOverlayPolicy.wanted(inputs))
            assertEquals(LockScreenOverlayPolicy.Action.Hide, LockScreenOverlayPolicy.decide(inputs, shown = true))
        }
    }

    @Test fun onlyTheLightOsMainActivityCounts() {
        val home = setOf("com.lightos")
        assertTrue(LockScreenOverlayPolicy.isLightOsLockScreen("com.lightos", "com.lightos.MainActivity", home))
        assertTrue(LockScreenOverlayPolicy.isLightOsLockScreen("com.lightos", null, home))
        assertFalse(LockScreenOverlayPolicy.isLightOsLockScreen("com.lightos", "com.lightos.LightOSPrivilegedCallActivity", home))
        assertFalse(LockScreenOverlayPolicy.isLightOsLockScreen("com.sublunar.amp", "x", home))
        assertFalse(LockScreenOverlayPolicy.isLightOsLockScreen(null, null, home))
    }
}
