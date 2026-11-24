package com.androidharness.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceLaunchClaimsTest {
    @Test fun failedShellExitIsNeverReportedAsSuccess() {
        val failure = DeviceCommandOutcome.failure(1, false, "Permission denied", 20_000)
        assertTrue(failure != null)
        assertTrue(failure!!.contains("exit 1"))
        assertTrue(failure.contains("Permission denied"))
        assertTrue(failure.contains("privileged shell"))
        assertNull(DeviceCommandOutcome.failure(0, false, "some output", 20_000))
    }

    @Test fun timedOutCommandIsNotCompleted() {
        val failure = DeviceCommandOutcome.failure(0, true, "partial output", 5_000)
        assertTrue(failure != null)
        assertTrue(failure!!.contains("timed out"))
        assertFalse(failure.contains("partial output"))
    }

    @Test fun foregroundMatchAcceptsProcessVariantsOnly() {
        val target = "com.whatsapp"
        assertTrue(AppLaunchClaims.foregroundMatches(listOf("com.whatsapp"), target))
        assertTrue(AppLaunchClaims.foregroundMatches(listOf("com.whatsapp:wateway"), target))
        assertFalse(AppLaunchClaims.foregroundMatches(listOf("com.whatsapp.something"), target))
        assertFalse(AppLaunchClaims.foregroundMatches(listOf("com.whatsapp.w4b"), target))
        assertFalse(AppLaunchClaims.foregroundMatches(emptyList(), target))
        assertFalse(AppLaunchClaims.foregroundMatches(listOf("com.androidharness.app.debug"), target))
    }

    @Test fun unverifiedLaunchNamesObservedForegroundAndForbidsClaim() {
        val message = AppLaunchClaims.unverified("com.whatsapp", listOf("com.androidharness.app", "com.sec.android.app.launcher"))
        assertTrue(message.contains("com.whatsapp"))
        assertTrue(message.contains("com.androidharness.app"))
        assertTrue(message.contains("could not be verified"))
        assertTrue(message.contains("device_screen"))
        assertEquals("unknown", AppLaunchClaims.foregroundLabel(emptyList()))
    }

    @Test fun verifiedAndFailureMessagesAreExplicit() {
        assertTrue(AppLaunchClaims.verified("com.whatsapp", "app context").contains("verified it is in the foreground"))
        assertTrue(AppLaunchClaims.missing("com.whatsapp").contains("no launcher activity"))
        val failed = AppLaunchClaims.launchFailed("com.whatsapp", "Permission denied")
        assertTrue(failed.contains("Could not open com.whatsapp"))
        assertTrue(failed.contains("No app was launched; do not claim otherwise."))
    }
}
