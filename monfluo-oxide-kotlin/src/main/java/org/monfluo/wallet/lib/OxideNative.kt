package org.monfluo.wallet.lib

/**
 * Loads the mirrored Monfluo Oxide JNI wallet backend.
 * Unsupported ABIs can continue using the legacy backend during migration.
 */
object OxideNative {
    @Volatile
    var isAvailable: Boolean = false
        private set

    @Synchronized
    fun load(): Boolean {
        if (isAvailable) return true
        return try {
            System.loadLibrary("monfluo_oxide_jni")
            isAvailable = true
            true
        } catch (_: UnsatisfiedLinkError) {
            false
        }
    }
}
