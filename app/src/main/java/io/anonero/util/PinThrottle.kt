package io.anonero.util

import android.content.SharedPreferences
import android.os.SystemClock
import androidx.core.content.edit

/**
 * A rate limit for the lock-screen PIN.
 *
 * The PIN is all that stands between a stolen phone and the Keystore-wrapped wallet secret
 * (WalletKey), and with no limit an auto-tapper gets through 10k four-digit PINs in hours.
 * Five attempts are free; each one after that waits 30s longer than the last, up to 5 minutes
 * -- about 290 guesses a day.
 *
 * An attempt is counted before the PIN is checked, so killing the app mid-check doesn't skip
 * it, and a correct PIN clears the count. Waits run on the boot clock: the system time can't
 * shorten them, and a reboot restarts a wait instead of ending it.
 */
object PinThrottle {
    private const val ATTEMPTS = "pin_attempts"
    private const val LAST = "pin_last_attempt"

    private fun waitFor(attempts: Int) = (attempts - 4).coerceIn(0, 10) * 30_000L

    /** Ms until the next attempt is allowed, 0 if it is allowed now. */
    @Synchronized
    fun waitLeft(prefs: SharedPreferences, now: Long = SystemClock.elapsedRealtime()): Long {
        var last = prefs.getLong(LAST, 0)
        // boot clock went backwards: the phone rebooted, so the wait starts over from now
        if (now < last) {
            last = now
            prefs.edit(commit = true) { putLong(LAST, now) }
        }
        return (last + waitFor(prefs.getInt(ATTEMPTS, 0)) - now).coerceAtLeast(0)
    }

    /** Call before checking the PIN: counts the attempt and returns 0, or returns the wait left. */
    @Synchronized
    fun tryAttempt(prefs: SharedPreferences, now: Long = SystemClock.elapsedRealtime()): Long {
        val left = waitLeft(prefs, now)
        if (left > 0) return left
        prefs.edit(commit = true) {
            putInt(ATTEMPTS, prefs.getInt(ATTEMPTS, 0) + 1)
            putLong(LAST, now)
        }
        return 0
    }

    /** Clears the count after a correct PIN. */
    @Synchronized
    fun reset(prefs: SharedPreferences) =
        prefs.edit(commit = true) { remove(ATTEMPTS); remove(LAST) }
}
