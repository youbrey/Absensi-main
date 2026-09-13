package com.example.util

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import com.example.data.GoogleSheetsManager
import java.util.concurrent.TimeUnit

/**
 * A "now" the device owner cannot spoof by changing Settings > Date & time.
 *
 * System.currentTimeMillis() follows whatever wall clock the user has set, so it must never
 * decide whether an attendance submission falls inside the MASUK/PULANG window -- doing that
 * was exactly the bug this closes (change the clock in Settings, submit whenever you like).
 * SystemClock.elapsedRealtime() ticks monotonically since boot and ignores wall-clock changes,
 * so this anchors it to a real timestamp fetched from our own Apps Script backend (which is
 * not editable from the device the way the system clock is) and derives "now" from that
 * anchor instead.
 *
 * The anchor is invalidated on reboot (elapsedRealtime resets near zero) and once it goes
 * stale, so a device that has never reached the server since it last booted has no trusted
 * time at all. Callers MUST treat a null result as "cannot verify" and refuse whatever
 * time-window-sensitive action was being attempted -- never fall back to the device clock.
 */
object TrustedTime {
    private const val PREFS = "trusted_time"
    private const val KEY_ANCHOR_EPOCH = "anchor_epoch_ms"
    private const val KEY_ANCHOR_ELAPSED = "anchor_elapsed_ms"
    private val MAX_ANCHOR_AGE_MS = TimeUnit.HOURS.toMillis(12)

    @Volatile private var appContext: Context? = null

    /** Call once, e.g. from the ViewModel's init block. Stores only the Application context
     *  (never an Activity/UI context), so this object cannot leak one. */
    fun init(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    private fun prefsOrNull(): SharedPreferences? =
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Called by GoogleSheetsManager whenever a server response includes a fresh serverTime.
     *  Safe to call often -- every successful round-trip (sync, recap fetch, admin login,
     *  the dedicated time check) refreshes the anchor for free. */
    fun recordAnchor(serverTimeMs: Long) {
        prefsOrNull()?.edit()
            ?.putLong(KEY_ANCHOR_EPOCH, serverTimeMs)
            ?.putLong(KEY_ANCHOR_ELAPSED, SystemClock.elapsedRealtime())
            ?.apply()
    }

    /** Trusted current time, or null if there is no valid (non-stale, post-boot) anchor yet. */
    fun nowOrNull(): Long? {
        val prefs = prefsOrNull() ?: return null
        val anchorEpoch = prefs.getLong(KEY_ANCHOR_EPOCH, -1L)
        val anchorElapsed = prefs.getLong(KEY_ANCHOR_ELAPSED, -1L)
        if (anchorEpoch <= 0L || anchorElapsed <= 0L) return null
        val nowElapsed = SystemClock.elapsedRealtime()
        if (nowElapsed < anchorElapsed) return null // device rebooted since this anchor was set
        val age = nowElapsed - anchorElapsed
        if (age > MAX_ANCHOR_AGE_MS) return null // stale -- force a fresh check with the server
        return anchorEpoch + age
    }

    /** Returns a trusted "now", refreshing from the server first if there is no valid anchor
     *  cached yet. Returns null only if that refresh also fails (e.g. no internet AND no
     *  still-valid anchor from before) -- callers must refuse rather than fall back silently
     *  to System.currentTimeMillis(). */
    suspend fun ensureFreshOrNull(): Long? {
        nowOrNull()?.let { return it }
        GoogleSheetsManager.fetchServerTime() ?: return null
        return nowOrNull()
    }
}
