package io.anonero.util

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class WalletKeyInstrumentedTest {
    @Test
    fun existsRequiresADecryptableKeystoreEntry() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val wallet = "wallet-key-test-" + UUID.randomUUID().toString()

        try {
            WalletKey.store(context, wallet, WalletKey.newSecret())
            assertTrue(WalletKey.exists(context, wallet))

            val keyStore = KeyStore.getInstance("AndroidKeyStore")
            keyStore.load(null)
            keyStore.deleteEntry("walletSecret-" + wallet)

            // The encrypted preference remains, but without its Keystore alias
            // the wrapped key is unusable and must not be reported as present.
            assertFalse(WalletKey.exists(context, wallet))
        } finally {
            WalletKey.remove(context, wallet)
        }
    }
}
