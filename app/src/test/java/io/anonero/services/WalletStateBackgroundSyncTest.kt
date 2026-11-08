package io.anonero.services

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WalletStateBackgroundSyncTest {
    @Test
    fun pinLockWithoutNativeModeDoesNotRequireTeardown() {
        assertFalse(
            requiresNativeBackgroundSyncTeardown(
                nativeBackgroundSyncActive = false
            )
        )
    }

    @Test
    fun pendingBackgroundTransitionIsSerializedBeforeTeardownDecision() {
        assertTrue(
            requiresNativeBackgroundSyncTeardown(
                nativeBackgroundSyncActive = false,
                backgroundTransitionPending = true
            )
        )
    }

    @Test
    fun enteredNativeBackgroundModeRequiresTeardown() {
        assertTrue(
            requiresNativeBackgroundSyncTeardown(
                nativeBackgroundSyncActive = true
            )
        )
    }
}
