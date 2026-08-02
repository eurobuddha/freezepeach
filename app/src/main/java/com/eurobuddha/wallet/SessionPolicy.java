package com.eurobuddha.wallet;

/**
 * SessionPolicy — the pure, Android-free rules for the unlock/session UX layer (auto-lock timeout and
 * passphrase minimum length). Kept dependency-free so the fund-adjacent decisions are directly
 * unit-testable off-device; nothing here touches the seed, keyuses or signing.
 *
 * <p>This is the ONLY place the "is the session stale?" arithmetic lives, so {@link WalletSession}
 * (Android) and the tests exercise identical logic.
 */
public final class SessionPolicy {

    private SessionPolicy() {}

    /** Auto-lock timeout meaning "lock as soon as the app leaves the foreground". */
    public static final long IMMEDIATELY_MS = 0L;

    /** Default auto-lock timeout: 5 minutes. */
    public static final long DEFAULT_TIMEOUT_MS = 5L * 60L * 1000L;

    /**
     * The offered auto-lock choices, in order, as {millis, label}. "Immediately" is {@code 0}.
     * Kept here so the Settings picker and any test read the SAME list.
     */
    public static final long[] TIMEOUT_CHOICES_MS = {
            IMMEDIATELY_MS,
            60L * 1000L,          // 1 min
            5L * 60L * 1000L,     // 5 min
            15L * 60L * 1000L,    // 15 min
            30L * 60L * 1000L,    // 30 min
            60L * 60L * 1000L     // 1 hour
    };

    public static final String[] TIMEOUT_LABELS = {
            "Immediately", "1 minute", "5 minutes", "15 minutes", "30 minutes", "1 hour"
    };

    /**
     * Minimum length for a NEW unlock passphrase set via "Change passphrase". Matches the encrypted
     * export minimum (12) — the passphrase protects the same seed material either way, so the stronger
     * of the two onboarding/export bars is used for a deliberate change.
     */
    public static final int MIN_PASSPHRASE = 8;

    /**
     * Should the session lock now? Given the last time the user was active ({@code lastActivityMs}), the
     * current time ({@code nowMs}) and the configured timeout:
     * <ul>
     *   <li>{@code timeoutMs <= 0} ("Immediately") — always lock (the caller only asks this after the app
     *       actually returned from the background, so "immediately" means "on every background").</li>
     *   <li>otherwise — lock once the idle gap has strictly exceeded the timeout.</li>
     * </ul>
     * Pure and side-effect free.
     */
    public static boolean shouldLock(long lastActivityMs, long nowMs, long timeoutMs) {
        if (timeoutMs <= 0) return true;
        return (nowMs - lastActivityMs) > timeoutMs;
    }

    /** True if {@code zPassphrase} meets the change-passphrase minimum length. */
    public static boolean passphraseMeetsMin(String zPassphrase) {
        return zPassphrase != null && zPassphrase.length() >= MIN_PASSPHRASE;
    }

    /** Index of {@code zTimeoutMs} within {@link #TIMEOUT_CHOICES_MS}, or the default's index if absent. */
    public static int indexOfTimeout(long zTimeoutMs) {
        for (int i = 0; i < TIMEOUT_CHOICES_MS.length; i++) {
            if (TIMEOUT_CHOICES_MS[i] == zTimeoutMs) return i;
        }
        // default = 5 min
        for (int i = 0; i < TIMEOUT_CHOICES_MS.length; i++) {
            if (TIMEOUT_CHOICES_MS[i] == DEFAULT_TIMEOUT_MS) return i;
        }
        return 0;
    }
}
