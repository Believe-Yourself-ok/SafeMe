package com.safeme.app.protect

import android.content.Context
import android.util.Log
import com.safeme.app.data.AppLockPrefsState
import com.safeme.app.data.AutoLockDelay
import com.safeme.app.data.LockType
import com.safeme.app.data.appLockPrefs
import com.safeme.app.data.setAppLock
import com.safeme.app.data.setAppLockBiometric
import com.safeme.app.data.setAppLockForgotDisabled
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Cached copy of the App Lock prefs, fed by a DataStore collector in
 * [com.safeme.app.SafeMeApp] and refreshed by the App Lock screen. The gate
 * controller reads this synchronously so a cold start can lock immediately
 * without waiting on DataStore.
 *
 * ## One snapshot, not one field per setting
 *
 * The mirror is a whole [AppLockPrefsState] behind an [AtomicReference], and
 * each setting is exposed as a lens onto it. This used to be one `@Volatile
 * var` per field, which forced every credential rewrite to rebuild a full
 * state by hand and list each field explicitly — so any field added later
 * silently reverted to its default. That is exactly how COD-79's two protection
 * flags would have been switched back ON the moment the user changed their
 * lock, defeating the whole feature.
 *
 * Holding one immutable snapshot also removes torn reads (a caller can no
 * longer see a new [lockType] alongside a stale [credentialLength]), and
 * `updateAndGet` keeps the lenses race-free: the SafeMeApp collector writes
 * from `Dispatchers.Default` while [com.safeme.app.ui.screens.applock.AppLockViewModel]
 * writes optimistically from the main thread.
 */
object AppLockStateHolder {

    private val current = AtomicReference(AppLockPrefsState())

    /** The whole mirrored state. Prefer this to the individual lenses. */
    val state: AppLockPrefsState get() = current.get()

    var lockType: LockType
        get() = current.get().lockType
        set(value) { current.updateAndGet { it.copy(lockType = value) } }

    var autoLock: AutoLockDelay
        get() = current.get().autoLock
        set(value) { current.updateAndGet { it.copy(autoLock = value) } }

    var credentialLength: Int
        get() = current.get().credentialLength
        set(value) { current.updateAndGet { it.copy(credentialLength = value) } }

    var biometricEnabled: Boolean
        get() = current.get().biometricEnabled
        set(value) { current.updateAndGet { it.copy(biometricEnabled = value) } }

    var forgotPasswordDisabled: Boolean
        get() = current.get().forgotPasswordDisabled
        set(value) { current.updateAndGet { it.copy(forgotPasswordDisabled = value) } }

    /** A credential is stored, so there is something to verify against. */
    val hasCredential: Boolean get() = current.get().hasCredential

    /**
     * COD-79 card 1 — is the entry gate armed?
     *
     * Deliberately **not** `lockType != LockType.OFF`. A stored credential with
     * the entry toggle off means "open the app freely, but still verify before
     * a protection is weakened", which is the entire point of the issue.
     * [com.safeme.app.ui.screens.applock.AppLockGateController] reads only this
     * property, so all four of its branches honour card 1 with no changes of
     * their own.
     */
    val enabled: Boolean get() = current.get().entryLockActive

    /** Replace the mirror wholesale (DataStore collector, post-write refresh). */
    fun update(state: AppLockPrefsState) {
        current.set(state)
    }

    /**
     * The current mirror, for `update(snapshot().copy(...))`. Always rebuild
     * through this rather than constructing an [AppLockPrefsState] by hand, so
     * fields you did not mean to change survive the rewrite.
     */
    fun snapshot(): AppLockPrefsState = current.get()
}

/**
 * App Lock security engine.
 *
 * ## Credentials
 *
 * The credential (PIN / password / pattern sequence) is never stored. It is
 * hashed with PBKDF2-HMAC-SHA256 (100k iterations, 256-bit key, 16-byte
 * random salt) and persisted as `"<saltHex>:<hashHex>"`. Verification runs on
 * [Dispatchers.Default] and compares with a constant-time digest compare.
 *
 * ## Rate limiting
 *
 * Failed attempts 1-4 are free; 5-9 trigger an exponential backoff
 * (1s, 2s, 4s, 8s, 16s); the 10th failed attempt locks the app out for 5
 * minutes. State is persisted in SharedPreferences so killing the app cannot
 * reset the counter. A successful unlock resets it.
 */
object AppLockManager {

    const val TAG = "SafeMeAppLock"

    private const val ITERATIONS = 100_000
    private const val KEY_LENGTH_BITS = 256
    private const val SALT_BYTES = 16

    private const val BACKOFF_THRESHOLD = 5
    private const val MAX_ATTEMPTS_BEFORE_LOCKOUT = 10
    private const val LOCKOUT_DURATION_MS = 5 * 60 * 1000L

    private const val RATE_PREFS = "safeme_applock_rate"
    private const val KEY_FAILED_ATTEMPTS = "failed_attempts"
    private const val KEY_LOCKOUT_UNTIL_MS = "lockout_until_ms"

    private val saltRegex = Regex("^[0-9a-fA-F]+$")

    // ---------------------------------------------------------------- input

    /** Prototype rules: PIN 4-6 digits, password 4+ chars, pattern 4+ dots. */
    fun isValidInput(type: LockType, input: String): Boolean = when (type) {
        LockType.PIN -> input.length in 4..6 && input.all { it.isDigit() }
        LockType.PASSWORD -> input.length >= 4
        LockType.PATTERN -> {
            val dots = input.split('-')
            dots.size >= 4 && dots.all { it.isNotEmpty() && it.all { c -> c.isDigit() } }
        }
        LockType.OFF -> false
    }

    // --------------------------------------------------------------- crypto

    fun generateSalt(): String {
        val bytes = ByteArray(SALT_BYTES)
        SecureRandom().nextBytes(bytes)
        return bytes.toHex()
    }

    fun hash(input: String, saltHex: String): String {
        val spec = PBEKeySpec(
            input.toCharArray(),
            saltHex.hexToBytes(),
            ITERATIONS,
            KEY_LENGTH_BITS,
        )
        // Standard JCA name (Android's KeyProperties.KEY_ALGORITHM_PBKDF2_WITH_SHA256
        // is the same string); literal keeps this JVM-testable without the stub.
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return factory.generateSecret(spec).encoded.toHex()
    }

    /** Constant-time compare of two hex digests (length leak is acceptable). */
    fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        return try {
            MessageDigest.isEqual(a.hexToBytes(), b.hexToBytes())
        } catch (_: Throwable) {
            false
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun String.hexToBytes(): ByteArray =
        ByteArray(length / 2) { index ->
            substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }

    // ------------------------------------------------------------- locking

    /**
     * Lockout deadline given the (already-incremented) failed-attempt count.
     * Pure so the backoff/lockout schedule is JVM-testable.
     */
    fun lockoutDeadlineFor(failedAttempts: Int, now: Long): Long = when {
        failedAttempts >= MAX_ATTEMPTS_BEFORE_LOCKOUT -> now + LOCKOUT_DURATION_MS
        failedAttempts >= BACKOFF_THRESHOLD -> {
            now + (1L shl (failedAttempts - BACKOFF_THRESHOLD)) * 1000L
        }
        else -> 0L
    }

    /** Set a new lock: hash the credential (off main thread) and persist. */
    suspend fun setLock(context: Context, type: LockType, input: String) {
        require(isValidInput(type, input)) { "Invalid $type credential" }
        // Patterns are stored as "1-2-3-4"; the length the unlock UI cares
        // about is the dot count, not the string length.
        val credentialLength = if (type == LockType.PATTERN) input.split('-').size else input.length
        val stored = withContext(Dispatchers.Default) {
            val salt = generateSalt()
            "$salt:${hash(input, salt)}"
        }
        context.setAppLock(type, stored, credentialLength = credentialLength)
        resetRateLimiter(context)
        // Rebuild from the live snapshot so settings this call does not own —
        // biometric, forgot-password, auto-lock and both COD-79 protection
        // flags — survive a credential change instead of snapping back to their
        // defaults. Hand-listing the fields here is what silently reset them.
        AppLockStateHolder.update(
            AppLockStateHolder.snapshot().copy(
                lockType = type,
                storedHash = stored,
                credentialLength = credentialLength,
            ),
        )
    }

    /**
     * Destroy the stored credential and clear the settings that depend on it.
     *
     * Note this is strictly stronger than either COD-79 toggle: with no
     * credential left, [AppLockStateHolder.hasCredential] is false, so the entry
     * gate and the feature-disabling guard both go inert no matter how the two
     * flags are set. The flags themselves are carried over untouched, so a user
     * who later sets a new lock gets their preferences back rather than a reset.
     */
    suspend fun disableLock(context: Context) {
        context.setAppLock(LockType.OFF, "", credentialLength = 0)
        context.setAppLockBiometric(false)
        context.setAppLockForgotDisabled(false)
        resetRateLimiter(context)
        AppLockStateHolder.update(
            AppLockStateHolder.snapshot().copy(
                lockType = LockType.OFF,
                storedHash = "",
                credentialLength = 0,
                biometricEnabled = false,
                forgotPasswordDisabled = false,
            ),
        )
    }

    /**
     * Verify a credential attempt. Returns false when the input is wrong, the
     * store is corrupt, or the rate limiter is active. Records failed attempts
     * and resets the limiter on success.
     */
    suspend fun verify(context: Context, input: String): Boolean {
        if (isLockedOut(context)) return false
        val stored = runCatching { context.appLockPrefs().first().storedHash }
            .getOrDefault("")
        if (stored.isBlank()) return false
        val parts = stored.split(":")
        if (parts.size != 2) return false
        val salt = parts[0]
        val expected = parts[1]
        // Reject corrupt salt/hash instead of crashing on hex decode.
        if (salt.length != SALT_BYTES * 2 || !salt.matches(saltRegex)) return false
        if (expected.length != 64 || !expected.matches(saltRegex)) return false

        val actual = withContext(Dispatchers.Default) { hash(input, salt) }
        val match = constantTimeEquals(actual, expected)
        if (match) {
            resetRateLimiter(context)
        } else {
            recordFailedAttempt(context)
        }
        return match
    }

    // -------------------------------------------------------- rate limiting

    private fun ratePrefs(context: Context) =
        context.applicationContext.getSharedPreferences(RATE_PREFS, Context.MODE_PRIVATE)

    private fun recordFailedAttempt(context: Context) {
        val prefs = ratePrefs(context)
        val attempts = prefs.getInt(KEY_FAILED_ATTEMPTS, 0) + 1
        val lockoutUntil = lockoutDeadlineFor(attempts, System.currentTimeMillis())
        prefs.edit()
            .putInt(KEY_FAILED_ATTEMPTS, attempts)
            .putLong(KEY_LOCKOUT_UNTIL_MS, lockoutUntil)
            .apply()
        Log.w(TAG, "failed attempt $attempts; lockoutUntil=${if (lockoutUntil > 0) lockoutUntil else "none"}")
    }

    private fun resetRateLimiter(context: Context) {
        ratePrefs(context).edit().clear().apply()
    }

    fun isLockedOut(context: Context): Boolean {
        val until = ratePrefs(context).getLong(KEY_LOCKOUT_UNTIL_MS, 0L)
        return until > 0L && System.currentTimeMillis() < until
    }

    fun getLockoutRemainingMs(context: Context): Long {
        val until = ratePrefs(context).getLong(KEY_LOCKOUT_UNTIL_MS, 0L)
        return if (until > 0L) (until - System.currentTimeMillis()).coerceAtLeast(0L) else 0L
    }
}
